import { Injectable } from '@nestjs/common';
import { DbClient, PostgresService } from '../../database/postgres.service';
import { isPgUniqueViolation } from '../../database/database.errors';
import type { SocialDiscoverability, SocialProfileStatus } from './social.contract';

export interface StoredSocialProfile {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly friendCode: string;
  readonly displayName: string;
  readonly status: SocialProfileStatus;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface StoredSocialPrivacy {
  readonly discoverability: SocialDiscoverability;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly activityTimeZoneId: string | null;
  readonly friendRankingParticipationEnabled: boolean;
  readonly updatedAt: number;
}

export interface StoredSocialAccount {
  readonly profile: StoredSocialProfile;
  readonly privacy: StoredSocialPrivacy;
}

export interface CreateSocialAccountInput {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly friendCode: string;
  readonly displayName: string;
  readonly discoverability: SocialDiscoverability;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly activityTimeZoneId?: string | null;
  readonly friendRankingParticipationEnabled?: boolean;
  readonly now: number;
}

export interface UpdateSocialPrivacyInput {
  readonly discoverability?: SocialDiscoverability;
  readonly friendRequestsEnabled?: boolean;
  readonly activitySharingEnabled?: boolean;
  readonly activityTimeZoneId?: string | null;
  readonly friendRankingParticipationEnabled?: boolean;
  readonly now: number;
}

export class FriendCodeCollisionError extends Error {
  constructor() {
    super('friendCode já existe');
    this.name = 'FriendCodeCollisionError';
  }
}

/**
 * A persistência do domínio social (T17.0 / T18.0 PostgreSQL).
 */
@Injectable()
export class SocialRepository {
  constructor(private readonly db: PostgresService) {}

  /** O perfil **daquela conta**, com a privacidade. `null` quando a conta nunca ativou. */
  async find(ownerUid: string): Promise<StoredSocialAccount | null> {
    const res = await this.db.query<AccountRow>(
      `SELECT p.owner_uid, p.social_id, p.friend_code, p.display_name, p.status,
              p.created_at, p.updated_at,
              s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
              s.activity_time_zone_id, s.friend_ranking_participation_enabled,
              s.updated_at AS privacy_updated_at
       FROM social_profiles p
       JOIN social_privacy_settings s ON s.owner_uid = p.owner_uid
       WHERE p.owner_uid = $1`,
      [ownerUid],
    );

    const row = res.rows[0];
    return row ? toAccount(row) : null;
  }

  /**
   * Cria o perfil, a privacidade e o compartilhamento de progresso, em uma transação atômica.
   */
  async create(input: CreateSocialAccountInput): Promise<StoredSocialAccount> {
    let existing: StoredSocialAccount | null = null;

    try {
      existing = await this.db.transaction(async (client) => {
        const checkRes = await client.query<AccountRow>(
          `SELECT p.owner_uid, p.social_id, p.friend_code, p.display_name, p.status,
                  p.created_at, p.updated_at,
                  s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
                  s.activity_time_zone_id, s.friend_ranking_participation_enabled,
                  s.updated_at AS privacy_updated_at
           FROM social_profiles p
           JOIN social_privacy_settings s ON s.owner_uid = p.owner_uid
           WHERE p.owner_uid = $1`,
          [input.ownerUid],
        );
        if (checkRes.rows[0]) {
          return toAccount(checkRes.rows[0]);
        }

        await client.query(
          `INSERT INTO social_profiles
             (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
           VALUES ($1, $2, $3, $4, 'ACTIVE', $5, $6)`,
          [
            input.ownerUid,
            input.socialId,
            input.friendCode,
            input.displayName,
            input.now,
            input.now,
          ],
        );

        await client.query(
          `INSERT INTO social_privacy_settings
             (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled,
              activity_time_zone_id, friend_ranking_participation_enabled, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7)`,
          [
            input.ownerUid,
            input.discoverability,
            Boolean(input.friendRequestsEnabled),
            Boolean(input.activitySharingEnabled),
            input.activityTimeZoneId ?? null,
            input.friendRankingParticipationEnabled !== undefined
              ? Boolean(input.friendRankingParticipationEnabled)
              : false,
            input.now,
          ],
        );

        await client.query(
          `INSERT INTO social_progress_settings
             (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
              share_highlighted_achievements, week_time_zone, updated_at)
           VALUES ($1, FALSE, FALSE, FALSE, FALSE, NULL, $2)`,
          [input.ownerUid, input.now],
        );

        return null;
      });
    } catch (error) {
      if (isPgUniqueViolation(error, 'friend_code')) {
        throw new FriendCodeCollisionError();
      }
      if (isPgUniqueViolation(error, 'owner_uid') || isPgUniqueViolation(error, 'social_profiles_pkey')) {
        const created = await this.find(input.ownerUid);
        if (created) {
          return created;
        }
      }
      throw error;
    }

    if (existing) {
      return existing;
    }

    const created = await this.find(input.ownerUid);
    if (!created) {
      throw new Error('perfil social não encontrado imediatamente após a criação');
    }
    return created;
  }

