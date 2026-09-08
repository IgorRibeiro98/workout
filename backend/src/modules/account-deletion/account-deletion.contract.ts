/**
 * Contrato de Exclusão de Conta (T17.6).
 *
 * Exclui a conta online do usuário e seus dados associados na VPS e no Firebase Auth.
 * Treinos locais no Room continuam no aparelho (local-first).
 */

export const ACCOUNT_DELETION_ROUTE_PREFIX = 'account';

export type AccountDeletionStatus = 'DELETED' | 'DELETION_PENDING';

export interface AccountDeletionResponseDto {
  readonly status: AccountDeletionStatus;
}
