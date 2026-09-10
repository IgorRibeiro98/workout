import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import type { InteractionAudienceType, ReactionType } from './workout-checkin.contract';
import type { InteractionAudience } from './workout-checkin-context.resolver';
import {
  VIEWER_BLOCKED_CTE,
  VIEWER_SCOPE_CTE,
  groupInteractionVisibleSql,
  interactionVisibleSql,
  viewerInActiveGroupSql,
} from './workout-checkin.access-policy';

/** Uma contagem de reação já filtrada para o viewer **e para a audiência** (T17.9 §69; T17.12 §37). */
export interface ReactionCountRow {
  readonly checkInId: string;
  readonly type: ReactionType;
  readonly total: number;
}

/** Um comentário como ele sai para a tela (T17.9 §87). Sem uid — §88 é bloqueante. */
export interface CommentRow {
  readonly commentId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly body: string;
  readonly createdAt: number;
}

/**
 * Um comentário como ele mora no banco, com a audiência a que ele pertence (T17.12 §17).
 */
export interface StoredComment {
  readonly id: string;
  readonly checkInId: string;
  readonly authorUid: string;
  readonly audienceType: InteractionAudienceType;
  readonly groupId: string | null;
  readonly deletedAt: number | null;
}

/**
 * Reações e comentários (T17.9 Etapas D/E; T17.12 Etapas D/E).
 */
@Injectable()
export class CheckInInteractionRepository {
  constructor(private readonly db: PostgresService) {}

  private getRunner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  // ------------------------------------------------------------------ reações (§12–§16/§37–§41)

