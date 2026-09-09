import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
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
 *
 * A audiência é do **comentário**, e não do pedido: é isso que permite `DELETE .../comments/{id}`
 * continuar sem parâmetro de contexto (§122) — o servidor lê de que audiência aquele comentário é e
 * decide a moderação a partir dela, em vez de acreditar no que a tela disse.
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
 *
 * ## Toda leitura aqui é **por viewer e por audiência**
 *
 * Não existe "a contagem de reações de um post". Existe "a contagem que este viewer pode ver
 * **nesta audiência**" (§37/§38/§63/§64). São duas filtragens independentes, e cada uma existe por
 * um motivo diferente:
 *
 * ```text
 * por audiência  →  o comentário do Squad X não aparece no Squad Y nem no Feed de amigos   §20/§21/§22
 * por viewer     →  a participação de quem está em bloqueio não transparece nem como número §39/§40/§41
 * ```
 *
 * A primeira é da T17.12 e é o motivo desta fase existir: sem ela, o mesmo check-in em dois Squads
 * teria uma conversa só, e quem está em `Y` leria o que foi escrito em `X` por alguém com quem não
 * tem relação nenhuma. A segunda é da T17.9 e continua exatamente como estava.
 *
 * ## As duas audiências têm regras de visibilidade **diferentes**, e é por isso que cada consulta
 * tem dois corpos
 *
 * ```text
 * FRIEND  →  interactionVisibleSql       —  quem interagiu é o autor do post ou amigo dele
 * GROUP   →  groupInteractionVisibleSql  —  quem interagiu ainda é membro ativo daquele Squad
 * ```
 *
 * Elas não se misturam em nenhuma consulta: uma leitura é sempre de **uma** audiência (§90). Não
 * existe caminho que some as duas, e é isso que faz "FRIEND reaction não aparece em Group summary"
 * ser verdade por construção, e não por um filtro que alguém precisa lembrar de escrever.
 *
 * ## Bounded, e sem N+1 (§170/§171)
 *
 * As agregações do Feed recebem a **página inteira** de `checkInId` e devolvem uma linha por
 * (post, tipo). Um feed de 20 itens custa três consultas, não sessenta — e a audiência entra como
 * parâmetro, não como uma consulta a mais.
 */
@Injectable()
export class CheckInInteractionRepository {
  constructor(private readonly sqlite: SqliteService) {}

  // ------------------------------------------------------------------ reações (§12–§16/§37–§41)

  /**
   * Adiciona ou **troca** a reação, dentro de uma audiência (§12/§15).
   *
   * ## Por que dois `INSERT` diferentes, e não um só
   *
   * O alvo do `ON CONFLICT` precisa ser um índice único que exista de verdade, e a T17.12 tem
   * **dois** — um por partição de audiência (0020):
   *
   * ```text
   * FRIEND  →  ON CONFLICT (checkin_id, reactor_uid)             WHERE audience_type = 'FRIEND'
   * GROUP   →  ON CONFLICT (checkin_id, reactor_uid, group_id)   WHERE audience_type = 'GROUP'
   * ```
   *
   * Essa é a solução para o problema de `NULL` de §93/§94. Um `UNIQUE(checkin_id, reactor_uid,
   * group_id)` único para as duas audiências **não** funcionaria: no SQLite cada `NULL` é distinto
   * de qualquer outro numa `UNIQUE`, então duas reações `FRIEND` da mesma pessoa no mesmo post
   * (com `group_id IS NULL` nas duas) conviveriam sem conflito — e a regra "uma reação por pessoa
   * por post por audiência" seria falsa exatamente na audiência mais usada.
   *
   * Com os dois índices parciais, trocar 🔥 por 💪 continua sendo um `UPDATE` da linha daquela
   * audiência, e a reação da pessoa nas **outras** audiências não é tocada (§13/§139).
   */
  putReaction(
    checkInId: string,
    reactorUid: string,
    type: ReactionType,
    audience: InteractionAudience,
    now: number,
  ): void {
    const conflictTarget =
      audience.type === 'FRIEND'
        ? `(checkin_id, reactor_uid) WHERE audience_type = 'FRIEND'`
        : `(checkin_id, reactor_uid, group_id) WHERE audience_type = 'GROUP'`;

    this.sqlite.connection
      .prepare(
        `INSERT INTO social_checkin_reactions
           (checkin_id, reactor_uid, type, audience_type, group_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT ${conflictTarget}
         DO UPDATE SET type = excluded.type, updated_at = excluded.updated_at`,
      )
      .run(checkInId, reactorUid, type, audience.type, groupIdOf(audience), now, now);
  }

