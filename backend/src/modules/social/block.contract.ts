/**
 * Contrato de Bloqueio Social (T17.6).
 *
 * Bloquear é server-authoritative e bilateral nas restrições:
 * - A blocks B impede A e B de interagirem (amizade, requests, perfis, ranking, atividade, convites).
 * - Remove amizade imediatamente se existir.
 * - Cancela pedidos de amizade pendentes.
 * - Resolve participações em desafios ativos compartilhados.
 * - Cancela notificações sociais de outbox pendentes entre o par.
 * - O ato de bloquear NÃO gera push.
 * - Desbloquear NÃO restaura amizade, pedidos ou desafios.
 */

export const BLOCKS_ROUTE_PREFIX = 'social/blocks';

export interface BlockUserRequestDto {
  readonly blockedSocialId: string;
}

export interface BlockedUserItemDto {
  readonly socialId: string;
  readonly displayName: string;
  readonly blockedAt: number;
}

export interface BlockUserResponseDto {
  readonly result: 'BLOCKED';
  readonly blockedSocialId: string;
}

export interface UnblockUserResponseDto {
  readonly result: 'UNBLOCKED';
  readonly unblockedSocialId: string;
}

export interface ListBlockedUsersResponseDto {
  readonly blockedUsers: BlockedUserItemDto[];
}
