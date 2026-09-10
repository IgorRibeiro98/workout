import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import { VIEWER_SCOPE_CTE } from './workout-checkin.access-policy';

/**
 * Um check-in como ele mora no banco (T17.8 §35).
 */
export interface StoredWorkoutCheckIn {
  readonly id: string;
  readonly authorUid: string;
  readonly sourceSessionSyncId: string;
  readonly clientRequestId: string;
  readonly status: 'PUBLISHED' | 'DELETED';
  /** A legenda, quando existe (T17.9 §7). `null` em toda publicação da T17.8 (§6/§60). */
  readonly caption: string | null;
  readonly createdAt: number;
  readonly deletedAt: number | null;
}

/** Uma linha do feed, já com a identidade pública do autor resolvida pelo `JOIN` (§79). */
export interface FeedRow {
  readonly checkInId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly caption: string | null;
  readonly publishedAt: number;
}

const SELECT_COLUMNS = `id,
       author_uid,
       source_session_sync_id,
       client_request_id,
       status,
       caption,
       created_at,
       deleted_at`;

interface CheckInRow {
  readonly id: string;
  readonly author_uid: string;
  readonly source_session_sync_id: string;
  readonly client_request_id: string;
  readonly status: 'PUBLISHED' | 'DELETED';
  readonly caption: string | null;
  readonly created_at: string | number;
  readonly deleted_at: string | number | null;
}

function toDomain(row: CheckInRow): StoredWorkoutCheckIn {
  return {
    id: row.id,
    authorUid: row.author_uid,
    sourceSessionSyncId: row.source_session_sync_id,
    clientRequestId: row.client_request_id,
    status: row.status,
    caption: row.caption,
    createdAt: Number(row.created_at),
    deletedAt: row.deleted_at != null ? Number(row.deleted_at) : null,
  };
}

@Injectable()
export class WorkoutCheckInRepository {
  constructor(private readonly db: PostgresService) {}

  private getRunner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  /**
   * Insere o check-in.
   */
  async create(item: StoredWorkoutCheckIn, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_workout_checkins (
         id, author_uid, source_session_sync_id, client_request_id, status, caption,
         created_at, deleted_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [
        item.id,
        item.authorUid,
        item.sourceSessionSyncId,
        item.clientRequestId,
        item.status,
        item.caption,
        item.createdAt,
        item.deletedAt,
      ],
    );
  }

  /** A garantia de §29: um treino, no máximo um check-in — vivo ou já excluído. */
  async findByAuthorAndSession(
    authorUid: string,
    sessionSyncId: string,
    client?: PoolClient,
  ): Promise<StoredWorkoutCheckIn | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<CheckInRow>(
      `SELECT ${SELECT_COLUMNS}
         FROM social_workout_checkins
        WHERE author_uid = $1 AND source_session_sync_id = $2
        LIMIT 1`,
      [authorUid, sessionSyncId],
    );
    const row = res.rows[0];
    return row ? toDomain(row) : null;
  }

  /** A garantia de §31/§32: a mesma intenção do usuário produz o mesmo check-in. */
  async findByAuthorAndClientRequest(
    authorUid: string,
    clientRequestId: string,
    client?: PoolClient,
  ): Promise<StoredWorkoutCheckIn | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<CheckInRow>(
      `SELECT ${SELECT_COLUMNS}
         FROM social_workout_checkins
        WHERE author_uid = $1 AND client_request_id = $2
        LIMIT 1`,
      [authorUid, clientRequestId],
    );
    const row = res.rows[0];
    return row ? toDomain(row) : null;
  }

  async findById(checkInId: string, client?: PoolClient): Promise<StoredWorkoutCheckIn | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<CheckInRow>(
      `SELECT ${SELECT_COLUMNS} FROM social_workout_checkins WHERE id = $1 LIMIT 1`,
      [checkInId],
    );
    const row = res.rows[0];
    return row ? toDomain(row) : null;
  }

  /**
   * Exclusão pelo autor (§64/§66).
   */
  async softDelete(
    checkInId: string,
    authorUid: string,
    now: number,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE social_workout_checkins
          SET status = 'DELETED', deleted_at = $1
        WHERE id = $2 AND author_uid = $3 AND status = 'PUBLISHED'`,
      [now, checkInId, authorUid],
    );
    return (res.rowCount ?? 0) > 0;
  }

  /**
   * Remove a linha de verdade — o **único** `DELETE` deste agregado (T17.9 §43).
   */
  async hardDelete(checkInId: string, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(`DELETE FROM social_workout_checkins WHERE id = $1`, [checkInId]);
  }

  /**
   * Uma transação do agregado.
   */
  async transaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
    return this.db.transaction(work);
  }

  /**
   * O feed do `viewerUid` (§54–§60, §70–§79).
   */
  async findFeed(
    viewerUid: string,
    publishedSinceMs: number,
    limit: number,
    client?: PoolClient,
  ): Promise<FeedRow[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{
      checkInId: string;
      authorUid: string;
      authorSocialId: string;
      authorDisplayName: string;
      caption: string | null;
      publishedAt: string | number;
    }>(
      `WITH ${VIEWER_SCOPE_CTE}
       SELECT c.id            AS "checkInId",
              c.author_uid    AS "authorUid",
              p.social_id     AS "authorSocialId",
              p.display_name  AS "authorDisplayName",
              c.caption       AS caption,
              c.created_at    AS "publishedAt"
         FROM social_workout_checkins c
         JOIN eligible_authors ea ON ea.uid = c.author_uid
         JOIN social_profiles p   ON p.owner_uid = c.author_uid
        WHERE c.status = 'PUBLISHED'
          AND c.created_at >= $2
          AND p.status = 'ACTIVE'
        ORDER BY c.created_at DESC, c.id DESC
        LIMIT $3`,
      [viewerUid, publishedSinceMs, limit],
    );

    return res.rows.map((row) => ({
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      caption: row.caption,
      publishedAt: Number(row.publishedAt),
    }));
  }
}