  /**
   * Remove a reação **daquela audiência** (§16). Idempotente: repetir converge.
   *
   * `group_id IS ?` e não `group_id = ?`: com `=`, a comparação com `NULL` nunca é verdadeira, e o
   * `DELETE` da reação `FRIEND` simplesmente não apagaria nada — o botão de desfazer ficaria
   * mudo. `IS` compara `NULL` com `NULL` corretamente e serve às duas audiências com uma consulta
   * só.
   */
  removeReaction(checkInId: string, reactorUid: string, audience: InteractionAudience): boolean {
    const result = this.sqlite.connection
      .prepare(
        `DELETE FROM social_checkin_reactions
          WHERE checkin_id = ? AND reactor_uid = ? AND audience_type = ? AND group_id IS ?`,
      )
      .run(checkInId, reactorUid, audience.type, groupIdOf(audience));
    return result.changes > 0;
  }

  /**
   * As contagens por tipo, para este viewer **nesta audiência** (§37/§38/§39).
   *
   * Não devolve lista de quem reagiu (§116): isso exporia participação social de terceiros — quem
   * é amigo de quem, quem está em qual Squad — e cobraria um `JOIN` de perfil por reação.
   */
  countReactionsForCheckIns(
    viewerUid: string,
    checkInIds: readonly string[],
    audience: InteractionAudience,
  ): ReactionCountRow[] {
    if (checkInIds.length === 0) {
      return [];
    }
    const { placeholders, params } = bindCheckInIds(checkInIds, viewerUid, audience);

    const sql =
      audience.type === 'FRIEND'
        ? `WITH ${VIEWER_SCOPE_CTE}
           SELECT r.checkin_id AS checkInId,
                  r.type       AS type,
                  COUNT(*)     AS total
             FROM social_checkin_reactions r
             JOIN social_workout_checkins c ON c.id = r.checkin_id
            WHERE r.checkin_id IN (${placeholders})
              AND r.audience_type = 'FRIEND'
              AND ${interactionVisibleSql('r.reactor_uid', 'c.author_uid')}
            GROUP BY r.checkin_id, r.type`
        : `WITH ${VIEWER_BLOCKED_CTE}
           SELECT r.checkin_id AS checkInId,
                  r.type       AS type,
                  COUNT(*)     AS total
             FROM social_checkin_reactions r
            WHERE r.checkin_id IN (${placeholders})
              AND r.audience_type = 'GROUP'
              AND r.group_id = :groupId
              AND ${viewerInActiveGroupSql()}
              AND ${groupInteractionVisibleSql('r.reactor_uid', 'r.group_id')}
            GROUP BY r.checkin_id, r.type`;

    return this.sqlite.connection.prepare(sql).all(params) as ReactionCountRow[];
  }

  /**
   * A reação do próprio viewer em cada post da página, **naquela audiência** (§37).
   *
   * Sem predicado de visibilidade: a reação é dele, e ele sempre a enxerga. O que muda em relação à
   * T17.9 é só o recorte — a reação que ele deixou no Squad X não aparece como `currentUserReaction`
   * no Feed de amigos, porque são interações independentes (§11/§13).
   */
  findViewerReactions(
    viewerUid: string,
    checkInIds: readonly string[],
    audience: InteractionAudience,
  ): Map<string, ReactionType> {
    const result = new Map<string, ReactionType>();
    if (checkInIds.length === 0) {
      return result;
    }
    const placeholders = checkInIds.map(() => '?').join(', ');
    const rows = this.sqlite.connection
      .prepare(
        `SELECT checkin_id AS checkInId, type
           FROM social_checkin_reactions
          WHERE reactor_uid = ?
            AND audience_type = ?
            AND group_id IS ?
            AND checkin_id IN (${placeholders})`,
      )
      .all(viewerUid, audience.type, groupIdOf(audience), ...checkInIds) as Array<{
      checkInId: string;
      type: ReactionType;
    }>;

    for (const row of rows) {
      result.set(row.checkInId, row.type);
    }
    return result;
  }

