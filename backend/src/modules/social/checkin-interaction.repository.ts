import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { ReactionType } from './workout-checkin.contract';
import { VIEWER_SCOPE_CTE, interactionVisibleSql } from './workout-checkin.access-policy';

/** Uma contagem de reação já filtrada para o viewer (T17.9 §69/§70). */
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
 * Reações e comentários (T17.9, Etapas D e E).
 *
 * ## Toda leitura aqui é **por viewer**
 *
 * Não existe "a contagem de reações de um post". Existe "a contagem que este viewer pode ver"
 * (§69/§92). O cenário que obriga isso é concreto: A e B são amigos de C, A bloqueou B, e A reagiu
 * ao post de C. Quando B abre o Feed, o post de C precisa aparecer — B e C continuam amigos — mas
 * a participação de A não pode transparecer nem como **número**. Uma contagem global vazaria
 * exatamente a informação que o bloqueio existe para esconder: que aquelas duas pessoas estão no
 * mesmo lugar.
 *
 * O mesmo vale para comentários (§86/§92): o comentário de A no post de C some para B, sem ser
 * apagado para C. Bloqueio é autorização por viewer, nunca exclusão global (§117).
 *
 * A regra é uma só, e ela mora em `interactionVisibleSql` — não é reescrita aqui a cada consulta.
 *
 * ## Bounded, e sem N+1 (§131/§132)
 *
 * As agregações do Feed recebem a **página inteira** de `checkInId` e devolvem uma linha por
 * (post, tipo). Um feed de 20 itens custa três consultas, não sessenta.
 */
@Injectable()
export class CheckInInteractionRepository {
  constructor(private readonly sqlite: SqliteService) {}

  // ------------------------------------------------------------------ reações (§61–§70)

