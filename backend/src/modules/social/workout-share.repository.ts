import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
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

@Injectable()
export class WorkoutShareRepository {
  constructor(private readonly sqlite: SqliteService) {}

  private get db() {
    return this.sqlite.connection;
  }

  insertShare(share: StoredWorkoutShare): void {
    this.db
      .prepare(
        `INSERT INTO workout_shares
           (id, sender_uid, recipient_uid, snapshot_version, snapshot_json, snapshot_hash,
            status, client_request_id, created_at, accepted_at, imported_at, declined_at,
            cancelled_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
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
      );
  }

  findBySenderAndClientRequestId(
    senderUid: string,
    clientRequestId: string,
  ): StoredWorkoutShare | undefined {
    return this.db
      .prepare(
        `SELECT * FROM workout_shares
         WHERE sender_uid = ? AND client_request_id = ?`,
      )
      .get(senderUid, clientRequestId) as StoredWorkoutShare | undefined;
  }

  findById(shareId: string): StoredWorkoutShare | undefined {
    return this.db.prepare(`SELECT * FROM workout_shares WHERE id = ?`).get(shareId) as
      | StoredWorkoutShare
      | undefined;
  }

  countPendingBySender(senderUid: string, now: number): number {
    const row = this.db
      .prepare(
        `SELECT COUNT(*) as count FROM workout_shares
         WHERE sender_uid = ? AND status = 'PENDING' AND expires_at > ?`,
      )
      .get(senderUid, now) as { count: number };
    return row.count;
  }

  countCreatedToday(senderUid: string, since: number): number {
    const row = this.db
      .prepare(
        `SELECT COUNT(*) as count FROM workout_shares
         WHERE sender_uid = ? AND created_at >= ?`,
      )
      .get(senderUid, since) as { count: number };
    return row.count;
  }

  isFriendshipActive(uidA: string, uidB: string): boolean {
    const row = this.db
      .prepare(
        `SELECT 1 FROM friendships
         WHERE (user_a_uid = ? AND user_b_uid = ?)
            OR (user_a_uid = ? AND user_b_uid = ?)
         LIMIT 1`,
      )
      .get(uidA, uidB, uidB, uidA);
    return row !== undefined;
  }

  findProfileBySocialId(
    socialId: string,
  ): { ownerUid: string; displayName: string; socialId: string } | undefined {
    const row = this.db
      .prepare(
        `SELECT owner_uid AS ownerUid, display_name AS displayName, social_id AS socialId
         FROM social_profiles
         WHERE social_id = ? AND status = 'ACTIVE'`,
      )
      .get(socialId) as { ownerUid: string; displayName: string; socialId: string } | undefined;
    return row;
  }

  findProfileByUid(ownerUid: string): { socialId: string; displayName: string } | undefined {
    const row = this.db
      .prepare(
        `SELECT social_id AS socialId, display_name AS displayName
         FROM social_profiles
         WHERE owner_uid = ? AND status = 'ACTIVE'`,
      )
      .get(ownerUid) as { socialId: string; displayName: string } | undefined;
    return row;
  }

  updateStatus(
    shareId: string,
    newStatus: WorkoutShareStatus,
    timestampField: 'accepted_at' | 'imported_at' | 'declined_at' | 'cancelled_at',
    timestamp: number,
  ): void {
    this.db
      .prepare(
        `UPDATE workout_shares
         SET status = ?, ${timestampField} = ?
         WHERE id = ?`,
      )
      .run(newStatus, timestamp, shareId);
  }

  /**
   * Lista recebidos para recipientUid. Auto-expira itens PENDING se now >= expires_at.
   */
  listReceived(recipientUid: string, now: number): WorkoutShareItemDto[] {
    // 1. Auto-expira PENDING passados
    this.db
      .prepare(
        `UPDATE workout_shares
         SET status = 'EXPIRED'
         WHERE recipient_uid = ? AND status = 'PENDING' AND expires_at <= ?`,
      )
      .run(recipientUid, now);

    // 2. Busca shares recebidos excluindo usuários com bloqueio bilateral
    const rows = this.db
      .prepare(
        `SELECT s.id AS shareId, s.status, s.created_at AS createdAt, s.expires_at AS expiresAt,
                s.snapshot_json AS snapshotJson,
                p.social_id AS otherSocialId, p.display_name AS otherDisplayName
         FROM workout_shares s
         JOIN social_profiles p ON s.sender_uid = p.owner_uid
         WHERE s.recipient_uid = ?
           AND NOT EXISTS (
             SELECT 1 FROM social_blocks b
             WHERE (b.blocker_uid = s.sender_uid AND b.blocked_uid = s.recipient_uid)
                OR (b.blocker_uid = s.recipient_uid AND b.blocked_uid = s.sender_uid)
           )
         ORDER BY s.created_at DESC`,
      )
      .all(recipientUid) as Array<{
      shareId: string;
      status: WorkoutShareStatus;
      createdAt: number;
      expiresAt: number;
      snapshotJson: string;
      otherSocialId: string;
      otherDisplayName: string;
    }>;

    return rows.map((r) => {
      const snap = JSON.parse(r.snapshotJson) as WorkoutTemplateShareSnapshotV1;
      return {
        shareId: r.shareId,
        status: r.status,
        createdAt: r.createdAt,
        expiresAt: r.expiresAt,
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
  listSent(senderUid: string, now: number): WorkoutShareItemDto[] {
    this.db
      .prepare(
        `UPDATE workout_shares
         SET status = 'EXPIRED'
         WHERE sender_uid = ? AND status = 'PENDING' AND expires_at <= ?`,
      )
      .run(senderUid, now);

    const rows = this.db
      .prepare(
        `SELECT s.id AS shareId, s.status, s.created_at AS createdAt, s.expires_at AS expiresAt,
                s.snapshot_json AS snapshotJson,
                p.social_id AS otherSocialId, p.display_name AS otherDisplayName
         FROM workout_shares s
         JOIN social_profiles p ON s.recipient_uid = p.owner_uid
         WHERE s.sender_uid = ?
           AND NOT EXISTS (
             SELECT 1 FROM social_blocks b
             WHERE (b.blocker_uid = s.sender_uid AND b.blocked_uid = s.recipient_uid)
                OR (b.blocker_uid = s.recipient_uid AND b.blocked_uid = s.sender_uid)
           )
         ORDER BY s.created_at DESC`,
      )
      .all(senderUid) as Array<{
      shareId: string;
      status: WorkoutShareStatus;
      createdAt: number;
      expiresAt: number;
      snapshotJson: string;
      otherSocialId: string;
      otherDisplayName: string;
    }>;

    return rows.map((r) => {
      const snap = JSON.parse(r.snapshotJson) as WorkoutTemplateShareSnapshotV1;
      return {
        shareId: r.shareId,
        status: r.status,
        createdAt: r.createdAt,
        expiresAt: r.expiresAt,
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