  // ------------------------------------------------------------------ comentários (§17–§23)

  createComment(
    id: string,
    checkInId: string,
    authorUid: string,
    body: string,
    audience: InteractionAudience,
    now: number,
  ): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_checkin_comments
           (id, checkin_id, author_uid, body, audience_type, group_id, created_at, deleted_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, NULL)`,
      )
      .run(id, checkInId, authorUid, body, audience.type, groupIdOf(audience), now);
  }

  /**
   * A página de comentários visíveis para o viewer **naquela audiência** (§65/§66/§137).
   *
   * `ORDER BY created_at ASC, id ASC` — cronológica, porque é uma conversa (T17.9 §91), com
   * desempate determinístico por identificador. `LIMIT` sempre: não existe histórico infinito.
   */
  listComments(
    viewerUid: string,
    checkInId: string,
    audience: InteractionAudience,
    limit: number,
  ): CommentRow[] {
    const params: Record<string, unknown> = {
      viewer: viewerUid,
      checkInId,
      limit,
    };
    if (audience.type === 'GROUP') {
      params.groupId = audience.groupId;
    }

    const sql =
      audience.type === 'FRIEND'
        ? `WITH ${VIEWER_SCOPE_CTE}
           SELECT cm.id           AS commentId,
                  cm.author_uid   AS authorUid,
                  p.social_id     AS authorSocialId,
                  p.display_name  AS authorDisplayName,
                  cm.body         AS body,
                  cm.created_at   AS createdAt
             FROM social_checkin_comments cm
             JOIN social_workout_checkins c ON c.id = cm.checkin_id
             JOIN social_profiles p         ON p.owner_uid = cm.author_uid
            WHERE cm.checkin_id = :checkInId
              AND cm.audience_type = 'FRIEND'
              AND cm.deleted_at IS NULL
              AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
            ORDER BY cm.created_at ASC, cm.id ASC
            LIMIT :limit`
        : `WITH ${VIEWER_BLOCKED_CTE}
           SELECT cm.id           AS commentId,
                  cm.author_uid   AS authorUid,
                  p.social_id     AS authorSocialId,
                  p.display_name  AS authorDisplayName,
                  cm.body         AS body,
                  cm.created_at   AS createdAt
             FROM social_checkin_comments cm
             JOIN social_profiles p ON p.owner_uid = cm.author_uid
            WHERE cm.checkin_id = :checkInId
              AND cm.audience_type = 'GROUP'
              AND cm.group_id = :groupId
              AND cm.deleted_at IS NULL
              AND ${viewerInActiveGroupSql()}
              AND ${groupInteractionVisibleSql('cm.author_uid', 'cm.group_id')}
            ORDER BY cm.created_at ASC, cm.id ASC
            LIMIT :limit`;

    return this.sqlite.connection.prepare(sql).all(params) as CommentRow[];
  }

  /**
   * Quantos comentários **este viewer** vê em cada post da página, naquela audiência (§63/§64).
   *
   * Nunca a contagem global, e nunca a soma das audiências: um card de Squad dizendo "5
   * comentários" e uma lista com 3 seria o bloqueio anunciando a si mesmo; um card dizendo "9"
   * porque somou o Feed de amigos seria pior — contaria a existência de uma conversa da qual aquele
   * viewer não faz parte.
   */
  countCommentsForCheckIns(
    viewerUid: string,
    checkInIds: readonly string[],
    audience: InteractionAudience,
  ): Map<string, number> {
    const result = new Map<string, number>();
    if (checkInIds.length === 0) {
      return result;
    }
    const { placeholders, params } = bindCheckInIds(checkInIds, viewerUid, audience);

    const sql =
      audience.type === 'FRIEND'
        ? `WITH ${VIEWER_SCOPE_CTE}
           SELECT cm.checkin_id AS checkInId, COUNT(*) AS total
             FROM social_checkin_comments cm
             JOIN social_workout_checkins c ON c.id = cm.checkin_id
            WHERE cm.checkin_id IN (${placeholders})
              AND cm.audience_type = 'FRIEND'
              AND cm.deleted_at IS NULL
              AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
            GROUP BY cm.checkin_id`
        : `WITH ${VIEWER_BLOCKED_CTE}
           SELECT cm.checkin_id AS checkInId, COUNT(*) AS total
             FROM social_checkin_comments cm
            WHERE cm.checkin_id IN (${placeholders})
              AND cm.audience_type = 'GROUP'
              AND cm.group_id = :groupId
              AND cm.deleted_at IS NULL
              AND ${viewerInActiveGroupSql()}
              AND ${groupInteractionVisibleSql('cm.author_uid', 'cm.group_id')}
            GROUP BY cm.checkin_id`;

    const rows = this.sqlite.connection.prepare(sql).all(params) as Array<{
      checkInId: string;
      total: number;
    }>;

    for (const row of rows) {
      result.set(row.checkInId, row.total);
    }
    return result;
  }

  /**
   * Um comentário vivo **com a audiência dele**, sem julgar visibilidade.
   *
   * Quem julga é o serviço, com a política: aqui devolvemos de que audiência aquele comentário é —
   * e é esse dado que faz a moderação do dono do Squad (§54) valer só sobre `GROUP` daquele Squad, e
   * nunca sobre um comentário `FRIEND` (§55).
   */
  findComment(commentId: string): StoredComment | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT id,
                checkin_id    AS checkInId,
                author_uid    AS authorUid,
                audience_type AS audienceType,
                group_id      AS groupId,
                deleted_at    AS deletedAt
           FROM social_checkin_comments
          WHERE id = ? LIMIT 1`,
      )
      .get(commentId) as StoredComment | undefined;
    return row ?? null;
  }

  /**
   * Exclusão de comentário (T17.9 §93/§94/§97; T17.12 §54).
   *
   * Escrita **condicional** em `deleted_at IS NULL`: dois toques produzem uma transição e um no-op,
   * e a decisão sobrevive ao processo morrer. Quem pode apagar — o autor do comentário, o autor do
   * check-in ou o dono do Squad quando a audiência é `GROUP` dele — é decidido pelo serviço.
   */
  softDeleteComment(commentId: string, now: number): boolean {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_checkin_comments
            SET deleted_at = ?
          WHERE id = ? AND deleted_at IS NULL`,
      )
      .run(now, commentId);
    return result.changes > 0;
  }

  /**
   * Quantos comentários esta conta deixou **neste check-in** dentro da janela (T17.9 §158).
   *
   * ## Por que a contagem atravessa as audiências, sendo que tudo o mais nesta fase as separa
   *
   * Porque ela descreve outra coisa. As audiências existem para separar **conversas** — quem lê o
   * quê. Este teto existe para conter **enxurrada na publicação de uma pessoa**, e quem recebe a
   * enxurrada é o autor do post, que enxerga todas as audiências em que a própria publicação está.
   * Contar por audiência daria a quem quisesse floodar um multiplicador pelo número de Squads em
   * que o post foi compartilhado (§130) — exatamente o que a T17.12 lista como risco de abuso.
   */
  countRecentCommentsBy(checkInId: string, authorUid: string, sinceMs: number): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS total
           FROM social_checkin_comments
          WHERE checkin_id = ? AND author_uid = ? AND created_at >= ?`,
      )
      .get(checkInId, authorUid, sinceMs) as { total: number };
    return row.total;
  }

  // ------------------------------------------------------------------ ciclo de vida (§43–§46/§70–§72)

  /**
   * As interações de uma pessoa **naquele** Squad (§43/§44/§45/§142/§144).
   *
   * Chamado quando alguém sai, é removido, ou desativa o Social. A participação era o consentimento
   * que autorizava aquela audiência; quando ela acaba, a participação social daquela pessoa naquele
   * Squad acaba junto — e um `rejoin` futuro não ressuscita nada (§46/§143), porque não há o que
   * ressuscitar.
   *
   * Não toca nada em `FRIEND` nem em outro Squad (§47): o recorte é `(group_id, actor)`.
   */
  purgeGroupInteractionsByActor(groupId: string, actorUid: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `DELETE FROM social_checkin_reactions
        WHERE audience_type = 'GROUP' AND group_id = ? AND reactor_uid = ?`,
    ).run(groupId, actorUid);
    db.prepare(
      `UPDATE social_checkin_comments
          SET deleted_at = ?
        WHERE audience_type = 'GROUP' AND group_id = ? AND author_uid = ? AND deleted_at IS NULL`,
    ).run(now, groupId, actorUid);
  }

  /**
   * As interações que existiam **por causa daquele compartilhamento** (§70/§71/§145).
   *
   * O autor tirou o próprio check-in do Squad: a conversa que acontecia ali perde o objeto. A
   * revogação de acesso já é imediata sem isto — a política exige o compartilhamento a cada leitura
   * —, e a limpeza existe para não deixar conteúdo inalcançável crescendo para sempre (§71).
   *
   * `FRIEND` e os outros Squads ficam intactos: o recorte é `(group_id, checkin_id)`.
   */
  purgeGroupInteractionsForShare(groupId: string, checkInId: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `DELETE FROM social_checkin_reactions
        WHERE audience_type = 'GROUP' AND group_id = ? AND checkin_id = ?`,
    ).run(groupId, checkInId);
    db.prepare(
      `UPDATE social_checkin_comments
          SET deleted_at = ?
        WHERE audience_type = 'GROUP' AND group_id = ? AND checkin_id = ? AND deleted_at IS NULL`,
    ).run(now, groupId, checkInId);
  }

  /**
   * Tudo o que pertencia à audiência daquele Squad (§72/§146).
   *
   * Chamado quando o Squad é excluído. O `ON DELETE CASCADE` da FK **não** cobre este caso: a
   * exclusão de Squad é soft (`status = 'DELETED'`, T17.11 §46), a linha de `social_groups`
   * continua existindo, e nenhum cascade dispara. Sem esta limpeza explícita, as interações
   * ficariam órfãs de um grupo que não responde mais.
   *
   * O que ela nunca alcança: `FRIEND`, outro Squad, e o `WorkoutCheckIn` de quem quer que seja
   * (§72/§146) — o recorte é `group_id`, e um check-in não tem essa coluna.
   */
  purgeGroupInteractions(groupId: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `DELETE FROM social_checkin_reactions WHERE audience_type = 'GROUP' AND group_id = ?`,
    ).run(groupId);
    db.prepare(
      `UPDATE social_checkin_comments
          SET deleted_at = ?
        WHERE audience_type = 'GROUP' AND group_id = ? AND deleted_at IS NULL`,
    ).run(now, groupId);
  }
}

/** `null` para `FRIEND`, o `groupId` para `GROUP` — a tradução usada em toda escrita (§27). */
function groupIdOf(audience: InteractionAudience): string | null {
  return audience.type === 'GROUP' ? audience.groupId : null;
}

/**
 * Os `checkInId` de uma página como parâmetros nomeados, mais `:viewer` e (quando `GROUP`)
 * `:groupId`.
 *
 * Nomeados e não posicionais porque as consultas já usam `:viewer` nas CTEs, e o better-sqlite3 não
 * aceita misturar posicional com nomeado na mesma instrução.
 */
function bindCheckInIds(
  checkInIds: readonly string[],
  viewerUid: string,
  audience: InteractionAudience,
): { placeholders: string; params: Record<string, unknown> } {
  const placeholders = checkInIds.map((_, index) => `@id${index}`).join(', ');
  const params: Record<string, unknown> = { viewer: viewerUid };
  checkInIds.forEach((id, index) => {
    params[`id${index}`] = id;
  });
  if (audience.type === 'GROUP') {
    params.groupId = audience.groupId;
  }
  return { placeholders, params };
}