  /**
   * Adiciona ou **troca** a reação (§64/§66).
   *
   * `ON CONFLICT ... DO UPDATE` sobre a chave primária `(checkin_id, reactor_uid)`: trocar 🔥 por
   * 💪 atualiza a linha existente e nunca cria uma segunda. Duas requisições simultâneas da mesma
   * conta convergem — a garantia é do banco, e não da ordem em que dois `SELECT` aconteceram
   * (§63).
   */
  putReaction(checkInId: string, reactorUid: string, type: ReactionType, now: number): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_checkin_reactions (checkin_id, reactor_uid, type, created_at, updated_at)
              VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (checkin_id, reactor_uid)
         DO UPDATE SET type = excluded.type, updated_at = excluded.updated_at`,
      )
      .run(checkInId, reactorUid, type, now, now);
  }

  /** Remove a reação (§65). Idempotente: repetir converge, e o `changes` diz o que aconteceu. */
  removeReaction(checkInId: string, reactorUid: string): boolean {
    const result = this.sqlite.connection
      .prepare(`DELETE FROM social_checkin_reactions WHERE checkin_id = ? AND reactor_uid = ?`)
      .run(checkInId, reactorUid);
    return result.changes > 0;
  }

  /**
   * As contagens por tipo, **para este viewer** (§69/§70).
   *
   * Não devolve lista de quem reagiu (§70): isso exporia participação social de terceiros — quem é
   * amigo de quem, quem interage com quem — e cobraria um `JOIN` de perfil por reação.
   */
  countReactionsForCheckIns(viewerUid: string, checkInIds: readonly string[]): ReactionCountRow[] {
    if (checkInIds.length === 0) {
      return [];
    }
    const placeholders = checkInIds.map((_, index) => `@id${index}`).join(', ');
    const params: Record<string, string> = { viewer: viewerUid };
    checkInIds.forEach((id, index) => {
      params[`id${index}`] = id;
    });

    return this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
         SELECT r.checkin_id AS checkInId,
                r.type       AS type,
                COUNT(*)     AS total
           FROM social_checkin_reactions r
           JOIN social_workout_checkins c ON c.id = r.checkin_id
          WHERE r.checkin_id IN (${placeholders})
            AND ${interactionVisibleSql('r.reactor_uid', 'c.author_uid')}
          GROUP BY r.checkin_id, r.type`,
      )
      .all(params) as ReactionCountRow[];
  }

  /** A reação do próprio viewer em cada post da página (§70). */
  findViewerReactions(viewerUid: string, checkInIds: readonly string[]): Map<string, ReactionType> {
    const result = new Map<string, ReactionType>();
    if (checkInIds.length === 0) {
      return result;
    }
    const placeholders = checkInIds.map(() => '?').join(', ');
    const rows = this.sqlite.connection
      .prepare(
        `SELECT checkin_id AS checkInId, type
           FROM social_checkin_reactions
          WHERE reactor_uid = ? AND checkin_id IN (${placeholders})`,
      )
      .all(viewerUid, ...checkInIds) as Array<{ checkInId: string; type: ReactionType }>;

    for (const row of rows) {
      result.set(row.checkInId, row.type);
    }
    return result;
  }

  // ------------------------------------------------------------------ comentários (§74–§97)

  createComment(id: string, checkInId: string, authorUid: string, body: string, now: number): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_checkin_comments (id, checkin_id, author_uid, body, created_at, deleted_at)
         VALUES (?, ?, ?, ?, ?, NULL)`,
      )
      .run(id, checkInId, authorUid, body, now);
  }

  /**
   * A página de comentários visíveis para o viewer (§89/§90/§91).
   *
   * `ORDER BY created_at ASC, id ASC` — cronológica, porque é uma conversa (§91), com desempate
   * determinístico por identificador para que dois comentários no mesmo milissegundo tenham sempre
   * a mesma ordem. `LIMIT` sempre: não existe histórico infinito (§90).
   */
  listComments(viewerUid: string, checkInId: string, limit: number): CommentRow[] {
    return this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
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
            AND cm.deleted_at IS NULL
            AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
          ORDER BY cm.created_at ASC, cm.id ASC
          LIMIT :limit`,
      )
      .all({ viewer: viewerUid, checkInId, limit }) as CommentRow[];
  }

  /**
   * Quantos comentários **este viewer** vê em cada post da página (§92).
   *
   * Nunca a contagem global: um card dizendo "5 comentários" e uma lista com 3 seria o bloqueio
   * anunciando a si mesmo — "existem duas mensagens que você não pode ver" é informação sobre
   * quem foi bloqueado.
   */
  countCommentsForCheckIns(viewerUid: string, checkInIds: readonly string[]): Map<string, number> {
    const result = new Map<string, number>();
    if (checkInIds.length === 0) {
      return result;
    }
    const placeholders = checkInIds.map((_, index) => `@id${index}`).join(', ');
    const params: Record<string, string> = { viewer: viewerUid };
    checkInIds.forEach((id, index) => {
      params[`id${index}`] = id;
    });

    const rows = this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
         SELECT cm.checkin_id AS checkInId, COUNT(*) AS total
           FROM social_checkin_comments cm
           JOIN social_workout_checkins c ON c.id = cm.checkin_id
          WHERE cm.checkin_id IN (${placeholders})
            AND cm.deleted_at IS NULL
            AND ${interactionVisibleSql('cm.author_uid', 'c.author_uid')}
          GROUP BY cm.checkin_id`,
      )
      .all(params) as Array<{ checkInId: string; total: number }>;

    for (const row of rows) {
      result.set(row.checkInId, row.total);
    }
    return result;
  }

  /** Um comentário vivo, sem julgar visibilidade — quem julga é o serviço, com a política. */
  findComment(
    commentId: string,
  ): { id: string; checkInId: string; authorUid: string; deletedAt: number | null } | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT id, checkin_id AS checkInId, author_uid AS authorUid, deleted_at AS deletedAt
           FROM social_checkin_comments
          WHERE id = ? LIMIT 1`,
      )
      .get(commentId) as
      | { id: string; checkInId: string; authorUid: string; deletedAt: number | null }
      | undefined;
    return row ?? null;
  }

  /**
   * Exclusão de comentário (§93/§94/§97).
   *
   * Escrita **condicional** em `deleted_at IS NULL`: dois toques produzem uma transição e um
   * no-op, e a decisão sobrevive ao processo morrer. Quem pode apagar — o autor do comentário ou
   * o autor do check-in — é decidido pelo serviço; aqui a autoridade é passada como parâmetro
   * porque as duas identidades são igualmente legítimas e a consulta não deve escolher uma.
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
   * Quantos comentários esta conta deixou **neste** check-in dentro da janela (§158).
   *
   * Separado do teto global de comentários porque descreve outra coisa: 30 comentários espalhados
   * por dez publicações é conversa; 30 no mesmo post é enxurrada na publicação de uma pessoa só.
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
}
