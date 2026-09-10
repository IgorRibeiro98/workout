import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';

/**
 * As CTEs que definem o escopo do viewer. Prefixo de toda consulta desta fase.
 */
export function viewerBlockedCte(viewerParam: string = '$1'): string {
  return `viewer_blocked AS (
    SELECT blocked_uid AS uid FROM social_blocks WHERE blocker_uid = ${viewerParam}
    UNION
    SELECT blocker_uid AS uid FROM social_blocks WHERE blocked_uid = ${viewerParam}
  )`;
}

export function viewerScopeCte(viewerParam: string = '$1'): string {
  return `
  ${viewerBlockedCte(viewerParam)},
  viewer_friends AS (
    SELECT CASE WHEN user_a_uid = ${viewerParam} THEN user_b_uid ELSE user_a_uid END AS uid
      FROM friendships
     WHERE user_a_uid = ${viewerParam} OR user_b_uid = ${viewerParam}
  ),
  eligible_authors AS (
    SELECT ${viewerParam} AS uid
    UNION
    SELECT uid FROM viewer_friends WHERE uid NOT IN (SELECT uid FROM viewer_blocked)
  )`;
}

export const VIEWER_BLOCKED_CTE = viewerBlockedCte('$1');
export const VIEWER_SCOPE_CTE = viewerScopeCte('$1');

/**
 * O predicado de "esta interação é visível para o viewer" (§83/§86/§69/§92).
 *
 * Duas condições, e cada uma responde a um cenário diferente:
 *
 * 1. **o autor da interação continua tendo relação com o autor do post** — ele é o próprio autor
 *    do post, ou é amigo dele. Um comentário de alguém que desfez a amizade com o dono do post
 *    deixa de aparecer (§84), sem hard delete: a relação some, a interação some da leitura;
 * 2. **o autor da interação não está em bloqueio com o viewer**, em nenhuma direção. É isto que
 *    resolve o caso de terceiro (§86/§117): A e B são ambos amigos de C, A bloqueou B, e no post
 *    de C cada um deixa de ver a interação do outro — **sem** que o comentário de B no post de C
 *    seja apagado para C. Bloqueio é autorização por viewer, nunca exclusão global.
 *
 * O perfil do autor da interação precisa estar `ACTIVE` (§54): quem desativou o Social some das
 * leituras dos outros e volta inteiro ao reativar.
 *
 * @param actorUidColumn coluna com o uid de quem interagiu (ex.: `r.reactor_uid`)
 * @param authorUidColumn coluna com o uid do autor do check-in (ex.: `c.author_uid`)
 */
export function interactionVisibleSql(actorUidColumn: string, authorUidColumn: string): string {
  return `(
    EXISTS (SELECT 1 FROM social_profiles ap
             WHERE ap.owner_uid = ${actorUidColumn} AND ap.status = 'ACTIVE')
    AND (
      ${actorUidColumn} = ${authorUidColumn}
      OR EXISTS (
        SELECT 1 FROM friendships f
         WHERE (f.user_a_uid = ${actorUidColumn} AND f.user_b_uid = ${authorUidColumn})
            OR (f.user_a_uid = ${authorUidColumn} AND f.user_b_uid = ${actorUidColumn})
      )
    )
    AND ${actorUidColumn} NOT IN (SELECT uid FROM viewer_blocked)
  )`;
}

/**
 * O predicado de "esta interação **de Squad** é visível para o viewer" (T17.12 §9/§39/§40/§41).
 *
 * O par de `interactionVisibleSql`, mas para audiência `GROUP`: aqui a relação que autoriza não é
 * amizade, é participação **naquele Squad**.
 *
 * ```text
 * groupInteractionVisible =
 *     perfil ACTIVE de quem interagiu
 *     ∧ quem interagiu ainda é membro ativo daquele Squad     (§43/§143 — sair revoga a leitura)
 *     ∧ ¬bloqueio entre viewer e quem interagiu                (§39/§40/§41 — por viewer, nunca global)
 * ```
 *
 * A segunda condição é defesa em profundidade, do mesmo jeito que `groupShareVisibleSql` exige que
 * o **autor do post** continue membro: `leave`/`removeMember` já apagam as interações da pessoa
 * naquele Squad (§44), e esta cláusula vale mesmo se aquela escrita falhar pela metade.
 *
 * A consulta que usa este fragmento precisa carregar [VIEWER_BLOCKED_CTE] (ou [VIEWER_SCOPE_CTE],
 * que o inclui) e passar `:viewer` como parâmetro nomeado.
 *
 * @param actorUidColumn coluna com o uid de quem interagiu (ex.: `r.reactor_uid`)
 * @param groupIdColumn coluna com o `group_id` da própria interação (ex.: `r.group_id`)
 */
