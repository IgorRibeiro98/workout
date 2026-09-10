import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import type { BlockedUserItemDto } from './block.contract';

@Injectable()
export class BlockRepository {
  constructor(private readonly db: PostgresService) {}

  /** Cria ou ignora bloqueio (idempotente). */
  async createBlock(id: string, blockerUid: string, blockedUid: string, now: number): Promise<void> {
    await this.db.query(
      `INSERT INTO social_blocks (id, blocker_uid, blocked_uid, created_at)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (blocker_uid, blocked_uid) DO NOTHING`,
      [id, blockerUid, blockedUid, now],
    );
  }

  /** Remove bloqueio (idempotente). */
  async deleteBlock(blockerUid: string, blockedUid: string): Promise<boolean> {
    const res = await this.db.query(
      `DELETE FROM social_blocks WHERE blocker_uid = $1 AND blocked_uid = $2`,
      [blockerUid, blockedUid],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /** Confere se existe bloqueio em qualquer uma das duas direções. */
  async isBlockedBidirectional(uidA: string, uidB: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM social_blocks
       WHERE (blocker_uid = $1 AND blocked_uid = $2)
          OR (blocker_uid = $3 AND blocked_uid = $4)
       LIMIT 1`,
      [uidA, uidB, uidB, uidA],
    );
    return res.rows.length > 0;
  }

  /** Retorna conjunto de todos os UIDs bloqueados pelo usuário ou que bloquearam o usuário. */
  async findBlockedUidsBidirectional(uid: string): Promise<Set<string>> {
    const res = await this.db.query<{ uid: string }>(
      `SELECT blocked_uid AS uid FROM social_blocks WHERE blocker_uid = $1
       UNION
       SELECT blocker_uid AS uid FROM social_blocks WHERE blocked_uid = $2`,
      [uid, uid],
    );
    return new Set(res.rows.map((r) => r.uid));
  }

  /** Lista usuários bloqueados pelo blockerUid. */
  async listBlocked(blockerUid: string): Promise<BlockedUserItemDto[]> {
    const res = await this.db.query<{
      socialId: string;
      displayName: string;
      blockedAt: string | number;
    }>(
      `SELECT p.social_id AS "socialId", p.display_name AS "displayName", b.created_at AS "blockedAt"
       FROM social_blocks b
       JOIN social_profiles p ON b.blocked_uid = p.owner_uid
       WHERE b.blocker_uid = $1
       ORDER BY b.created_at DESC`,
      [blockerUid],
    );
    return res.rows.map((r) => ({
      socialId: r.socialId,
      displayName: r.displayName,
      blockedAt: Number(r.blockedAt),
    }));
  }

  /**
   * Executa a limpeza server-side de relacionamentos mútuos no momento do bloqueio:
   * 1. Remove amizades;
   * 2. Cancela pedidos de amizade pendentes;
   * 3. Cancela convites de desafio pendentes entre o par;
   * 4. Trata desafios ativos/upcoming compartilhados:
   *    - Blocker creator: retira blocked member (WITHDRAWN);
   *    - Blocker member: retira blocker (WITHDRAWN);
   * 5. Cancela eventos de notificação pendentes.
   */
  async cleanupSharedRelationsOnBlock(blockerUid: string, blockedUid: string, now: number): Promise<void> {
    await this.db.transaction(async (client) => {
      // 1. Remove amizades bilaterais
      await client.query(
        `DELETE FROM friendships
         WHERE (user_a_uid = $1 AND user_b_uid = $2)
            OR (user_a_uid = $3 AND user_b_uid = $4)`,
        [blockerUid, blockedUid, blockedUid, blockerUid],
      );

      // 2. Cancela pedidos de amizade pendentes entre o par
      await client.query(
        `UPDATE friend_requests
         SET status = 'CANCELLED', updated_at = $1
         WHERE status = 'PENDING'
           AND ((requester_uid = $2 AND recipient_uid = $3)
             OR (requester_uid = $4 AND recipient_uid = $5))`,
        [now, blockerUid, blockedUid, blockedUid, blockerUid],
      );

      // 3. Cancela convites de desafios pendentes entre o par
      await client.query(
        `UPDATE challenge_invitations
         SET status = 'DECLINED', updated_at = $1
         WHERE status = 'PENDING'
           AND ((inviter_uid = $2 AND recipient_uid = $3)
             OR (inviter_uid = $4 AND recipient_uid = $5))`,
        [now, blockerUid, blockedUid, blockedUid, blockerUid],
      );

      // 4. Desafios compartilhados:
      // Se blocker é creator e blocked é member em desafio não encerrado -> retira o blocked
      await client.query(
        `UPDATE challenge_participants
         SET status = 'WITHDRAWN', left_at = $1
         WHERE status = 'JOINED'
           AND participant_uid = $2
           AND challenge_id IN (
             SELECT challenge_id FROM challenges
             WHERE creator_uid = $3 AND lifecycle = 'OPEN'
           )`,
        [now, blockedUid, blockerUid],
      );

      // Se blocker é member em desafio não encerrado criado pelo blocked ou terceiro compartilhado -> retira o blocker
      await client.query(
        `UPDATE challenge_participants
         SET status = 'WITHDRAWN', left_at = $1
         WHERE status = 'JOINED'
           AND participant_uid = $2
           AND challenge_id IN (
             SELECT challenge_id FROM challenges
             WHERE creator_uid = $3 AND lifecycle = 'OPEN'
           )`,
        [now, blockerUid, blockedUid],
      );

      // Para desafios de terceiros onde ambos estão JOINED -> blocker é retirado
      await client.query(
        `UPDATE challenge_participants
         SET status = 'WITHDRAWN', left_at = $1
         WHERE status = 'JOINED'
           AND participant_uid = $2
           AND challenge_id IN (
             SELECT p1.challenge_id FROM challenge_participants p1
             JOIN challenge_participants p2 ON p1.challenge_id = p2.challenge_id
             JOIN challenges c ON p1.challenge_id = c.challenge_id
             WHERE p1.participant_uid = $3
               AND p2.participant_uid = $4
               AND p1.status = 'JOINED'
               AND p2.status = 'JOINED'
               AND c.lifecycle = 'OPEN'
           )`,
        [now, blockerUid, blockerUid, blockedUid],
      );

      // 5. Cancela workout shares PENDING ou ACCEPTED entre o par
      await client.query(
        `UPDATE workout_shares
         SET status = 'CANCELLED', cancelled_at = $1
         WHERE status IN ('PENDING', 'ACCEPTED')
           AND ((sender_uid = $2 AND recipient_uid = $3)
             OR (sender_uid = $4 AND recipient_uid = $5))`,
        [now, blockerUid, blockedUid, blockedUid, blockerUid],
      );

      // 6. Convites de Squad pendentes entre o par (T17.11 §105).
      await client.query(
        `UPDATE social_group_invitations
         SET status = 'CANCELLED', responded_at = $1
         WHERE status = 'PENDING'
           AND ((sender_uid = $2 AND recipient_uid = $3)
             OR (sender_uid = $4 AND recipient_uid = $5))`,
        [now, blockerUid, blockedUid, blockedUid, blockerUid],
      );

      // 7. Cancela notificações de outbox pendentes para ambos
      await client.query(
        `UPDATE social_notification_events
         SET status = 'CANCELLED', completed_at = $1
         WHERE status = 'PENDING'
           AND (recipient_uid = $2 OR recipient_uid = $3)`,
        [now, blockerUid, blockedUid],
      );

      await client.query(
        `UPDATE social_notification_deliveries
         SET status = 'FAILED_PERMANENT'
         WHERE status = 'PENDING'
           AND event_id IN (
             SELECT id FROM social_notification_events WHERE status = 'CANCELLED'
           )`,
      );
    });
  }
}
