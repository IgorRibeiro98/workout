import type { AuthenticatedPrincipal } from '../../src/modules/auth/authenticated-principal';
import {
  type AuthTokenVerifier,
  InvalidTokenError,
  VerifierUnavailableError,
} from '../../src/modules/auth/auth-token-verifier';

/**
 * Verificador de teste.
 *
 * Vive em `test/`, e só em `test/`: não existe provider, variável de ambiente ou flag que faça o
 * processo de produção usá-lo. É esta separação — e não um `AUTH_DISABLED=true` — que permite ao
 * CI rodar sem service account, sem conta Google e sem rede.
 *
 * Ele **não** decodifica JWT: mapeia tokens conhecidos para respostas conhecidas. A verificação
 * criptográfica é justamente o que o dublê não pode simular, então ele nem finge.
 */
export class FakeAuthTokenVerifier implements AuthTokenVerifier {
  readonly seen: string[] = [];
  readonly deletedUids: string[] = [];
  deleteUserFailure?: Error;

  constructor(private readonly principals: Map<string, AuthenticatedPrincipal> = new Map()) {}

  static withPrincipal(token: string, principal: AuthenticatedPrincipal): FakeAuthTokenVerifier {
    return new FakeAuthTokenVerifier(new Map([[token, principal]]));
  }

  accept(token: string, principal: AuthenticatedPrincipal): this {
    this.principals.set(token, principal);
    return this;
  }

  verify(idToken: string): Promise<AuthenticatedPrincipal> {
    this.seen.push(idToken);

    if (idToken === UNAVAILABLE_TOKEN) {
      return Promise.reject(new VerifierUnavailableError('verificador indisponível'));
    }
    if (idToken === EXPLODING_TOKEN) {
      return Promise.reject(new TypeError('falha inesperada dentro do verificador'));
    }

    const principal = this.principals.get(idToken);
    if (!principal) {
      return Promise.reject(new InvalidTokenError('token não reconhecido'));
    }
    return Promise.resolve(principal);
  }

  deleteUser(uid: string): Promise<void> {
    this.deletedUids.push(uid);
    if (this.deleteUserFailure) {
      return Promise.reject(this.deleteUserFailure);
    }
    return Promise.resolve();
  }
}

/** Token que faz o verificador reportar indisponibilidade (503), não credencial inválida (401). */
export const UNAVAILABLE_TOKEN = 'verifier-unavailable';

/** Token que faz o verificador quebrar de forma imprevista — não pode virar autenticação. */
export const EXPLODING_TOKEN = 'verifier-explodes';
