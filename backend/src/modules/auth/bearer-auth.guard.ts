import { createHmac } from 'node:crypto';
import {
  CanActivate,
  ExecutionContext,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  Optional,
  ServiceUnavailableException,
  UnauthorizedException,
} from '@nestjs/common';
import type { Request } from 'express';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { SparkLogger } from '../../common/logger';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import type { RequestWithId } from '../../common/request-id.middleware';
import { PostgresService } from '../../database/postgres.service';
import type { AuthenticatedPrincipal } from './authenticated-principal';
import {
  AUTH_TOKEN_VERIFIER,
  type AuthTokenVerifier,
  InvalidTokenError,
  VerifierUnavailableError,
} from './auth-token-verifier';

export interface RequestWithPrincipal extends Request {
  principal?: AuthenticatedPrincipal;
}

/** `Bearer <token>`, com o esquema case-insensitive como manda o RFC 6750. */
const BEARER = /^Bearer[ \t]+(\S+)$/i;

export const UNAUTHENTICATED_CODE = 'UNAUTHENTICATED';
export const AUTH_UNAVAILABLE_CODE = 'AUTH_UNAVAILABLE';
export const API_RATE_LIMITED_CODE = 'API_RATE_LIMITED';

/**
 * O estado da conta não pôde ser avaliado (T17.13.1 §19).
 *
 * Separado de `AUTH_UNAVAILABLE`: lá o token não pôde ser verificado, aqui ele foi verificado e o
 * que falhou foi a consulta ao tombstone. As duas viram 503, e distingui-las é o que permite ao
 * operador saber se o problema é o Firebase ou o banco local.
 */
export const ACCOUNT_STATE_UNAVAILABLE_CODE = 'ACCOUNT_STATE_UNAVAILABLE';

/**
 * A única superfície que uma conta com tombstone ainda alcança: consultar e reexecutar a própria
 * exclusão. Tudo o mais responde `403 ACCOUNT_DELETED`.
 */
export const ACCOUNT_ROUTE_BASE = '/v1/account';

/**
 * Teto geral por conta, para **qualquer** rota autenticada (T16.8 §84).
 *
 * Deliberadamente alto: ele não substitui os limites específicos do sync e do backup, que são bem
 * mais apertados porque conhecem o custo de cada rota. Este aqui existe para o caso que nenhum
 * deles cobre — uma rota nova, ou o `/v1/auth/me` — e para a única falha que importa aqui: um
 * cliente em laço. 600 por minuto é ordens de grandeza acima de qualquer uso legítimo (um restore
 * inteiro faz três requisições) e ordens de grandeza abaixo do que um laço produz.
 *
 * Um teto baixo demais aqui seria pior que não ter teto: pararia um restore legítimo, que é
 * exatamente o momento em que o usuário mais precisa do servidor.
 */
export const GENERAL_API_RATE_LIMIT = {
  windowMs: 60_000,
  maxRequestsPerWindow: 600,
} as const;

/**
 * A porta de entrada da identidade: nada passa daqui sem um Firebase ID Token verificado.
 *
 * O que o guard **não** faz, por decisão:
 *
 * - não lê `uid` de query string, header próprio, cookie ou corpo — o único `uid` que existe no
 *   servidor é o que saiu do token verificado (contrato de identidade, seção "Ownership");
 * - não decodifica o JWT por conta própria para "adiantar" o `uid`;
 * - não tem modo de bypass. Não existe `AUTH_DISABLED`: sem verificador utilizável, a resposta é
 *   503, nunca 200.
 *
 * Ele também não é global. Health é infraestrutura e continua público; a proteção é declarada na
 * rota, onde dá para ler o que está protegido.
 */
@Injectable()
export class BearerAuthGuard implements CanActivate {
  /**
   * Contagem por conta, e nunca por IP (§85).
   *
   * Em rede móvel e atrás de NAT o IP é compartilhado por gente que não tem nada a ver com o
   * abuso — e, com o Caddy à frente, todo mundo chegaria aqui com o mesmo endereço. A conta é a
   * única identidade que este servidor conhece de verdade.
   *
   * A contagem acontece **depois** da verificação do token: um atacante sem credencial não pode
   * gastar a janela de uma conta que não é dele.
   */
  private readonly limiter = new FixedWindowRateLimiter(GENERAL_API_RATE_LIMIT);

