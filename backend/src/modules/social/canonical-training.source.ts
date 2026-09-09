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
 * O que o domínio social pode saber de uma sessão para decidir um check-in (T17.8 §16/§17/§25).
 *
 * Cinco campos, e nenhum deles é conteúdo de treino: nenhum payload bruto, exercício, série,
 * carga, repetição, nota, duração, medida corporal ou nome de template atravessa esta fronteira.
 * `SocialModule` não recebe `sync_entities` — ele recebe **respostas** (§16).
 *
 * `finishedAt` aqui é o **instante canônico de fim do treino**, e não um significado novo (§25):
 * é `finishedAt` do agregado quando ele existe — o único escritor no Android o grava no mesmo
 * `copy()` que marca `COMPLETED` — e `startedAt` quando não existe, que é o instante canônico já
 * usado pela semana da T17.2 e pelo dia da T17.3. Como `startedAt <= finishedAt`, o fallback só
 * pode fazer a sessão parecer **mais velha**: ele nunca alarga a janela de elegibilidade.
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

  /**
   * A sessão canônica deste dono, para decidir um check-in (T17.8 §15/§16).
   *
   * Uma operação estreita, e não um quarto parser: quem define "o que é uma sessão de treino no
   * estado sincronizado" continua sendo **este** adapter, o mesmo que responde perfil (T17.2),
   * desafio (T17.3) e atividade (T17.4). Um `SELECT` de `sync_entities` dentro do serviço de
   * check-in seria a segunda definição da mesma regra, e a divergência apareceria como um
   * check-in publicado a partir de algo que a tela de consistência não conta como treino.
   *
   * `null` quando não há linha desta sessão **para este dono**. O chamador não consegue
   * distinguir "não existe", "é de outra conta" e "ainda não sincronizou" — e não deve (§116).
   */
  findSessionForCheckIn(ownerUid: string, sessionSyncId: string): CheckInSessionDetail | null;
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

  findSessionForCheckIn(ownerUid: string, sessionSyncId: string): CheckInSessionDetail | null {
    // `json_extract` só sobre escalares — `status` e os dois instantes. `payload` **não** é
    // selecionado: materializá-lo em JavaScript abriria para o domínio social o exercício, a
    // carga, a repetição, a nota e o horário do treino, que é exatamente o que este adapter
    // existe para manter fora (§16).
    //
    // O filtro é `owner_uid = ?` e não um `WHERE entity_sync_id = ?` seguido de conferência em
    // memória: uma sessão de outra conta precisa ser **indistinguível** de inexistente já na
    // consulta, e não depois de o serviço ter tido a linha na mão (§116).
    //
    // O `CASE WHEN deleted = 0 AND json_valid(payload)` **não** é defensividade decorativa: o
    // tombstone da T16.7 é uma linha com `deleted = 1` e `payload` vazio — a coluna é `NOT NULL`
    // numa tabela `STRICT`, então "sem conteúdo" é `''`, e não `NULL`. `json_extract('')` **lança**
    // `malformed JSON` no SQLite, o que viraria um `500` para quem tentasse publicar um check-in de
    // uma sessão apagada. O `CASE` é o que garante que a extração só aconteça sobre uma linha viva,
    // e `deleted` continua sendo respondido para o chamador decidir.
    const row = this.sqlite.connection
      .prepare(
        `SELECT owner_uid,
                entity_sync_id,
                deleted,
                CASE WHEN deleted = 0 AND json_valid(payload)
                     THEN json_extract(payload, '$.status')
                END AS status,
                CASE WHEN deleted = 0 AND json_valid(payload)
                     THEN CAST(COALESCE(json_extract(payload, '$.finishedAt'),
                                        json_extract(payload, '$.startedAt')) AS INTEGER)
                END AS finished_at
           FROM sync_entities
          WHERE owner_uid = ?
            AND entity_sync_id = ?
            AND entity_type = 'WORKOUT_SESSION'
          LIMIT 1`,
      )
      .get(ownerUid, sessionSyncId) as
      | {
          owner_uid: string;
          entity_sync_id: string;
          deleted: number;
          status: string | null;
          finished_at: number | null;
        }
      | undefined;

    if (!row) {
      return null;
    }

    return {
      ownerUid: row.owner_uid,
      sessionSyncId: row.entity_sync_id,
      deleted: row.deleted === 1,
      status: row.status,
      finishedAt: row.finished_at,
    };
  }
}
