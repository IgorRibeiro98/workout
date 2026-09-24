import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import type { ConsistencyParameters } from './social-consistency';

/**
 * Os interruptores de compartilhamento, e a coluna de cada um (T17.2, T19.H3).
 *
 * **Uma tabela só** para os quinze: a leitura, o `INSERT` de defaults, o `PATCH` e o DTO do dono
 * percorrem esta lista. Até a T19.2 eram quatro blocos escritos à mão em cada lugar; com onze
 * novos, um esquecido em um deles seria um interruptor que salva e não lê, ou lê e não salva.
 *
 * Todos nascem `false` (0008): ligar é sempre decisão do dono.
 */
export const PROGRESS_SHARING_FLAGS = [
  // Progresso geral (T17.2/T19.2)
  ['shareLevel', 'share_level'],
  ['shareConsistencyStreak', 'share_consistency_streak'],
  ['shareWeeklyWorkoutCount', 'share_weekly_workout_count'],
  ['shareHighlightedAchievements', 'share_highlighted_achievements'],
  // Estatísticas de treino (T19.H3) — agregado da semana canônica / total
  ['shareWeeklyTrainingMinutes', 'share_weekly_training_minutes'],
  ['shareWeeklyCompletedSets', 'share_weekly_completed_sets'],
  ['shareWeeklyVolume', 'share_weekly_volume'],
  ['shareTotalWorkouts', 'share_total_workouts'],
  // Detalhes dos check-ins (T19.H3) — por publicação, lidos da sessão canônica
  ['shareWorkoutName', 'share_workout_name'],
  ['shareWorkoutTime', 'share_workout_time'],
  ['shareWorkoutDuration', 'share_workout_duration'],
  ['shareWorkoutExercises', 'share_workout_exercises'],
  ['shareWorkoutSets', 'share_workout_sets'],
  ['shareWorkoutWeights', 'share_workout_weights'],
  ['shareWorkoutVolume', 'share_workout_volume'],
] as const;

export type ProgressSharingFlag = (typeof PROGRESS_SHARING_FLAGS)[number][0];

/** Os quinze interruptores, como estão gravados. */
export type ProgressSharingFlags = { readonly [K in ProgressSharingFlag]: boolean };

/**
 * As preferências de compartilhamento de progresso, como estão gravadas.
 *
 * Desde a T19.2A elas carregam também os **parâmetros de consistência** que o dono declarou
 * ([consistency]) — configuração, não progresso: são o que o aparelho lê de `weekly_goal_history`
 * e do DataStore para calcular a própria sequência, e o que o servidor precisa para derivar a mesma
 * sequência das sessões sincronizadas. `null` enquanto o app não os enviar.
 */
export interface StoredProgressSettings extends ProgressSharingFlags {
  readonly weekTimeZone: string | null;
  readonly consistency: ConsistencyParameters | null;
  readonly updatedAt: number;
}

export type UpdateProgressSettingsInput = {
  readonly [K in ProgressSharingFlag]?: boolean;
} & {
  readonly weekTimeZone?: string;
  /** Substitui os parâmetros inteiros: o aparelho é a autoridade sobre a própria configuração. */
  readonly consistency?: ConsistencyParameters;
  readonly now: number;
};

const ALL_FLAGS_OFF = Object.fromEntries(
  PROGRESS_SHARING_FLAGS.map(([flag]) => [flag, false]),
) as ProgressSharingFlags;

export const SOCIAL_PROGRESS_SHARING_DEFAULTS = {
  ...ALL_FLAGS_OFF,
  weekTimeZone: null,
  consistency: null,
} as const;

const FLAG_COLUMNS = PROGRESS_SHARING_FLAGS.map(([, column]) => column);

