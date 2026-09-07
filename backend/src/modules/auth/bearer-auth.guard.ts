import {
  CanActivate,
  ExecutionContext,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  ServiceUnavailableException,
  UnauthorizedException,
} from '@nestjs/common';
import type { Request } from 'express';
import { SparkLogger } from '../../common/logger';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import type { RequestWithId } from '../../common/request-id.middleware';
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
    private readonly logger: SparkLogger,
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