  /**
   * Adiciona ou **troca** a reação, dentro de uma audiência (§12/§15).
   */
  async putReaction(
    checkInId: string,
    reactorUid: string,
    type: ReactionType,
    audience: InteractionAudience,
    now: number,
    client?: PoolClient,
  ): Promise<void> {
    const conflictTarget =
      audience.type === 'FRIEND'
        ? `(checkin_id, reactor_uid) WHERE audience_type = 'FRIEND'`
        : `(checkin_id, reactor_uid, group_id) WHERE audience_type = 'GROUP'`;

    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_checkin_reactions
         (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT ${conflictTarget}
       DO UPDATE SET type = EXCLUDED.type, updated_at = EXCLUDED.updated_at`,
      [checkInId, reactorUid, type, audience.type, groupIdOf(audience), now, now],
    );
  }

  /**
   * Remove a reação **daquela audiência** (§16). Idempotente: repetir converge.
   */
  async removeReaction(
    checkInId: string,
    reactorUid: string,
    audience: InteractionAudience,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner = this.getRunner(client);
    const result = await runner.query(
      `DELETE FROM social_checkin_reactions
        WHERE checkin_id = $1 AND reactor_uid = $2 AND audience_type = $3 AND group_id IS NOT DISTINCT FROM $4`,
      [checkInId, reactorUid, audience.type, groupIdOf(audience)],
    );
    return (result.rowCount ?? 0) > 0;
  }

  /**
   * As contagens por tipo, para este viewer **nesta audiência** (§37/§38/§39).
   */
  async countReactionsForCheckIns(
    viewerUid: string,
    checkInIds: readonly string[],
    audience: InteractionAudience,
    client?: PoolClient,
  ): Promise<ReactionCountRow[]> {
    if (checkInIds.length === 0) {
      return [];
    }

    const runner = this.getRunner(client);
    let sql: string;
    let params: unknown[];

    if (audience.type === 'FRIEND') {
      sql = `WITH ${VIEWER_SCOPE_CTE}
             SELECT r.checkin_id AS "checkInId",
                    r.type       AS type,
                    COUNT(*)     AS total
               FROM social_checkin_reactions r
               JOIN social_workout_checkins c ON c.id = r.checkin_id
              WHERE r.checkin_id = ANY($2::text[])
                AND r.audience_type = 'FRIEND'
                AND ${interactionVisibleSql('r.reactor_uid', 'c.author_uid')}
              GROUP BY r.checkin_id, r.type`;
      params = [viewerUid, checkInIds];
    } else {
      sql = `WITH ${VIEWER_BLOCKED_CTE}
             SELECT r.checkin_id AS "checkInId",
                    r.type       AS type,
                    COUNT(*)     AS total
               FROM social_checkin_reactions r
              WHERE r.checkin_id = ANY($3::text[])
                AND r.audience_type = 'GROUP'
                AND r.group_id = $2
                AND ${viewerInActiveGroupSql('$2', '$1')}
                AND ${groupInteractionVisibleSql('r.reactor_uid', 'r.group_id')}
              GROUP BY r.checkin_id, r.type`;
      params = [viewerUid, audience.groupId, checkInIds];
    }

    const res = await runner.query<{ checkInId: string; type: string; total: string | number }>(
      sql,
      params,
    );

    return res.rows.map((row: any) => ({
      checkInId: row.checkInId,
      type: row.type as ReactionType,
      total: Number(row.total),
    }));
  }

  /**
   * A reação do próprio viewer em cada post da página, **naquela audiência** (§37).
   */
  async findViewerReactions(
    viewerUid: string,
    checkInIds: readonly string[],
    audience: InteractionAudience,
    client?: PoolClient,
  ): Promise<Map<string, ReactionType>> {
    const result = new Map<string, ReactionType>();
    if (checkInIds.length === 0) {
      return result;
    }

    const runner = this.getRunner(client);
    const res = await runner.query<{ checkInId: string; type: string }>(
      `SELECT checkin_id AS "checkInId", type
         FROM social_checkin_reactions
        WHERE reactor_uid = $1
          AND audience_type = $2
          AND group_id IS NOT DISTINCT FROM $3
          AND checkin_id = ANY($4::text[])`,
      [viewerUid, audience.type, groupIdOf(audience), checkInIds],
    );

    for (const row of res.rows) {
      result.set(row.checkInId, row.type as ReactionType);
    }
    return result;
  }

  // ------------------------------------------------------------------ comentários (§17–§23)

  async createComment(
    id: string,
    checkInId: string,
    authorUid: string,
    body: string,
    audience: InteractionAudience,
    now: number,
    client?: PoolClient,
  ): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `INSERT INTO social_checkin_comments
         (id, checkin_id, author_uid, body, audience_type, group_id, created_at, deleted_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, NULL)`,
      [id, checkInId, authorUid, body, audience.type, groupIdOf(audience), now],
    );
  }

  /**
   * A página de comentários visíveis para o viewer **naquela audiência** (§65/§66/§137).
   */
  async listComments(
    viewerUid: string,
    checkInId: string,
    audience: InteractionAudience,
    limit: number,
    client?: PoolClient,
  ): Promise<CommentRow[]> {
    const runner = this.getRunner(client);
    let sql: string;
    let params: unknown[];

    if (audience.type === 'FRIEND') {
      sql = `WITH ${VIEWER_SCOPE_CTE}
             SELECT cm.id           AS "commentId",
                    cm.author_uid   AS "authorUid",
                    p.social_id     AS "authorSocialId",
                    p.display_name  AS "authorDisplayName",
                    cm.body         AS body,
                    cm.created_at   AS "createdAt"
               FROM social_checkin_comments cm
               JOIN social_workout_checkins c ON c.id = cm.checkin_id
               JOIN social_profiles p         ON p.owner_uid = cm.author_uid
              WHERE cm.checkin_id = $2
                AND cm.audience_type = 'FRIEND'
                AND cm.deleted_at IS NULL
                AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
              ORDER BY cm.created_at ASC, cm.id ASC
              LIMIT $3`;
      params = [viewerUid, checkInId, limit];
    } else {
      sql = `WITH ${VIEWER_BLOCKED_CTE}
             SELECT cm.id           AS "commentId",
                    cm.author_uid   AS "authorUid",
                    p.social_id     AS "authorSocialId",
                    p.display_name  AS "authorDisplayName",
                    cm.body         AS body,
                    cm.created_at   AS "createdAt"
               FROM social_checkin_comments cm
               JOIN social_profiles p ON p.owner_uid = cm.author_uid
              WHERE cm.checkin_id = $3
                AND cm.audience_type = 'GROUP'
                AND cm.group_id = $2
                AND cm.deleted_at IS NULL
                AND ${viewerInActiveGroupSql('$2', '$1')}
                AND ${groupInteractionVisibleSql('cm.author_uid', 'cm.group_id')}
              ORDER BY cm.created_at ASC, cm.id ASC
              LIMIT $4`;
      params = [viewerUid, audience.groupId, checkInId, limit];
    }

    const res = await runner.query<{
      commentId: string;
      authorUid: string;
      authorSocialId: string;
      authorDisplayName: string;
      body: string;
      createdAt: string | number;
    }>(sql, params);

    return res.rows.map((row: any) => ({
      commentId: row.commentId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      body: row.body,
      createdAt: Number(row.createdAt),
    }));
  }

  /**
   * Quantos comentários **este viewer** vê em cada post da página, naquela audiência (§63/§64).
   */
  async countCommentsForCheckIns(
    viewerUid: string,
    checkInIds: readonly string[],
    audience: InteractionAudience,
    client?: PoolClient,
  ): Promise<Map<string, number>> {
    const result = new Map<string, number>();
    if (checkInIds.length === 0) {
      return result;
    }

    const runner = this.getRunner(client);
    let sql: string;
    let params: unknown[];

    if (audience.type === 'FRIEND') {
      sql = `WITH ${VIEWER_SCOPE_CTE}
             SELECT cm.checkin_id AS "checkInId", COUNT(*) AS total
               FROM social_checkin_comments cm
               JOIN social_workout_checkins c ON c.id = cm.checkin_id
              WHERE cm.checkin_id = ANY($2::text[])
                AND cm.audience_type = 'FRIEND'
                AND cm.deleted_at IS NULL
                AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
              GROUP BY cm.checkin_id`;
      params = [viewerUid, checkInIds];
    } else {
      sql = `WITH ${VIEWER_BLOCKED_CTE}
             SELECT cm.checkin_id AS "checkInId", COUNT(*) AS total
               FROM social_checkin_comments cm
              WHERE cm.checkin_id = ANY($3::text[])
                AND cm.audience_type = 'GROUP'
                AND cm.group_id = $2
                AND cm.deleted_at IS NULL
                AND ${viewerInActiveGroupSql('$2', '$1')}
                AND ${groupInteractionVisibleSql('cm.author_uid', 'cm.group_id')}
              GROUP BY cm.checkin_id`;
      params = [viewerUid, audience.groupId, checkInIds];
    }

    const res = await runner.query<{ checkInId: string; total: string | number }>(sql, params);

    for (const row of res.rows) {
      result.set(row.checkInId, Number(row.total));
    }
    return result;
  }

  /**
   * Um comentário vivo **com a audiência dele**, sem julgar visibilidade.
   */
  async findComment(commentId: string, client?: PoolClient): Promise<StoredComment | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<{
      id: string;
      checkInId: string;
      authorUid: string;
      audienceType: string;
      groupId: string | null;
      deletedAt: string | number | null;
    }>(
      `SELECT id,
              checkin_id    AS "checkInId",
              author_uid    AS "authorUid",
              audience_type AS "audienceType",
              group_id      AS "groupId",
              deleted_at    AS "deletedAt"
         FROM social_checkin_comments
        WHERE id = $1 LIMIT 1`,
      [commentId],
    );

    const row = res.rows[0];
    if (!row) return null;
    return {
      id: row.id,
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      audienceType: row.audienceType as InteractionAudienceType,
      groupId: row.groupId,
      deletedAt: row.deletedAt != null ? Number(row.deletedAt) : null,
    };
  }

  /**
   * Exclusão de comentário (T17.9 §93/§94/§97; T17.12 §54).
   */
  async softDeleteComment(commentId: string, now: number, client?: PoolClient): Promise<boolean> {
    const runner = this.getRunner(client);
    const result = await runner.query(
      `UPDATE social_checkin_comments
          SET deleted_at = $1
        WHERE id = $2 AND deleted_at IS NULL`,
      [now, commentId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  /**
   * Quantos comentários esta conta deixou **neste check-in** dentro da janela (T17.9 §158).
   */
  async countRecentCommentsBy(
    checkInId: string,
    authorUid: string,
    sinceMs: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total
         FROM social_checkin_comments
        WHERE checkin_id = $1 AND author_uid = $2 AND created_at >= $3`,
      [checkInId, authorUid, sinceMs],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  // ------------------------------------------------------------------ ciclo de vida (§43–§46/§70–§72)

  /**
   * As interações de uma pessoa **naquele** Squad (§43/§44/§45/§142/§144).
   */
  async purgeGroupInteractionsByActor(
    groupId: string,
    actorUid: string,
    now: number,
    client?: PoolClient,
  ): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `DELETE FROM social_checkin_reactions
        WHERE audience_type = 'GROUP' AND group_id = $1 AND reactor_uid = $2`,
      [groupId, actorUid],
    );
    await runner.query(
      `UPDATE social_checkin_comments
          SET deleted_at = $1
        WHERE audience_type = 'GROUP' AND group_id = $2 AND author_uid = $3 AND deleted_at IS NULL`,
      [now, groupId, actorUid],
    );
  }

