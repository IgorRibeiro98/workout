import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';

/**
 * A **única** definição de "quem enxerga o quê" no Feed (T17.9 §129/§130).
 *
 * ## Por que ela precisou nascer agora
 *
 * Na T17.8 havia uma superfície só — o Feed — e a regra vivia dentro daquela consulta. A T17.9
 * acrescenta cinco: detalhe do check-in, bytes da foto, reações, comentários e denúncia de
 * conteúdo. Cada uma precisa responder exatamente a mesma pergunta, e reimplementar
 * `amigo ∧ ativo ∧ ¬bloqueado` em cinco controllers é o desenho em que, no dia de um ajuste, quatro
 * mudam e a quinta continua respondendo o dado de quem não devia — normalmente a mais recente, que
 * é a menos testada. §130 chama isso de bloqueante arquitetural, e está certo.
 *
 * ## A regra, em uma frase
 *
 * ```text
 * autores visíveis = { viewer }  ∪  { amigos diretos atuais ∧ perfil ACTIVE ∧ ¬bloqueado }
 * check-in visível  = autor visível ∧ status = 'PUBLISHED' ∧ viewer com perfil ACTIVE
 * ```
 *
 * Avaliada **a cada leitura**, contra as tabelas, e não contra nada que o aparelho tenha guardado.
 * É isso que faz `unfriend`, bloqueio e desativação do Social serem revogações imediatas: não há
 * cache a invalidar, porque não há cache.
 *
 * ## Por que SQL, e não um objeto que carrega listas
 *
 * Porque a alternativa — carregar os check-ins e filtrar em JavaScript — é o desenho que, no dia
 * de um bug no filtro, **já leu** o dado de quem não devia. Aqui a audiência é uma CTE na cláusula
 * `FROM`: não existe caminho em que uma linha inelegível chegue a ser materializada.
 *
 * ## O que ela não decide
 *
 * Propriedade. O `uid` sai sempre do token verificado pelo `BearerAuthGuard`, e nenhuma consulta
 * daqui substitui isso: `:viewer` é sempre o dono autenticado.
 */

/**
 * As CTEs que definem o escopo do viewer. Prefixo de toda consulta desta fase.
 *
 * Exportado como **texto** de propósito: é assim que o Feed, o detalhe, a mídia, as reações, os
 * comentários e a denúncia usam literalmente a mesma definição, em vez de cinco cópias que
 * divergem. Todas as consultas passam `:viewer` como parâmetro nomeado.
 */
export const VIEWER_SCOPE_CTE = `
  viewer_blocked AS (
    SELECT blocked_uid AS uid FROM social_blocks WHERE blocker_uid = :viewer
    UNION
    SELECT blocker_uid AS uid FROM social_blocks WHERE blocked_uid = :viewer
  ),
  viewer_friends AS (
    SELECT CASE WHEN user_a_uid = :viewer THEN user_b_uid ELSE user_a_uid END AS uid
      FROM friendships
     WHERE user_a_uid = :viewer OR user_b_uid = :viewer
  ),
  eligible_authors AS (
    SELECT :viewer AS uid
    UNION
    SELECT uid FROM viewer_friends WHERE uid NOT IN (SELECT uid FROM viewer_blocked)
  )`;

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

/** O que a política devolve quando o check-in é visível. Nada de conteúdo de treino. */
export interface VisibleCheckIn {
  readonly checkInId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly caption: string | null;
  readonly publishedAt: number;
}

@Injectable()
export class WorkoutCheckInAccessPolicy {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * O check-in [checkInId], **se** o viewer pode vê-lo agora.
   *
   * `null` cobre todos os motivos com a mesma resposta: não existe, foi excluído, é de um
   * não-amigo, é de alguém em bloqueio, o autor desativou o Social — e o chamador devolve `404`
   * para os cinco (§51). Distinguir qualquer um transformaria a rota em um oráculo de existência:
   * bastaria comparar as respostas para descobrir que um `checkInId` existe mas pertence a alguém
   * que não quer ser visto.
   *
   * O viewer também precisa estar `ACTIVE` — quem desativou o Social parou de participar do
   * domínio social, inclusive como leitor. Isso já é verificado antes, no serviço, e a cláusula
   * aqui é a segunda barreira que não depende da ordem em que alguém escreveu o serviço.
   */
  findVisibleCheckIn(viewerUid: string, checkInId: string): VisibleCheckIn | null {
    const row = this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
         SELECT c.id           AS checkInId,
                c.author_uid   AS authorUid,
                p.social_id    AS authorSocialId,
                p.display_name AS authorDisplayName,
                c.caption      AS caption,
                c.created_at   AS publishedAt
           FROM social_workout_checkins c
           JOIN eligible_authors ea ON ea.uid = c.author_uid
           JOIN social_profiles p   ON p.owner_uid = c.author_uid
          WHERE c.id = :checkInId
            AND c.status = 'PUBLISHED'
            AND p.status = 'ACTIVE'
            AND EXISTS (SELECT 1 FROM social_profiles vp
                         WHERE vp.owner_uid = :viewer AND vp.status = 'ACTIVE')
          LIMIT 1`,
      )
      .get({ viewer: viewerUid, checkInId }) as VisibleCheckIn | undefined;

    return row ?? null;
  }
}
