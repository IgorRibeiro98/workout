import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
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
 * A fonte canônica de dados de treino sincronizados para o domínio social (T17.4 §2/§88–§92).
 *
 * Centraliza o acesso seguro a sessões canônicas COMPLETED em sync_entities.
 * Garante consultas em lote (batch) para eliminar N+1 e assegura que dados íntimos de
 * treino (exercícios, séries, cargas, notas) nunca saiam do adapter.
 */
export interface CanonicalTrainingSource {
  hasAnyCompletedSession(ownerUid: string): boolean;

  countCompletedWorkouts(ownerUid: string, startMs: number, endMsExclusive: number): number;

  countActiveDays(ownerUid: string, startDate: string, endDate: string, timeZoneId: string): number;

  /**
   * Contagem de treinos concluídos em lote para múltiplos donos em uma janela [fromMs, untilMsExclusive).
   * Elimina consultas N+1 para o ranking contextual entre amigos (§88/§89).
   */
  getCompletedWorkoutCounts(
    ownerUids: readonly string[],
    fromMs: number,
    untilMsExclusive: number,
  ): Map<string, number>;

  /**
   * Resumo de sessões concluídas em lote para múltiplos donos em uma janela [fromMs, untilMs].
   * Utilizado para projetar os dias sociais recentes de múltiplos amigos em lote (§88/§90).
   */
  getCompletedWorkoutSummaries(
    ownerUids: readonly string[],
    fromMs: number,
    untilMs: number,
  ): readonly CompletedWorkoutSummary[];
}

export const CANONICAL_TRAINING_SOURCE = Symbol('CANONICAL_TRAINING_SOURCE');

@Injectable()
export class SyncedCanonicalTrainingSource implements CanonicalTrainingSource {
  constructor(private readonly sqlite: SqliteService) {}

  hasAnyCompletedSession(ownerUid: string): boolean {
    const row = this.sqlite.connection
      .prepare(
        `SELECT 1 AS present
           FROM sync_entities
          WHERE owner_uid = ?
            AND entity_type = 'WORKOUT_SESSION'
            AND deleted = 0
            AND json_extract(payload, '$.status') = 'COMPLETED'
          LIMIT 1`,
      )
      .get(ownerUid) as { present: number } | undefined;

    return row !== undefined;
  }

  countCompletedWorkouts(ownerUid: string, startMs: number, endMsExclusive: number): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS total
           FROM sync_entities
          WHERE owner_uid = ?
            AND entity_type = 'WORKOUT_SESSION'
            AND deleted = 0
            AND json_extract(payload, '$.status') = 'COMPLETED'
            AND json_extract(payload, '$.startedAt') >= ?
            AND json_extract(payload, '$.startedAt') < ?`,
      )
      .get(ownerUid, startMs, endMsExclusive) as { total: number } | undefined;

    return row?.total ?? 0;
  }

  countActiveDays(
    ownerUid: string,
    startDate: string,
    endDate: string,
    timeZoneId: string,
  ): number {
    const days = challengeDayWindows(startDate, endDate, timeZoneId);
    if (days.length === 0) {
      return 0;
    }

    const values = days.map(() => '(?, ?)').join(', ');
    const parameters: number[] = [];
    for (const day of days) {
      parameters.push(day.startMs, day.endMs);
    }

    const row = this.sqlite.connection
      .prepare(
        `WITH challenge_days(day_start, day_end) AS (VALUES ${values})
         SELECT COUNT(*) AS total
           FROM challenge_days d
          WHERE EXISTS (
                SELECT 1
                  FROM sync_entities
                 WHERE owner_uid = ?
                   AND entity_type = 'WORKOUT_SESSION'
                   AND deleted = 0
                   AND json_extract(payload, '$.status') = 'COMPLETED'
                   AND json_extract(payload, '$.startedAt') >= d.day_start
                   AND json_extract(payload, '$.startedAt') < d.day_end
                )`,
      )
      .get(...parameters, ownerUid) as { total: number } | undefined;

    return row?.total ?? 0;
  }

  getCompletedWorkoutCounts(
    ownerUids: readonly string[],
    fromMs: number,
    untilMsExclusive: number,
  ): Map<string, number> {
    const result = new Map<string, number>();
    for (const uid of ownerUids) {
      result.set(uid, 0);
    }

    if (ownerUids.length === 0) {
      return result;
    }

    const placeholders = ownerUids.map(() => '?').join(', ');
    const rows = this.sqlite.connection
      .prepare(
        `SELECT owner_uid, COUNT(*) AS total
           FROM sync_entities
          WHERE owner_uid IN (${placeholders})
            AND entity_type = 'WORKOUT_SESSION'
            AND deleted = 0
            AND json_extract(payload, '$.status') = 'COMPLETED'
            AND json_extract(payload, '$.startedAt') >= ?
            AND json_extract(payload, '$.startedAt') < ?
          GROUP BY owner_uid`,
      )
      .all(...ownerUids, fromMs, untilMsExclusive) as Array<{ owner_uid: string; total: number }>;

    for (const row of rows) {
      result.set(row.owner_uid, row.total);
    }

    return result;
  }

  getCompletedWorkoutSummaries(
    ownerUids: readonly string[],
    fromMs: number,
    untilMs: number,
  ): readonly CompletedWorkoutSummary[] {
    if (ownerUids.length === 0) {
      return [];
    }

    const placeholders = ownerUids.map(() => '?').join(', ');
    const rows = this.sqlite.connection
      .prepare(
        `SELECT owner_uid,
                entity_sync_id,
                CAST(json_extract(payload, '$.startedAt') AS INTEGER) AS started_at
           FROM sync_entities
          WHERE owner_uid IN (${placeholders})
            AND entity_type = 'WORKOUT_SESSION'
            AND deleted = 0
            AND json_extract(payload, '$.status') = 'COMPLETED'
            AND json_extract(payload, '$.startedAt') >= ?
            AND json_extract(payload, '$.startedAt') <= ?
          ORDER BY started_at ASC`,
      )
      .all(...ownerUids, fromMs, untilMs) as Array<{
      owner_uid: string;
      entity_sync_id: string;
      started_at: number;
    }>;

    return rows.map((row) => ({
      ownerUid: row.owner_uid,
      sessionSyncId: row.entity_sync_id,
      startedAt: row.started_at,
    }));
  }
}
