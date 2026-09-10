import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import { challengeDayWindows } from './challenge-progress.source';

/**
 * Resumo seguro e mínimo de uma sessão concluída (T17.4 §91/§92).
 *
 * Apenas identificador canônico da sessão e startedAt.
 * Nenhum payload bruto, carga, exercício, repetição, nota ou medida corporal.
 */
export interface CompletedWorkoutSummary {
  readonly ownerUid: string;
  readonly sessionSyncId: string;
  readonly startedAt: number;
}

/**
 * O que o domínio social pode saber de uma sessão para decidir um check-in (T17.8 §16/§17/§25).
 */
export interface CheckInSessionDetail {
  readonly ownerUid: string;
  readonly sessionSyncId: string;
  /** Tombstone do sync (T16.7). Uma sessão apagada não vira check-in (§17). */
  readonly deleted: boolean;
  readonly status: string | null;
  readonly finishedAt: number | null;
}

/**
 * A fonte canônica de dados de treino sincronizados para o domínio social (T17.4 §2/§88–§92).
 */
export interface CanonicalTrainingSource {
  hasAnyCompletedSession(ownerUid: string): Promise<boolean>;

  countCompletedWorkouts(ownerUid: string, startMs: number, endMsExclusive: number): Promise<number>;

  countActiveDays(ownerUid: string, startDate: string, endDate: string, timeZoneId: string): Promise<number>;

  /**
   * Contagem de treinos concluídos em lote para múltiplos donos em uma janela [fromMs, untilMsExclusive).
   */
  getCompletedWorkoutCounts(
    ownerUids: readonly string[],
    fromMs: number,
    untilMsExclusive: number,
  ): Promise<Map<string, number>>;

  /**
   * Resumo de sessões concluídas em lote para múltiplos donos em uma janela [fromMs, untilMs].
   */
  getCompletedWorkoutSummaries(
    ownerUids: readonly string[],
    fromMs: number,
    untilMs: number,
  ): Promise<readonly CompletedWorkoutSummary[]>;

  /**
   * A sessão canônica deste dono, para decidir um check-in (T17.8 §15/§16).
   */
  findSessionForCheckIn(ownerUid: string, sessionSyncId: string): Promise<CheckInSessionDetail | null>;
}

export const CANONICAL_TRAINING_SOURCE = Symbol('CANONICAL_TRAINING_SOURCE');

@Injectable()
export class SyncedCanonicalTrainingSource implements CanonicalTrainingSource {
  constructor(private readonly db: PostgresService) {}