@Injectable()
export class SocialProgressSettingsRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * As preferências daquela conta.
   */
  async find(ownerUid: string): Promise<StoredProgressSettings> {
    const res = await this.db.query<SettingsRow>(
      `SELECT ${FLAG_COLUMNS.join(', ')}, week_time_zone, tracking_started_at_epoch_day,
              updated_at
         FROM social_progress_settings
        WHERE owner_uid = $1`,
      [ownerUid],
    );

    const row = res.rows[0];
    if (!row) {
      return { ...SOCIAL_PROGRESS_SHARING_DEFAULTS, updatedAt: 0 };
    }

    let consistency: ConsistencyParameters | null = null;
    if (
      row.tracking_started_at_epoch_day !== null &&
      row.tracking_started_at_epoch_day !== undefined
    ) {
      const goals = await this.db.query<GoalRow>(
        `SELECT week_start_epoch_day, goal
           FROM social_progress_weekly_goals
          WHERE owner_uid = $1
          ORDER BY week_start_epoch_day ASC`,
        [ownerUid],
      );
      consistency = {
        trackingStartedAtEpochDay: Number(row.tracking_started_at_epoch_day),
        weeklyGoals: goals.rows.map((goal) => ({
          weekStartEpochDay: Number(goal.week_start_epoch_day),
          goal: Number(goal.goal),
        })),
      };
    }

    return {
      ...flagsOf(row),
      weekTimeZone: row.week_time_zone,
      consistency,
      updatedAt: Number(row.updated_at),
    };
  }

  /**
   * Os interruptores de várias contas de uma vez (T19.H3 §38).
   *
   * O Feed precisa das escolhas **de cada autor** para montar o resumo de treino de cada
   * publicação — uma consulta para o lote, e não uma por item. Uma conta sem linha (anterior à
   * T17.2) não aparece no mapa, e quem lê trata ausência como "tudo desligado".
   */
  async findFlagsForOwners(
    ownerUids: readonly string[],
  ): Promise<Map<string, ProgressSharingFlags>> {
    const result = new Map<string, ProgressSharingFlags>();
    if (ownerUids.length === 0) {
      return result;
    }
    const res = await this.db.query<{ owner_uid: string } & Record<string, unknown>>(
      `SELECT owner_uid, ${FLAG_COLUMNS.join(', ')}
         FROM social_progress_settings
        WHERE owner_uid = ANY($1::text[])`,
      [ownerUids as string[]],
    );
    for (const row of res.rows) {
      result.set(row.owner_uid, flagsOf(row));
    }
    return result;
  }

  /** Cria a linha com os defaults. Chamado dentro da transação que cria o perfil social. */
  async createDefaults(ownerUid: string, now: number, client?: PoolClient): Promise<void> {
    const runner: DbClient = (client ?? this.db) as DbClient;
    // Só `owner_uid` e `updated_at`: cada interruptor nasce do `DEFAULT FALSE` da própria coluna,
    // e uma coluna nova não precisa lembrar de entrar aqui para nascer desligada.
    await runner.query(
      `INSERT INTO social_progress_settings (owner_uid, week_time_zone, updated_at)
       VALUES ($1, NULL, $2)`,
      [ownerUid, now],
    );
  }

  /**
   * Atualização parcial.
   *
   * Os parâmetros de consistência, quando vêm, são substituídos por inteiro **na mesma
   * transação** da linha de preferências: o histórico de metas é um conjunto, e um conjunto meio
   * substituído descreveria uma configuração que nunca existiu em aparelho nenhum.
   */
  async update(ownerUid: string, input: UpdateProgressSettingsInput): Promise<void> {
    // `$1` é o dono; `$2..$(n+1)` são as colunas do `INSERT` inicial; o `SET` do conflito usa os
    // índices seguintes.
    const insertColumns: string[] = [];
    const insertValues: unknown[] = [];
    const include = (column: string, value: unknown): void => {
      insertColumns.push(column);
      insertValues.push(value);
    };
    for (const [flag, column] of PROGRESS_SHARING_FLAGS) {
      include(column, input[flag] === true);
    }
    include('week_time_zone', input.weekTimeZone ?? null);
    include('tracking_started_at_epoch_day', input.consistency?.trackingStartedAtEpochDay ?? null);
    include('updated_at', input.now);

    let paramIndex = insertColumns.length + 2;
    const assignments: string[] = [];
    const updateValues: unknown[] = [];

    const set = (column: string, value: unknown): void => {
      assignments.push(`${column} = $${paramIndex++}`);
      updateValues.push(value);
    };

    for (const [flag, column] of PROGRESS_SHARING_FLAGS) {
      const value = input[flag];
      if (value !== undefined) set(column, Boolean(value));
    }
    if (input.weekTimeZone !== undefined) set('week_time_zone', input.weekTimeZone);
    if (input.consistency !== undefined) {
      set('tracking_started_at_epoch_day', input.consistency.trackingStartedAtEpochDay);
    }

    if (assignments.length === 0) {
      return;
    }
    assignments.push(`updated_at = $${paramIndex++}`);
    updateValues.push(input.now);

    const placeholders = insertColumns.map((_, index) => `$${index + 2}`);

    await this.db.transaction(async (client) => {
      await client.query(
        `INSERT INTO social_progress_settings (owner_uid, ${insertColumns.join(', ')})
         VALUES ($1, ${placeholders.join(', ')})
         ON CONFLICT (owner_uid) DO UPDATE SET ${assignments.join(', ')}`,
        [ownerUid, ...insertValues, ...updateValues],
      );

      if (input.consistency !== undefined) {
        await client.query(`DELETE FROM social_progress_weekly_goals WHERE owner_uid = $1`, [
          ownerUid,
        ]);
        for (const snapshot of input.consistency.weeklyGoals) {
          await client.query(
            `INSERT INTO social_progress_weekly_goals (owner_uid, week_start_epoch_day, goal)
             VALUES ($1, $2, $3)`,
            [ownerUid, snapshot.weekStartEpochDay, snapshot.goal],
          );
        }
      }
    });
  }
}

type SettingsRow = {
  [K in (typeof PROGRESS_SHARING_FLAGS)[number][1]]: boolean | number;
} & {
  week_time_zone: string | null;
  tracking_started_at_epoch_day: string | number | null;
  updated_at: string | number;
};

function flagsOf(row: Record<string, unknown>): ProgressSharingFlags {
  return Object.fromEntries(
    PROGRESS_SHARING_FLAGS.map(([flag, column]) => [flag, Boolean(row[column])]),
  ) as ProgressSharingFlags;
}

interface GoalRow {
  week_start_epoch_day: string | number;
  goal: string | number;
}
