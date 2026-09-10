import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { PostgresService } from '../../database/postgres.service';
import { allAccountUidsQuery } from './account-uid-inventory';

/**
 * As fases duráveis de uma exclusão em andamento (T17.13.1 §11, migration 0021).
 */
export type AccountDeletionPhase = 'LEDGER_PENDING' | 'FIREBASE_PENDING';

export interface StoredDeletionJob {
  readonly id: string;
  readonly firebase_uid: string;
  readonly uid_hash: string;
  readonly attempts: number;
  readonly last_error: string | null;
  readonly next_attempt_at: number;
  readonly created_at: number;
  readonly phase: AccountDeletionPhase;
}

@Injectable()
export class AccountDeletionRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * Purga todas as tabelas account-scoped do PostgreSQL.
   */
  async purgeAccountData(ownerUid: string): Promise<void> {
    await this.db.transaction(async (client) => {
      await this.purgeStatements(client, ownerUid);
    });
  }

  /**
   * Os `DELETE` do purge executados dentro de um PoolClient transacional.
   */
  async purgeStatements(client: PoolClient, ownerUid: string): Promise<void> {
    // 1. Sync
    await client.query(`DELETE FROM sync_entities WHERE owner_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM sync_changes WHERE owner_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM sync_mutations WHERE owner_uid = $1`, [ownerUid]);

    // 2. Backups
    await client.query(
      `DELETE FROM backup_items WHERE snapshot_id IN (SELECT id FROM backup_snapshots WHERE owner_uid = $1)`,
      [ownerUid],
    );
    await client.query(`DELETE FROM backup_snapshots WHERE owner_uid = $1`, [ownerUid]);

    // 3. IA Usage
    await client.query(`DELETE FROM ai_usage_daily WHERE uid = $1`, [ownerUid]);

    // 4. Notificações
    await client.query(
      `DELETE FROM social_notification_deliveries
       WHERE event_id IN (SELECT id FROM social_notification_events WHERE recipient_uid = $1)
          OR device_registration_id IN (SELECT id FROM social_push_devices WHERE owner_uid = $2)`,
      [ownerUid, ownerUid],
    );
    await client.query(`DELETE FROM social_notification_events WHERE recipient_uid = $1`, [
      ownerUid,
    ]);
    await client.query(`DELETE FROM social_push_devices WHERE owner_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM social_notification_preferences WHERE owner_uid = $1`, [
      ownerUid,
    ]);

    // 5. Configurações sociais
    await client.query(`DELETE FROM social_progress_settings WHERE owner_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM social_privacy_settings WHERE owner_uid = $1`, [ownerUid]);

    // 6. Grafo de amizades e solicitações
    await client.query(
      `DELETE FROM friend_requests WHERE requester_uid = $1 OR recipient_uid = $2`,
      [ownerUid, ownerUid],
    );
    await client.query(`DELETE FROM friendships WHERE user_a_uid = $1 OR user_b_uid = $2`, [
      ownerUid,
      ownerUid,
    ]);

    // 7. Bloqueios e denúncias
    await client.query(`DELETE FROM social_blocks WHERE blocker_uid = $1 OR blocked_uid = $2`, [
      ownerUid,
      ownerUid,
    ]);
    await client.query(`DELETE FROM social_reports WHERE reporter_uid = $1 OR reported_uid = $2`, [
      ownerUid,
      ownerUid,
    ]);

    // 8. Desafios
    await client.query(`DELETE FROM challenge_participants WHERE participant_uid = $1`, [ownerUid]);
    await client.query(
      `DELETE FROM challenge_invitations WHERE inviter_uid = $1 OR recipient_uid = $2`,
      [ownerUid, ownerUid],
    );
    await client.query(`DELETE FROM challenge_creation_requests WHERE owner_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM challenges WHERE creator_uid = $1`, [ownerUid]);

    // 9. Workout Shares
    await client.query(`DELETE FROM workout_shares WHERE sender_uid = $1 OR recipient_uid = $2`, [
      ownerUid,
      ownerUid,
    ]);

    // 10. Workout Check-ins e Conteúdo UGC
    await client.query(`DELETE FROM social_checkin_comments WHERE author_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM social_checkin_reactions WHERE reactor_uid = $1`, [ownerUid]);
    await client.query(
      `DELETE FROM social_checkin_comments
       WHERE checkin_id IN (SELECT id FROM social_workout_checkins WHERE author_uid = $1)`,
      [ownerUid],
    );
    await client.query(
      `DELETE FROM social_checkin_reactions
       WHERE checkin_id IN (SELECT id FROM social_workout_checkins WHERE author_uid = $1)`,
      [ownerUid],
    );
    await client.query(`DELETE FROM social_checkin_media WHERE owner_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM social_workout_checkins WHERE author_uid = $1`, [ownerUid]);

    // 11. Squads
    await client.query(
      `DELETE FROM social_group_checkin_shares
       WHERE group_id IN (SELECT id FROM social_groups WHERE owner_uid = $1)`,
      [ownerUid],
    );
    await client.query(
      `DELETE FROM social_group_memberships
       WHERE group_id IN (SELECT id FROM social_groups WHERE owner_uid = $1)`,
      [ownerUid],
    );
    await client.query(
      `DELETE FROM social_group_invitations
       WHERE group_id IN (SELECT id FROM social_groups WHERE owner_uid = $1)`,
      [ownerUid],
    );
    await client.query(`DELETE FROM social_groups WHERE owner_uid = $1`, [ownerUid]);

    await client.query(`DELETE FROM social_group_checkin_shares WHERE author_uid = $1`, [ownerUid]);
    await client.query(`DELETE FROM social_group_memberships WHERE member_uid = $1`, [ownerUid]);
    await client.query(
      `DELETE FROM social_group_invitations WHERE sender_uid = $1 OR recipient_uid = $2`,
      [ownerUid, ownerUid],
    );

    // 12. Perfil Social raiz
    await client.query(`DELETE FROM social_profiles WHERE owner_uid = $1`, [ownerUid]);
  }

  /**
   * O início da exclusão de conta, como **uma** transação atômica durável.
   */
  async beginAccountDeletion(input: {
    readonly firebaseUid: string;
    readonly uidHash: string;
    readonly tombstoneId: string;
    readonly jobId: string;
    readonly now: number;
  }): Promise<void> {
    await this.db.transaction(async (client) => {
      await client.query(
        `INSERT INTO account_deletion_tombstones (id, uid_hash, deleted_at)
         VALUES ($1, $2, $3)
         ON CONFLICT (uid_hash) DO UPDATE SET deleted_at = EXCLUDED.deleted_at`,
        [input.tombstoneId, input.uidHash, input.now],
      );

      await client.query(
        `INSERT INTO account_deletion_jobs
           (id, firebase_uid, uid_hash, attempts, next_attempt_at, created_at, phase)
         VALUES ($1, $2, $3, 0, $4, $5, 'LEDGER_PENDING')
         ON CONFLICT (firebase_uid) DO NOTHING`,
        [input.jobId, input.firebaseUid, input.uidHash, input.now, input.now],
      );

      await this.purgeStatements(client, input.firebaseUid);
    });
  }

  /**
   * As chaves de armazenamento de toda a mídia desta conta.
   */
  async listMediaStorageKeys(ownerUid: string): Promise<string[]> {
    const res = await this.db.query<{ key: string }>(
      `SELECT storage_key AS key FROM social_checkin_media WHERE owner_uid = $1`,
      [ownerUid],
    );
    return res.rows.map((row) => row.key);
  }

  /**
   * As chaves dos documentos de backup desta conta no Object Storage (T18.1 §36).
   *
   * Lidas **antes** do purge, como as de mídia: depois dele as linhas não existem mais. Snapshots
   * anteriores à T18.1 ainda não migrados não têm chave — o documento deles mora na própria linha,
   * e sai com ela.
   */
  async listBackupStorageKeys(ownerUid: string): Promise<string[]> {
    const res = await this.db.query<{ key: string }>(
      `SELECT storage_key AS key FROM backup_snapshots
        WHERE owner_uid = $1 AND storage_key IS NOT NULL`,
      [ownerUid],
    );
    return res.rows.map((row) => row.key);
  }

  async insertTombstone(id: string, uidHash: string, now: number): Promise<void> {
    await this.db.query(
      `INSERT INTO account_deletion_tombstones (id, uid_hash, deleted_at)
       VALUES ($1, $2, $3)
       ON CONFLICT (uid_hash) DO UPDATE SET deleted_at = EXCLUDED.deleted_at`,
      [id, uidHash, now],
    );
  }

  async isTombstoned(uidHash: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM account_deletion_tombstones WHERE uid_hash = $1 LIMIT 1`,
      [uidHash],
    );
    return res.rows.length > 0;
  }

  async insertJob(id: string, firebaseUid: string, uidHash: string, now: number): Promise<void> {
    await this.db.query(
      `INSERT INTO account_deletion_jobs (id, firebase_uid, uid_hash, attempts, next_attempt_at, created_at)
       VALUES ($1, $2, $3, 0, $4, $5)
       ON CONFLICT (firebase_uid) DO NOTHING`,
      [id, firebaseUid, uidHash, now, now],
    );
  }

  async findDueJobs(now: number, limit: number): Promise<StoredDeletionJob[]> {
    const res = await this.db.query<StoredDeletionJob>(
      `SELECT id, firebase_uid, uid_hash, attempts, last_error, next_attempt_at, created_at, phase
       FROM account_deletion_jobs
       WHERE next_attempt_at <= $1
       ORDER BY next_attempt_at ASC
       LIMIT $2`,
      [now, limit],
    );
    return res.rows;
  }

  async updateJobPhase(
    id: string,
    phase: AccountDeletionPhase,
    nextAttemptAt: number,
  ): Promise<void> {
    await this.db.query(
      `UPDATE account_deletion_jobs SET phase = $1, last_error = NULL, next_attempt_at = $2 WHERE id = $3`,
      [phase, nextAttemptAt, id],
    );
  }

  async findJobByFirebaseUid(firebaseUid: string): Promise<StoredDeletionJob | undefined> {
    const res = await this.db.query<StoredDeletionJob>(
      `SELECT id, firebase_uid, uid_hash, attempts, last_error, next_attempt_at, created_at, phase
       FROM account_deletion_jobs WHERE firebase_uid = $1 LIMIT 1`,
      [firebaseUid],
    );
    return res.rows[0];
  }

  async hasPendingJob(firebaseUid: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM account_deletion_jobs WHERE firebase_uid = $1 LIMIT 1`,
      [firebaseUid],
    );
    return res.rows.length > 0;
  }

  async deleteJob(id: string): Promise<void> {
    await this.db.query(`DELETE FROM account_deletion_jobs WHERE id = $1`, [id]);
  }

  async deleteJobByFirebaseUid(firebaseUid: string): Promise<void> {
    await this.db.query(`DELETE FROM account_deletion_jobs WHERE firebase_uid = $1`, [firebaseUid]);
  }

  async incrementJobAttempt(id: string, error: string, nextAttemptAt: number): Promise<void> {
    await this.db.query(
      `UPDATE account_deletion_jobs
       SET attempts = attempts + 1, last_error = $1, next_attempt_at = $2
       WHERE id = $3`,
      [error, nextAttemptAt, id],
    );
  }

  async listAllOwnerUidsInDatabase(): Promise<string[]> {
    const res = await this.db.query<{ owner_uid: string }>(allAccountUidsQuery());
    return res.rows.map((r) => r.owner_uid);
  }
}