  async hasAnyCompletedSession(ownerUid: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 AS present
         FROM sync_entities
        WHERE owner_uid = $1
          AND entity_type = 'WORKOUT_SESSION'
          AND deleted = FALSE
          AND (NULLIF(payload, '')::jsonb->>'status') = 'COMPLETED'
        LIMIT 1`,
      [ownerUid],
    );
    return res.rows.length > 0;
  }

  async countCompletedWorkouts(ownerUid: string, startMs: number, endMsExclusive: number): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total
         FROM sync_entities
        WHERE owner_uid = $1
          AND entity_type = 'WORKOUT_SESSION'
          AND deleted = FALSE
          AND (NULLIF(payload, '')::jsonb->>'status') = 'COMPLETED'
          AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) >= $2
          AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) < $3`,
      [ownerUid, startMs, endMsExclusive],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  async countActiveDays(
    ownerUid: string,
    startDate: string,
    endDate: string,
    timeZoneId: string,
  ): Promise<number> {
    const days = challengeDayWindows(startDate, endDate, timeZoneId);
    if (days.length === 0) {
      return 0;
    }

    const dayStarts = days.map((d) => d.startMs);
    const dayEnds = days.map((d) => d.endMs);

    const res = await this.db.query<{ total: string | number }>(
      `WITH challenge_days AS (
         SELECT UNNEST($1::bigint[]) AS day_start, UNNEST($2::bigint[]) AS day_end
       )
       SELECT COUNT(*) AS total
         FROM challenge_days d
        WHERE EXISTS (
              SELECT 1
                FROM sync_entities
               WHERE owner_uid = $3
                 AND entity_type = 'WORKOUT_SESSION'
                 AND deleted = FALSE
                 AND (NULLIF(payload, '')::jsonb->>'status') = 'COMPLETED'
                 AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) >= d.day_start
                 AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) < d.day_end
              )`,
      [dayStarts, dayEnds, ownerUid],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  async getCompletedWorkoutCounts(
    ownerUids: readonly string[],
    fromMs: number,
    untilMsExclusive: number,
  ): Promise<Map<string, number>> {
    const result = new Map<string, number>();
    for (const uid of ownerUids) {
      result.set(uid, 0);
    }

    if (ownerUids.length === 0) {
      return result;
    }

    const res = await this.db.query<{ owner_uid: string; total: string | number }>(
      `SELECT owner_uid, COUNT(*) AS total
         FROM sync_entities
        WHERE owner_uid = ANY($1::text[])
          AND entity_type = 'WORKOUT_SESSION'
          AND deleted = FALSE
          AND (NULLIF(payload, '')::jsonb->>'status') = 'COMPLETED'
          AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) >= $2
          AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) < $3
        GROUP BY owner_uid`,
      [ownerUids as string[], fromMs, untilMsExclusive],
    );

    for (const row of res.rows) {
      result.set(row.owner_uid, Number(row.total));
    }

    return result;
  }

  async getCompletedWorkoutSummaries(
    ownerUids: readonly string[],
    fromMs: number,
    untilMs: number,
  ): Promise<readonly CompletedWorkoutSummary[]> {
    if (ownerUids.length === 0) {
      return [];
    }

    const res = await this.db.query<{
      owner_uid: string;
      entity_sync_id: string;
      started_at: string | number;
    }>(
      `SELECT owner_uid,
              entity_sync_id,
              CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) AS started_at
         FROM sync_entities
        WHERE owner_uid = ANY($1::text[])
          AND entity_type = 'WORKOUT_SESSION'
          AND deleted = FALSE
          AND (NULLIF(payload, '')::jsonb->>'status') = 'COMPLETED'
          AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) >= $2
          AND CAST(NULLIF(payload, '')::jsonb->>'startedAt' AS BIGINT) <= $3
        ORDER BY started_at ASC`,
      [ownerUids as string[], fromMs, untilMs],
    );

    return res.rows.map((row) => ({
      ownerUid: row.owner_uid,
      sessionSyncId: row.entity_sync_id,
      startedAt: Number(row.started_at),
    }));
  }

  async findSessionForCheckIn(ownerUid: string, sessionSyncId: string): Promise<CheckInSessionDetail | null> {
    const res = await this.db.query<{
      owner_uid: string;
      entity_sync_id: string;
      deleted: boolean;
      status: string | null;
      finished_at: string | number | null;
    }>(
      `SELECT owner_uid,
              entity_sync_id,
              deleted,
              CASE WHEN deleted = FALSE
                   THEN (NULLIF(payload, '')::jsonb->>'status')
              END AS status,
              CASE WHEN deleted = FALSE
                   THEN CAST(COALESCE(
                          NULLIF(payload, '')::jsonb->>'finishedAt',
                          NULLIF(payload, '')::jsonb->>'startedAt'
                        ) AS BIGINT)
              END AS finished_at
         FROM sync_entities
        WHERE owner_uid = $1
          AND entity_sync_id = $2
          AND entity_type = 'WORKOUT_SESSION'
        LIMIT 1`,
      [ownerUid, sessionSyncId],
    );

    const row = res.rows[0];
    if (!row) {
      return null;
    }

    return {
      ownerUid: row.owner_uid,
      sessionSyncId: row.entity_sync_id,
      deleted: Boolean(row.deleted),
      status: row.status,
      finishedAt: row.finished_at !== null ? Number(row.finished_at) : null,
    };
  }
}