  /** Renomeia. */
  async updateDisplayName(ownerUid: string, displayName: string, now: number): Promise<void> {
    await this.db.query(
      `UPDATE social_profiles SET display_name = $1, updated_at = $2 WHERE owner_uid = $3`,
      [displayName, now, ownerUid],
    );
  }

  /** Muda o status preservando a identidade. */
  async updateStatus(
    ownerUid: string,
    status: SocialProfileStatus,
    now: number,
    client?: import('pg').PoolClient,
  ): Promise<void> {
    const runner: DbClient = (client ?? this.db) as DbClient;
    await runner.query(
      `UPDATE social_profiles SET status = $1, updated_at = $2 WHERE owner_uid = $3`,
      [status, now, ownerUid],
    );
  }

  /** Atualização parcial da privacidade. */
  async updatePrivacy(ownerUid: string, input: UpdateSocialPrivacyInput): Promise<void> {
    const assignments: string[] = [];
    const values: unknown[] = [];
    let paramIndex = 1;

    if (input.discoverability !== undefined) {
      assignments.push(`discoverability = $${paramIndex++}`);
      values.push(input.discoverability);
    }
    if (input.friendRequestsEnabled !== undefined) {
      assignments.push(`friend_requests_enabled = $${paramIndex++}`);
      values.push(Boolean(input.friendRequestsEnabled));
    }
    if (input.activitySharingEnabled !== undefined) {
      assignments.push(`activity_sharing_enabled = $${paramIndex++}`);
      values.push(Boolean(input.activitySharingEnabled));
    }
    if (input.activityTimeZoneId !== undefined) {
      assignments.push(`activity_time_zone_id = $${paramIndex++}`);
      values.push(input.activityTimeZoneId);
    }
    if (input.friendRankingParticipationEnabled !== undefined) {
      assignments.push(`friend_ranking_participation_enabled = $${paramIndex++}`);
      values.push(Boolean(input.friendRankingParticipationEnabled));
    }
    if (assignments.length === 0) {
      return;
    }

    assignments.push(`updated_at = $${paramIndex++}`);
    values.push(input.now);

    values.push(ownerUid);
    const sql = `UPDATE social_privacy_settings SET ${assignments.join(', ')} WHERE owner_uid = $${paramIndex}`;

    await this.db.query(sql, values);
  }
}

interface AccountRow {
  owner_uid: string;
  social_id: string;
  friend_code: string;
  display_name: string;
  status: string;
  created_at: string | number;
  updated_at: string | number;
  discoverability: string;
  friend_requests_enabled: boolean | number;
  activity_sharing_enabled: boolean | number;
  activity_time_zone_id: string | null;
  friend_ranking_participation_enabled: boolean | number;
  privacy_updated_at: string | number;
}

function toAccount(row: AccountRow): StoredSocialAccount {
  return {
    profile: {
      ownerUid: row.owner_uid,
      socialId: row.social_id,
      friendCode: row.friend_code,
      displayName: row.display_name,
      status: row.status as SocialProfileStatus,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at),
    },
    privacy: {
      discoverability: row.discoverability as SocialDiscoverability,
      friendRequestsEnabled: Boolean(row.friend_requests_enabled),
      activitySharingEnabled: Boolean(row.activity_sharing_enabled),
      activityTimeZoneId: row.activity_time_zone_id ?? null,
      friendRankingParticipationEnabled: Boolean(row.friend_ranking_participation_enabled),
      updatedAt: Number(row.privacy_updated_at),
    },
  };
}
