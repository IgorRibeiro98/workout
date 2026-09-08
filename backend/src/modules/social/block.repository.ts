import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { BlockedUserItemDto } from './block.contract';

@Injectable()
export class BlockRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /** Cria ou ignora bloqueio (idempotente). */
  createBlock(id: string, blockerUid: string, blockedUid: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `INSERT INTO social_blocks (id, blocker_uid, blocked_uid, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (blocker_uid, blocked_uid) DO NOTHING`,
    ).run(id, blockerUid, blockedUid, now);
  }

  /** Remove bloqueio (idempotente). */
  deleteBlock(blockerUid: string, blockedUid: string): boolean {
    const db = this.sqlite.connection;
    const info = db
      .prepare(`DELETE FROM social_blocks WHERE blocker_uid = ? AND blocked_uid = ?`)
      .run(blockerUid, blockedUid);
    return info.changes > 0;
  }

  /** Confere se existe bloqueio em qualquer uma das duas direções. */
  isBlockedBidirectional(uidA: string, uidB: string): boolean {
    const db = this.sqlite.connection;
    const row = db
      .prepare(
        `SELECT 1 FROM social_blocks
         WHERE (blocker_uid = ? AND blocked_uid = ?)
            OR (blocker_uid = ? AND blocked_uid = ?)
         LIMIT 1`,
      )
      .get(uidA, uidB, uidB, uidA);
    return row !== undefined;
  }

  /** Retorna conjunto de todos os UIDs bloqueados pelo usuário ou que bloquearam o usuário. */
  findBlockedUidsBidirectional(uid: string): Set<string> {
    const db = this.sqlite.connection;
    const rows = db
      .prepare(
        `SELECT blocked_uid AS uid FROM social_blocks WHERE blocker_uid = ?
         UNION
         SELECT blocker_uid AS uid FROM social_blocks WHERE blocked_uid = ?`,
      )
      .all(uid, uid) as Array<{ uid: string }>;
    return new Set(rows.map((r) => r.uid));
  }

  /** Lista usuários bloqueados pelo blockerUid. */
  listBlocked(blockerUid: string): BlockedUserItemDto[] {
    const db = this.sqlite.connection;
    const rows = db
      .prepare(
        `SELECT p.social_id AS socialId, p.display_name AS displayName, b.created_at AS blockedAt
         FROM social_blocks b
         JOIN social_profiles p ON b.blocked_uid = p.owner_uid
         WHERE b.blocker_uid = ?
         ORDER BY b.created_at DESC`,
      )
      .all(blockerUid) as BlockedUserItemDto[];
    return rows;
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
  cleanupSharedRelationsOnBlock(blockerUid: string, blockedUid: string, now: number): void {
    const db = this.sqlite.connection;
    const tx = db.transaction(() => {
      // 1. Remove amizades bilaterais
      db.prepare(
        `DELETE FROM friendships
         WHERE (user_a_uid = ? AND user_b_uid = ?)
            OR (user_a_uid = ? AND user_b_uid = ?)`,
      ).run(blockerUid, blockedUid, blockedUid, blockerUid);

      // 2. Cancela pedidos de amizade pendentes entre o par
      db.prepare(
        `UPDATE friend_requests
         SET status = 'CANCELLED', updated_at = ?
         WHERE status = 'PENDING'
           AND ((requester_uid = ? AND recipient_uid = ?)
             OR (requester_uid = ? AND recipient_uid = ?))`,
      ).run(now, blockerUid, blockedUid, blockedUid, blockerUid);

      // 3. Cancela convites de desafios pendentes entre o par
      db.prepare(
        `UPDATE challenge_invitations
         SET status = 'DECLINED', updated_at = ?
         WHERE status = 'PENDING'
           AND ((inviter_uid = ? AND recipient_uid = ?)
             OR (inviter_uid = ? AND recipient_uid = ?))`,
      ).run(now, blockerUid, blockedUid, blockedUid, blockerUid);

      // 4. Desafios compartilhados:
      // Se blocker é creator e blocked é member em desafio não encerrado -> retira o blocked
      db.prepare(
        `UPDATE challenge_participants
         SET status = 'WITHDRAWN', left_at = ?
         WHERE status = 'JOINED'
           AND participant_uid = ?
           AND challenge_id IN (
             SELECT challenge_id FROM challenges
             WHERE creator_uid = ? AND lifecycle = 'OPEN'
           )`,
      ).run(now, blockedUid, blockerUid);

      // Se blocker é member em desafio não encerrado criado pelo blocked ou terceiro compartilhado -> retira o blocker
      db.prepare(
        `UPDATE challenge_participants
         SET status = 'WITHDRAWN', left_at = ?
         WHERE status = 'JOINED'
           AND participant_uid = ?
           AND challenge_id IN (
             SELECT challenge_id FROM challenges
             WHERE creator_uid = ? AND lifecycle = 'OPEN'
           )`,
      ).run(now, blockerUid, blockedUid);

      // Para desafios de terceiros onde ambos estão JOINED -> blocker é retirado
      db.prepare(
        `UPDATE challenge_participants
         SET status = 'WITHDRAWN', left_at = ?
         WHERE status = 'JOINED'
           AND participant_uid = ?
           AND challenge_id IN (
             SELECT p1.challenge_id FROM challenge_participants p1
             JOIN challenge_participants p2 ON p1.challenge_id = p2.challenge_id
             JOIN challenges c ON p1.challenge_id = c.challenge_id
             WHERE p1.participant_uid = ?
               AND p2.participant_uid = ?
               AND p1.status = 'JOINED'
               AND p2.status = 'JOINED'
               AND c.lifecycle = 'OPEN'
           )`,
      ).run(now, blockerUid, blockerUid, blockedUid);

      // 5. Cancela notificações de outbox pendentes para ambos
      db.prepare(
        `UPDATE social_notification_events
         SET status = 'CANCELLED', completed_at = ?
         WHERE status = 'PENDING'
           AND (recipient_uid = ? OR recipient_uid = ?)`,
      ).run(now, blockerUid, blockedUid);

      db.prepare(
        `UPDATE social_notification_deliveries
         SET status = 'FAILED_PERMANENT'
         WHERE status = 'PENDING'
           AND event_id IN (
             SELECT id FROM social_notification_events WHERE status = 'CANCELLED'
           )`,
      ).run();
    });

    tx();
  }
}