export function groupInteractionVisibleSql(actorUidColumn: string, groupIdColumn: string): string {
  return `(
    EXISTS (SELECT 1 FROM social_profiles ap
             WHERE ap.owner_uid = ${actorUidColumn} AND ap.status = 'ACTIVE')
    AND EXISTS (
      SELECT 1 FROM social_group_memberships am
       WHERE am.group_id = ${groupIdColumn} AND am.member_uid = ${actorUidColumn}
    )
    AND ${actorUidColumn} NOT IN (SELECT uid FROM viewer_blocked)
  )`;
}

/**
 * O predicado de "o viewer é membro ativo do Squad `:groupId`" (T17.12 §9/§90).
 *
 * Ele existe como fragmento próprio porque **toda** consulta de audiência `GROUP` precisa dele, e
 * porque ele é a segunda barreira das rotas: o resolvedor de contexto já respondeu `404` para quem
 * não é membro, e esta cláusula garante que nenhuma consulta futura consiga devolver linha para
 * alguém que não seja — nem por engano, nem por um caminho novo que esqueça a checagem de fora.
 *
 * Exige `:groupId` e `:viewer` como parâmetros nomeados.
 */
export function viewerInActiveGroupSql(groupIdParam: string = ':groupId', viewerParam: string = ':viewer'): string {
  return `(
    EXISTS (SELECT 1 FROM social_groups g WHERE g.id = ${groupIdParam} AND g.status = 'ACTIVE')
    AND EXISTS (SELECT 1 FROM social_group_memberships vm
                 WHERE vm.group_id = ${groupIdParam} AND vm.member_uid = ${viewerParam})
  )`;
}

export function groupShareVisibleSql(
  checkInIdColumn: string,
  authorUidColumn: string,
  viewerParam: string = '$1',
  groupIdParam?: string,
): string {
  return `(
    ${authorUidColumn} NOT IN (SELECT uid FROM viewer_blocked)
    AND EXISTS (
      SELECT 1
        FROM social_group_checkin_shares s
        JOIN social_groups g             ON g.id = s.group_id AND g.status = 'ACTIVE'
        JOIN social_group_memberships vm ON vm.group_id = s.group_id AND vm.member_uid = ${viewerParam}
        JOIN social_group_memberships am ON am.group_id = s.group_id
                                        AND am.member_uid = ${authorUidColumn}
       WHERE s.checkin_id = ${checkInIdColumn}
         ${groupIdParam ? `AND s.group_id = ${groupIdParam}` : ''}
    )
  )`;
}

/** O que a política devolve quando o check-in é visível. Nada de conteúdo de treino. */
export interface VisibleCheckIn {
  readonly checkInId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly caption: string | null;
  readonly publishedAt: number;
}

/**
 * Um check-in visível **e** por qual caminho ele chegou (T17.11 §78/§82).
 */
export type CheckInAccessGrant = 'SELF' | 'FRIEND' | 'GROUP';

export interface AccessibleCheckIn extends VisibleCheckIn {
  readonly grant: CheckInAccessGrant;
  /** `true` só para `SELF` e `FRIEND` (§70/§73). Decidido no servidor, nunca pela tela (§72). */
  readonly canInteract: boolean;
}

@Injectable()
export class WorkoutCheckInAccessPolicy {
  constructor(private readonly db: PostgresService) {}

