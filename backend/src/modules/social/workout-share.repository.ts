import { Injectable } from '@nestjs/common';
import { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import type {
  WorkoutShareItemDto,
  WorkoutShareStatus,
  WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';

export interface StoredWorkoutShare {
  readonly id: string;
  readonly sender_uid: string;
  readonly recipient_uid: string;
  readonly snapshot_version: number;
  readonly snapshot_json: string;
  readonly snapshot_hash: string;
  readonly status: WorkoutShareStatus;
  readonly client_request_id: string;
  readonly created_at: number;
  readonly accepted_at: number | null;
  readonly imported_at: number | null;
  readonly declined_at: number | null;
  readonly cancelled_at: number | null;
  readonly expires_at: number;
}

interface WorkoutShareRow {
  id: string;
  sender_uid: string;
  recipient_uid: string;
  snapshot_version: number | string;
  snapshot_json: string;
  snapshot_hash: string;
  status: string;
  client_request_id: string;
  created_at: number | string;
  accepted_at: number | string | null;
  imported_at: number | string | null;
  declined_at: number | string | null;
  cancelled_at: number | string | null;
  expires_at: number | string;
}

@Injectable()
export class WorkoutShareRepository {
  constructor(private readonly db: PostgresService) {}

  private toDomain(row: WorkoutShareRow): StoredWorkoutShare {
    return {
      id: row.id,
      sender_uid: row.sender_uid,
      recipient_uid: row.recipient_uid,
      snapshot_version: Number(row.snapshot_version),
      snapshot_json: row.snapshot_json,
      snapshot_hash: row.snapshot_hash,
      status: row.status as WorkoutShareStatus,
      client_request_id: row.client_request_id,
      created_at: Number(row.created_at),
      accepted_at: row.accepted_at != null ? Number(row.accepted_at) : null,
      imported_at: row.imported_at != null ? Number(row.imported_at) : null,
      declined_at: row.declined_at != null ? Number(row.declined_at) : null,
      cancelled_at: row.cancelled_at != null ? Number(row.cancelled_at) : null,
      expires_at: Number(row.expires_at),
    };
  }

  async insertShare(share: StoredWorkoutShare, client?: PoolClient): Promise<void> {
    const runner: DbClient = (client ?? this.db) as DbClient;
    await runner.query(
      `INSERT INTO workout_shares
         (id, sender_uid, recipient_uid, snapshot_version, snapshot_json, snapshot_hash,
          status, client_request_id, created_at, accepted_at, imported_at, declined_at,
          cancelled_at, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)`,
      [
        share.id,
        share.sender_uid,
        share.recipient_uid,
        share.snapshot_version,
        share.snapshot_json,
        share.snapshot_hash,
        share.status,
        share.client_request_id,
        share.created_at,
        share.accepted_at,
        share.imported_at,
        share.declined_at,
        share.cancelled_at,
        share.expires_at,
      ],
    );
  }

  async findBySenderAndClientRequestId(
    senderUid: string,
    clientRequestId: string,
  ): Promise<StoredWorkoutShare | undefined> {
    const res = await this.db.query<WorkoutShareRow>(
      `SELECT * FROM workout_shares
       WHERE sender_uid = $1 AND client_request_id = $2`,
      [senderUid, clientRequestId],
    );
    if (res.rows.length === 0) return undefined;
    return this.toDomain(res.rows[0]);
  }

  async findById(shareId: string): Promise<StoredWorkoutShare | undefined> {
    const res = await this.db.query<WorkoutShareRow>(`SELECT * FROM workout_shares WHERE id = $1`, [
      shareId,
    ]);
    if (res.rows.length === 0) return undefined;
    return this.toDomain(res.rows[0]);
  }

  async countPendingBySender(senderUid: string, now: number): Promise<number> {
    const res = await this.db.query<{ count: string | number }>(
      `SELECT COUNT(*) as count FROM workout_shares
       WHERE sender_uid = $1 AND status = 'PENDING' AND expires_at > $2`,
      [senderUid, now],
    );
    return Number(res.rows[0]?.count ?? 0);
  }

  async countCreatedToday(senderUid: string, since: number): Promise<number> {
    const res = await this.db.query<{ count: string | number }>(
      `SELECT COUNT(*) as count FROM workout_shares
       WHERE sender_uid = $1 AND created_at >= $2`,
      [senderUid, since],
    );
    return Number(res.rows[0]?.count ?? 0);
  }

  async isFriendshipActive(uidA: string, uidB: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM friendships
       WHERE (user_a_uid = $1 AND user_b_uid = $2)
          OR (user_a_uid = $3 AND user_b_uid = $4)
       LIMIT 1`,
      [uidA, uidB, uidB, uidA],
    );
    return res.rows.length > 0;
  }

  async findProfileBySocialId(
    socialId: string,
  ): Promise<{ ownerUid: string; displayName: string; socialId: string } | undefined> {
    const res = await this.db.query<{
      ownerUid?: string;
      owneruid?: string;
      displayName?: string;
      displayname?: string;
      socialId?: string;
      socialid?: string;
    }>(
      `SELECT owner_uid AS "ownerUid", display_name AS "displayName", social_id AS "socialId"
       FROM social_profiles
       WHERE social_id = $1 AND status = 'ACTIVE'`,
      [socialId],
    );
    if (res.rows.length === 0) return undefined;
    const row = res.rows[0];
    return {
      ownerUid: (row.ownerUid ?? row.owneruid)!,
      displayName: (row.displayName ?? row.displayname)!,
      socialId: (row.socialId ?? row.socialid)!,
    };
  }

  async findProfileByUid(
    ownerUid: string,
  ): Promise<{ socialId: string; displayName: string } | undefined> {
    const res = await this.db.query<{
      displayName?: string;
      displayname?: string;
      socialId?: string;
      socialid?: string;
    }>(
      `SELECT social_id AS "socialId", display_name AS "displayName"
       FROM social_profiles
       WHERE owner_uid = $1 AND status = 'ACTIVE'`,
      [ownerUid],
    );
    if (res.rows.length === 0) return undefined;
    const row = res.rows[0];
    return {
      socialId: (row.socialId ?? row.socialid)!,
      displayName: (row.displayName ?? row.displayname)!,
    };
  }

  /**
   * Uma transição de estado **condicional** ao estado esperado (T17.13.1 §50/§51).
   */
  async transitionStatus(
    shareId: string,
    fromStatus: WorkoutShareStatus,
    newStatus: WorkoutShareStatus,
    timestampField: 'accepted_at' | 'imported_at' | 'declined_at' | 'cancelled_at',
    timestamp: number,
  ): Promise<boolean> {
    const res = await this.db.query(
      `UPDATE workout_shares
       SET status = $1, ${timestampField} = $2
       WHERE id = $3 AND status = $4`,
      [newStatus, timestamp, shareId, fromStatus],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /**
   * O compartilhamento e o evento de notificação, numa transação só (T17.13.1 §45–§47).
   */
  async insertShareWithNotification(
    share: StoredWorkoutShare,
    enqueueEvent: (client: PoolClient) => Promise<void>,
  ): Promise<void> {
    await this.db.transaction(async (client) => {
      await this.insertShare(share, client);
      await enqueueEvent(client);
    });
  }

  /**
   * Lista recebidos para recipientUid. Auto-expira itens PENDING se now >= expires_at.
   */
  async listReceived(recipientUid: string, now: number): Promise<WorkoutShareItemDto[]> {
    // 1. Auto-expira PENDING passados
    await this.db.query(
      `UPDATE workout_shares
       SET status = 'EXPIRED'
       WHERE recipient_uid = $1 AND status = 'PENDING' AND expires_at <= $2`,
      [recipientUid, now],
    );

    // 2. Busca shares recebidos excluindo usuários com bloqueio bilateral
    const res = await this.db.query<{
      shareId: string;
      status: WorkoutShareStatus;
      createdAt: string | number;
      expiresAt: string | number;
      snapshotJson: string;
      otherSocialId: string;
      otherDisplayName: string;
    }>(
      `SELECT s.id AS "shareId", s.status, s.created_at AS "createdAt", s.expires_at AS "expiresAt",
              s.snapshot_json AS "snapshotJson",
              p.social_id AS "otherSocialId", p.display_name AS "otherDisplayName"
       FROM workout_shares s
       JOIN social_profiles p ON s.sender_uid = p.owner_uid
       WHERE s.recipient_uid = $1
         AND NOT EXISTS (
           SELECT 1 FROM social_blocks b
           WHERE (b.blocker_uid = s.sender_uid AND b.blocked_uid = s.recipient_uid)
              OR (b.blocker_uid = s.recipient_uid AND b.blocked_uid = s.sender_uid)
         )
       ORDER BY s.created_at DESC`,
      [recipientUid],
    );

    return res.rows.map((r) => {
      const snap = JSON.parse(r.snapshotJson) as WorkoutTemplateShareSnapshotV1;
      return {
        shareId: r.shareId,
        status: r.status,
        createdAt: Number(r.createdAt),
        expiresAt: Number(r.expiresAt),
        templateName: snap.name,
        exerciseCount: snap.exercises?.length ?? 0,
        otherUser: {
          socialId: r.otherSocialId,
          displayName: r.otherDisplayName,
        },
      };
    });
  }

  /**
   * Lista enviados para senderUid. Auto-expira itens PENDING se now >= expires_at.
   */
  async listSent(senderUid: string, now: number): Promise<WorkoutShareItemDto[]> {
    await this.db.query(
      `UPDATE workout_shares
       SET status = 'EXPIRED'
       WHERE sender_uid = $1 AND status = 'PENDING' AND expires_at <= $2`,
      [senderUid, now],
    );

    const res = await this.db.query<{
      shareId: string;
      status: WorkoutShareStatus;
      createdAt: string | number;
      expiresAt: string | number;
      snapshotJson: string;
      otherSocialId: string;
      otherDisplayName: string;
    }>(
      `SELECT s.id AS "shareId", s.status, s.created_at AS "createdAt", s.expires_at AS "expiresAt",
              s.snapshot_json AS "snapshotJson",
              p.social_id AS "otherSocialId", p.display_name AS "otherDisplayName"
       FROM workout_shares s
       JOIN social_profiles p ON s.recipient_uid = p.owner_uid
       WHERE s.sender_uid = $1
         AND NOT EXISTS (
           SELECT 1 FROM social_blocks b
           WHERE (b.blocker_uid = s.sender_uid AND b.blocked_uid = s.recipient_uid)
              OR (b.blocker_uid = s.recipient_uid AND b.blocked_uid = s.sender_uid)
         )
       ORDER BY s.created_at DESC`,
      [senderUid],
    );

    return res.rows.map((r) => {
      const snap = JSON.parse(r.snapshotJson) as WorkoutTemplateShareSnapshotV1;
      return {
        shareId: r.shareId,
        status: r.status,
        createdAt: Number(r.createdAt),
        expiresAt: Number(r.expiresAt),
        templateName: snap.name,
        exerciseCount: snap.exercises?.length ?? 0,
        otherUser: {
          socialId: r.otherSocialId,
          displayName: r.otherDisplayName,
        },
      };
    });
  }
}
