import type { AuthenticatedPrincipal } from './authenticated-principal';

/**
 * Fronteira de verificação de identidade.
 *
 * Uma implementação **precisa** verificar a assinatura do token criptograficamente. Decodificar o
 * payload de um JWT (base64) e confiar no `sub` não é verificação: qualquer pessoa consegue
 * escrever um JWT com o `uid` que quiser. A implementação real
 * (`FirebaseAuthTokenVerifier`) delega ao Firebase Admin SDK, que valida assinatura, emissor,
 * audiência e expiração contra as chaves públicas do Google.
 *
 * Existe como interface por um motivo concreto: os testes precisam exercitar guard, controller e
 * envelope de erro sem service account, sem rede e sem projeto Firebase real.
 */
export interface AuthTokenVerifier {
  /**
   * @throws {InvalidTokenError} token ausente, malformado, expirado, revogado ou de outro projeto.
   * @throws {VerifierUnavailableError} o verificador não conseguiu decidir (credencial ausente,
   * falha de rede ao buscar as chaves públicas). Não é o mesmo que credencial inválida.
   */
  verify(idToken: string): Promise<AuthenticatedPrincipal>;

  /**
   * Exclui o usuário do provedor de autenticação (Firebase Auth).
   * Se o usuário já não existir (user-not-found), deve convergir com sucesso.
   */
  deleteUser?(uid: string): Promise<void>;
}

/** O token foi avaliado e recusado. Vira 401. */
export class InvalidTokenError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = 'InvalidTokenError';
  }
}

/**
 * O token não pôde ser avaliado. Vira 503 — e não 401.
 *
 * A diferença importa: 401 diz ao cliente "sua credencial não serve" (e o Android poderia
 * deslogar o usuário); 503 diz "tente de novo mais tarde", que é a verdade quando o problema é do
 * servidor. Indisponibilidade do backend nunca pode apagar a identidade local (ARCHITECTURE §17).
 */
export class VerifierUnavailableError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = 'VerifierUnavailableError';
  }
}

/** Token de injeção: o resto do backend depende da interface, nunca da implementação Firebase. */
export const AUTH_TOKEN_VERIFIER = Symbol('AUTH_TOKEN_VERIFIER');
