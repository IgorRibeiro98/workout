import {
  CanActivate,
  ExecutionContext,
  Inject,
  Injectable,
  ServiceUnavailableException,
  UnauthorizedException,
} from '@nestjs/common';
import type { Request } from 'express';
import { SparkLogger } from '../../common/logger';
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
