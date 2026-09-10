import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';

/**
 * As preferências de compartilhamento de progresso, como estão gravadas.
 */
export interface StoredProgressSettings {
  readonly shareLevel: boolean;
  readonly shareConsistencyStreak: boolean;
  readonly shareWeeklyWorkoutCount: boolean;
  readonly shareHighlightedAchievements: boolean;
  readonly weekTimeZone: string | null;
  readonly updatedAt: number;
}

export interface UpdateProgressSettingsInput {
  readonly shareLevel?: boolean;
  readonly shareConsistencyStreak?: boolean;
  readonly shareWeeklyWorkoutCount?: boolean;
  readonly shareHighlightedAchievements?: boolean;
  readonly weekTimeZone?: string;
  readonly now: number;
}

export const SOCIAL_PROGRESS_SHARING_DEFAULTS = {
  shareLevel: false,
  shareConsistencyStreak: false,
  shareWeeklyWorkoutCount: false,
  shareHighlightedAchievements: false,
  weekTimeZone: null,
} as const;

@Injectable()
export class SocialProgressSettingsRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * As preferências daquela conta.
   */
  async find(ownerUid: string): Promise<StoredProgressSettings> {
    const res = await this.db.query<SettingsRow>(
      `SELECT share_level, share_consistency_streak, share_weekly_workout_count,
              share_highlighted_achievements, week_time_zone, updated_at
         FROM social_progress_settings
        WHERE owner_uid = $1`,
      [ownerUid],
    );

    const row = res.rows[0];
    if (!row) {
      return { ...SOCIAL_PROGRESS_SHARING_DEFAULTS, updatedAt: 0 };
    }
    return {
      shareLevel: Boolean(row.share_level),
      shareConsistencyStreak: Boolean(row.share_consistency_streak),
      shareWeeklyWorkoutCount: Boolean(row.share_weekly_workout_count),
      shareHighlightedAchievements: Boolean(row.share_highlighted_achievements),
      weekTimeZone: row.week_time_zone,
      updatedAt: Number(row.updated_at),
    };
  }

  /** Cria a linha com os defaults. Chamado dentro da transação que cria o perfil social. */
  async createDefaults(ownerUid: string, now: number, client?: PoolClient): Promise<void> {
    const runner: DbClient = (client ?? this.db) as DbClient;
    await runner.query(
      `INSERT INTO social_progress_settings
         (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
          share_highlighted_achievements, week_time_zone, updated_at)
       VALUES ($1, FALSE, FALSE, FALSE, FALSE, NULL, $2)`,
      [ownerUid, now],
    );
  }

  /**
   * Atualização parcial.
   */
  async update(ownerUid: string, input: UpdateProgressSettingsInput): Promise<void> {
    let paramIndex = 8;
    const assignments: string[] = [];
    const updateValues: unknown[] = [];

    const set = (column: string, value: unknown): void => {
      assignments.push(`${column} = $${paramIndex++}`);
      updateValues.push(value);
    };

    if (input.shareLevel !== undefined) set('share_level', Boolean(input.shareLevel));
    if (input.shareConsistencyStreak !== undefined) {
      set('share_consistency_streak', Boolean(input.shareConsistencyStreak));
    }
    if (input.shareWeeklyWorkoutCount !== undefined) {
      set('share_weekly_workout_count', Boolean(input.shareWeeklyWorkoutCount));
    }
    if (input.shareHighlightedAchievements !== undefined) {
      set('share_highlighted_achievements', Boolean(input.shareHighlightedAchievements));
    }
    if (input.weekTimeZone !== undefined) set('week_time_zone', input.weekTimeZone);

    if (assignments.length === 0) {
      return;
    }
    assignments.push(`updated_at = $${paramIndex++}`);
    updateValues.push(input.now);

    const initialValues = [
      ownerUid,
      input.shareLevel === true,
      input.shareConsistencyStreak === true,
      input.shareWeeklyWorkoutCount === true,
      input.shareHighlightedAchievements === true,
      input.weekTimeZone ?? null,
      input.now,
    ];

    await this.db.query(
      `INSERT INTO social_progress_settings
         (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
          share_highlighted_achievements, week_time_zone, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (owner_uid) DO UPDATE SET ${assignments.join(', ')}`,
      [...initialValues, ...updateValues],
    );
  }
}

interface SettingsRow {
  share_level: boolean | number;
  share_consistency_streak: boolean | number;
  share_weekly_workout_count: boolean | number;
  share_highlighted_achievements: boolean | number;
  week_time_zone: string | null;
  updated_at: string | number;
}
