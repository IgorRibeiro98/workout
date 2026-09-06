/**
 * Quem fez a requisição, do ponto de vista do Spark Backend.
 *
 * É a **única** representação de identidade que o resto do backend conhece. Nenhum módulo de
 * produto (T16.2+) importa `firebase-admin`, `DecodedIdToken` ou qualquer tipo do provedor: se
 * um dia a identidade deixar de vir do Firebase, muda o verificador e nada mais.
 *
 * O recorte é deliberadamente mínimo. Um `DecodedIdToken` traz dezenas de claims (`aud`, `iss`,
 * `auth_time`, `firebase.identities`, `picture`, ...) e carregar todas elas pelo processo só
 * aumenta a chance de alguma acabar em log, em resposta ou em banco sem necessidade.
 *
 * O `uid` é o único campo obrigatório — e o único que a T16.3+ usará como `ownerUid`.
 */
export interface AuthenticatedPrincipal {
  /** Firebase UID. Sai sempre de um token verificado criptograficamente, nunca do payload. */
  readonly uid: string;
  /** Presente apenas quando o token traz; `undefined` é normal e precisa ser tratado. */
  readonly email?: string;
  /** Provedor da última autenticação (ex.: `google.com`), quando o token informa. */
  readonly provider?: string;
}