  constructor(
    @Inject(AUTH_TOKEN_VERIFIER) private readonly verifier: AuthTokenVerifier,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
    @Optional() private readonly db?: PostgresService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<RequestWithPrincipal>();
    const requestId = (request as RequestWithId).requestId ?? 'unknown';

    const token = extractBearerToken(request.headers.authorization);
    if (token === null) {
      // Nem o header, nem o token, nem parte dele vão para o log — só o fato e o requestId.
      this.logger.info('auth.rejected', { requestId, reason: 'missing_or_malformed' });
      throw unauthenticated();
    }

    let principal: AuthenticatedPrincipal;
    try {
      principal = await this.verifier.verify(token);
    } catch (error) {
      if (error instanceof VerifierUnavailableError) {
        this.logger.error('auth.unavailable', { requestId });
        throw new ServiceUnavailableException({
          code: AUTH_UNAVAILABLE_CODE,
          message: 'Authentication is temporarily unavailable',
        });
      }
      if (error instanceof InvalidTokenError) {
        this.logger.info('auth.rejected', { requestId, reason: 'invalid_token' });
        throw unauthenticated();
      }
      // Um verificador que quebrou de forma imprevista não autentica ninguém.
      this.logger.error('auth.verifier.unexpected', {
        requestId,
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
      throw new ServiceUnavailableException({
        code: AUTH_UNAVAILABLE_CODE,
        message: 'Authentication is temporarily unavailable',
      });
    }

    // Se a conta já foi excluída, rejeita qualquer operação exceto rotas sob /v1/account.
    if (!isAccountRoutePath(request)) {
      let deleted: boolean;
      try {
        deleted = await this.isTombstoned(principal.uid);
      } catch (error) {
        this.logger.error('auth.tombstone.unavailable', {
          requestId,
          uidPrefix: uidPrefix(principal.uid),
          errorName: error instanceof Error ? error.name : 'UnknownError',
        });
        throw new ServiceUnavailableException({
          code: ACCOUNT_STATE_UNAVAILABLE_CODE,
          message: 'Account state is temporarily unavailable',
        });
      }

      if (deleted) {
        this.logger.warn('auth.account_deleted', {
          requestId,
          uidPrefix: uidPrefix(principal.uid),
        });
        throw new HttpException(
          { code: 'ACCOUNT_DELETED', message: 'Esta conta foi excluída.' },
          HttpStatus.FORBIDDEN,
        );
      }
    }

    if (!this.limiter.tryAcquire(principal.uid)) {
      this.logger.warn('auth.rate_limited', { requestId, uidPrefix: uidPrefix(principal.uid) });
      throw new HttpException(
        { code: API_RATE_LIMITED_CODE, message: 'too many requests for this account' },
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    request.principal = principal;
    this.logger.info('auth.accepted', { requestId, uidPrefix: uidPrefix(principal.uid) });
    return true;
  }

  /**
   * A conta tem tombstone?
   */
  private async isTombstoned(uid: string): Promise<boolean> {
    if (!this.db) return false;
    if (!this.db.isOpen) {
      throw new Error('a conexão PostgreSQL não está aberta');
    }
    const hash = createHmac('sha256', this.config.accountDeletionHmacKey).update(uid).digest('hex');
    const res = await this.db.query(
      `SELECT 1 FROM account_deletion_tombstones WHERE uid_hash = $1 LIMIT 1`,
      [hash],
    );
    return res.rows.length > 0;
  }
}

/**
 * O caminho da requisição, sem query string e sem fragmento.
 *
 * `originalUrl` carrega `?a=b`; `url` também. Comparar a URL inteira contra um prefixo de rota
 * deixa a decisão nas mãos de quem escreve a query string.
 */
export function requestPath(rawUrl: string | undefined): string {
  if (!rawUrl) {
    return '';
  }
  const queryStart = rawUrl.search(/[?#]/);
  return queryStart === -1 ? rawUrl : rawUrl.slice(0, queryStart);
}

/**
 * A requisição endereça `/v1/account` ou algo abaixo dela?
 *
 * Prefixo de **segmento**: `/v1/account` e `/v1/account/deletion-status` casam; um futuro
 * `/v1/accounts` ou `/v1/account-recovery` não — o que impede que uma rota nova herde por acidente
 * a isenção do tombstone.
 */
export function isAccountRoutePath(request: { originalUrl?: string; url?: string }): boolean {
  const path = requestPath(request.originalUrl ?? request.url);
  return path === ACCOUNT_ROUTE_BASE || path.startsWith(`${ACCOUNT_ROUTE_BASE}/`);
}

/**
 * O token, ou `null` quando o header está ausente, duplicado ou fora do formato.
 *
 * Um `Authorization` repetido chega como array no Express; aceitar o primeiro seria escolher
 * arbitrariamente entre credenciais conflitantes.
 */
export function extractBearerToken(header: string | string[] | undefined): string | null {
  if (typeof header !== 'string') {
    return null;
  }
  const match = BEARER.exec(header.trim());
  return match ? match[1] : null;
}

/**
 * Prefixo do uid para correlacionar log com suporte sem registrar o identificador inteiro.
 * Um uid do Firebase tem 28 caracteres; 6 não identificam ninguém sozinhos.
 */
export function uidPrefix(uid: string): string {
  return uid.slice(0, 6);
}

function unauthenticated(): UnauthorizedException {
  // Mensagem única para ausência, formato inválido e token recusado: dizer *qual* dos três
  // falhou só ajudaria quem está tentando adivinhar um token válido.
  return new UnauthorizedException({
    code: UNAUTHENTICATED_CODE,
    message: 'A valid Firebase ID token is required',
  });
}