  /**
   * O check-in [checkInId], **se** o viewer pode vê-lo agora.
   */
  async findVisibleCheckIn(viewerUid: string, checkInId: string): Promise<VisibleCheckIn | null> {
    const res = await this.db.query<{
      checkInId: string;
      authorUid: string;
      authorSocialId: string;
      authorDisplayName: string;
      caption: string | null;
      publishedAt: string | number;
    }>(
      `WITH ${viewerScopeCte('$1')}
       SELECT c.id           AS "checkInId",
              c.author_uid   AS "authorUid",
              p.social_id    AS "authorSocialId",
              p.display_name AS "authorDisplayName",
              c.caption      AS caption,
              c.created_at   AS "publishedAt"
         FROM social_workout_checkins c
         JOIN eligible_authors ea ON ea.uid = c.author_uid
         JOIN social_profiles p   ON p.owner_uid = c.author_uid
        WHERE c.id = $2
          AND c.status = 'PUBLISHED'
          AND p.status = 'ACTIVE'
          AND EXISTS (SELECT 1 FROM social_profiles vp
                       WHERE vp.owner_uid = $1 AND vp.status = 'ACTIVE')
        LIMIT 1`,
      [viewerUid, checkInId],
    );

    const row = res.rows[0];
    if (!row) return null;
    return {
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      caption: row.caption,
      publishedAt: Number(row.publishedAt),
    };
  }

  /**
   * O check-in [checkInId] **e** o caminho pelo qual o viewer o alcança (T17.11 §76/§82).
   */
  async findAccessibleCheckIn(viewerUid: string, checkInId: string): Promise<AccessibleCheckIn | null> {
    const res = await this.db.query<{
      checkInId: string;
      authorUid: string;
      authorSocialId: string;
      authorDisplayName: string;
      caption: string | null;
      publishedAt: string | number;
      direct: number;
    }>(
      `WITH ${viewerScopeCte('$1')}
       SELECT c.id           AS "checkInId",
              c.author_uid   AS "authorUid",
              p.social_id    AS "authorSocialId",
              p.display_name AS "authorDisplayName",
              c.caption      AS caption,
              c.created_at   AS "publishedAt",
              CASE WHEN EXISTS (SELECT 1 FROM eligible_authors ea WHERE ea.uid = c.author_uid)
                   THEN 1 ELSE 0 END AS direct
         FROM social_workout_checkins c
         JOIN social_profiles p ON p.owner_uid = c.author_uid
        WHERE c.id = $2
          AND c.status = 'PUBLISHED'
          AND p.status = 'ACTIVE'
          AND EXISTS (SELECT 1 FROM social_profiles vp
                       WHERE vp.owner_uid = $1 AND vp.status = 'ACTIVE')
          AND (
            EXISTS (SELECT 1 FROM eligible_authors ea WHERE ea.uid = c.author_uid)
            OR ${groupShareVisibleSql('c.id', 'c.author_uid', '$1')}
          )
        LIMIT 1`,
      [viewerUid, checkInId],
    );

    const row = res.rows[0];
    if (!row) return null;

    const direct = Number(row.direct) === 1;
    const grant: CheckInAccessGrant =
      row.authorUid === viewerUid ? 'SELF' : direct ? 'FRIEND' : 'GROUP';

    return {
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      caption: row.caption,
      publishedAt: Number(row.publishedAt),
      grant,
      canInteract: direct,
    };
  }

  /**
   * O check-in [checkInId], **se** o viewer o alcança por [groupId] especificamente (T17.12 §9).
   */
  async findGroupAccessibleCheckIn(
    viewerUid: string,
    checkInId: string,
    groupId: string,
  ): Promise<VisibleCheckIn | null> {
    const res = await this.db.query<{
      checkInId: string;
      authorUid: string;
      authorSocialId: string;
      authorDisplayName: string;
      caption: string | null;
      publishedAt: string | number;
    }>(
      `WITH ${viewerBlockedCte('$1')}
       SELECT c.id           AS "checkInId",
              c.author_uid   AS "authorUid",
              p.social_id    AS "authorSocialId",
              p.display_name AS "authorDisplayName",
              c.caption      AS caption,
              c.created_at   AS "publishedAt"
         FROM social_workout_checkins c
         JOIN social_profiles p ON p.owner_uid = c.author_uid
        WHERE c.id = $2
          AND c.status = 'PUBLISHED'
          AND p.status = 'ACTIVE'
          AND EXISTS (SELECT 1 FROM social_profiles vp
                       WHERE vp.owner_uid = $1 AND vp.status = 'ACTIVE')
          AND ${groupShareVisibleSql('c.id', 'c.author_uid', '$1', '$3')}
        LIMIT 1`,
      [viewerUid, checkInId, groupId],
    );

    const row = res.rows[0];
    if (!row) return null;
    return {
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      caption: row.caption,
      publishedAt: Number(row.publishedAt),
    };
  }
}