  /**
   * As interações que existiam **por causa daquele compartilhamento** (§70/§71/§145).
   */
  async purgeGroupInteractionsForShare(
    groupId: string,
    checkInId: string,
    now: number,
    client?: PoolClient,
  ): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `DELETE FROM social_checkin_reactions
        WHERE audience_type = 'GROUP' AND group_id = $1 AND checkin_id = $2`,
      [groupId, checkInId],
    );
    await runner.query(
      `UPDATE social_checkin_comments
          SET deleted_at = $1
        WHERE audience_type = 'GROUP' AND group_id = $2 AND checkin_id = $3 AND deleted_at IS NULL`,
      [now, groupId, checkInId],
    );
  }

  /**
   * Tudo o que pertencia à audiência daquele Squad (§72/§146).
   */
  async purgeGroupInteractions(groupId: string, now: number, client?: PoolClient): Promise<void> {
    const runner = this.getRunner(client);
    await runner.query(
      `DELETE FROM social_checkin_reactions WHERE audience_type = 'GROUP' AND group_id = $1`,
      [groupId],
    );
    await runner.query(
      `UPDATE social_checkin_comments
          SET deleted_at = $1
        WHERE audience_type = 'GROUP' AND group_id = $2 AND deleted_at IS NULL`,
      [now, groupId],
    );
  }
}

/** `null` para `FRIEND`, o `groupId` para `GROUP` — a tradução usada em toda escrita (§27). */
function groupIdOf(audience: InteractionAudience): string | null {
  return audience.type === 'GROUP' ? audience.groupId : null;
}
