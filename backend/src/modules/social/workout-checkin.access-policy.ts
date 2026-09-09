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

/**
 * O predicado de "este check-in chegou até o viewer por um **Squad**" (T17.11 §76/§77/§78).
 *
 * ```text
 * activeGroupShareAccess =
 *     existe Squad ACTIVE
 *     ∧ o check-in foi explicitamente compartilhado nele        (social_group_checkin_shares)
 *     ∧ o viewer é membro ativo dele
 *     ∧ o autor ainda é membro ativo dele                       (§61/§62/§63)
 *     ∧ ¬bloqueio entre viewer e autor, em nenhuma direção      (§33/§65)
 * ```
 *
 * ## Por que ele mora aqui, e não na consulta do feed de grupo
 *
 * §77 é explícito: `membro + share + ¬bloqueio` não pode ser reimplementado em Feed, Mídia e
 * Detalhe separadamente. São três superfícies fazendo a mesma pergunta, e três cópias é o desenho
 * em que, no dia de um ajuste, duas mudam e a terceira continua respondendo o dado de quem não
 * devia. Como `VIEWER_SCOPE_CTE`, isto é exportado como **texto** para que as três usem
 * literalmente a mesma definição.
 *
 * ## Por que o autor precisa continuar membro, se sair já apaga os shares
 *
 * Porque as duas coisas protegem contra falhas diferentes. `leave`/`remove` apagam as arestas
 * (§62/§63/§64), e é isso que garante que um `rejoin` futuro não ressuscite conteúdo antigo. A
 * junção com a participação do autor aqui é a **segunda** barreira: ela vale mesmo se uma escrita
 * de saída falhar pela metade, e vale para qualquer caminho futuro que remova alguém sem passar
 * por aquele código.
 *
 * ## O `mediaId` e o `checkInId` continuam não concedendo nada (§59/§80/§81)
 *
 * Este predicado é avaliado **contra as tabelas, a cada leitura**. Não existe token de grupo, não
 * existe contexto enviado pelo cliente que o satisfaça (§83), e um bloqueio superveniente revoga o
 * acesso na próxima requisição, sem cache a invalidar.
 *
 * A consulta que usa este fragmento precisa ter `viewer_blocked` no escopo — ou seja, precisa
 * carregar [VIEWER_SCOPE_CTE] — e passar `:viewer` como parâmetro nomeado.
 *
 * @param checkInIdColumn coluna com o id do check-in (ex.: `c.id`)
 * @param authorUidColumn coluna com o uid do autor do check-in (ex.: `c.author_uid`)
 */
export function groupShareVisibleSql(checkInIdColumn: string, authorUidColumn: string): string {
  return `(
    ${authorUidColumn} NOT IN (SELECT uid FROM viewer_blocked)
    AND EXISTS (
      SELECT 1
        FROM social_group_checkin_shares s
        JOIN social_groups g             ON g.id = s.group_id AND g.status = 'ACTIVE'
        JOIN social_group_memberships vm ON vm.group_id = s.group_id AND vm.member_uid = :viewer
        JOIN social_group_memberships am ON am.group_id = s.group_id
                                        AND am.member_uid = ${authorUidColumn}
       WHERE s.checkin_id = ${checkInIdColumn}
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
 *
 * ```text
 * WorkoutCheckInAccessContext
 * ├── SELF     — a própria publicação
 * ├── FRIEND   — amizade direta atual
 * └── GROUP    — um Squad compartilhado, e só ele
 * ```
 *
 * O contexto **não** vem do cliente (§83): ele é o resultado da mesma consulta que decidiu a
 * visibilidade. O `groupId` de uma rota diz onde procurar; quem responde "pode" é o banco.
 *
 * `canInteract` é o que a T17.11 acrescenta ao contrato de leitura, e ele existe por uma razão
 * específica (§70/§71): reagir e comentar continuam sendo autorizados **só** por relação direta.
 * Um mesmo check-in pode estar no Feed de amigos e em dois Squads, e permitir comentário a partir
 * do Squad criaria uma conversa com três audiências sobrepostas sobre o mesmo objeto — o problema
 * que só se resolve com comentários cientes de audiência, que esta fase não introduz em silêncio.
 */
export type CheckInAccessGrant = 'SELF' | 'FRIEND' | 'GROUP';

export interface AccessibleCheckIn extends VisibleCheckIn {
  readonly grant: CheckInAccessGrant;
  /** `true` só para `SELF` e `FRIEND` (§70/§73). Decidido no servidor, nunca pela tela (§72). */
  readonly canInteract: boolean;
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

  /**
   * O check-in [checkInId] **e** o caminho pelo qual o viewer o alcança (T17.11 §76/§82).
   *
   * ```text
   * canViewCheckIn = self ∨ directFriendAccess ∨ activeGroupShareAccess
   *                  sempre sujeito a: perfil ACTIVE dos dois lados ∧ ¬bloqueio
   * ```
   *
   * ## Por que ela é um método **novo**, e não uma mudança em [findVisibleCheckIn]
   *
   * Porque as duas respondem perguntas diferentes, e confundi-las seria o defeito que §171 lista
   * como bloqueante ("membro apenas de Squad ganhar comment/reaction sem política audience-aware").
   *
   * [findVisibleCheckIn] responde *"o viewer tem relação direta com esta publicação?"*, e é ela
   * que autoriza **mutação**: reação, comentário, exclusão de comentário e denúncia. Ela continua
   * exatamente como estava — nenhuma dessas superfícies mudou de comportamento com a T17.11, e é
   * isso que faz "membro só de Squad tenta reagir → recusado" ser verdade por construção, e não
   * por um `if` que alguém precisa lembrar de escrever.
   *
   * Este método responde *"o viewer pode **ler** esta publicação, e por quê?"*, e é usado pelo
   * detalhe do check-in e pelos bytes da foto — as duas superfícies que precisam funcionar quando
   * o acesso vem do Squad (§79/§82).
   *
   * ## A ordem de precedência do `grant`
   *
   * `SELF` antes de `FRIEND` antes de `GROUP`, e ela importa: um amigo que também está no mesmo
   * Squad continua podendo interagir (§73/§159), e resolver o contexto como `GROUP` só porque a
   * consulta encontrou o compartilhamento primeiro tiraria dele uma permissão que a Friendship já
   * concedia.
   */
  findAccessibleCheckIn(viewerUid: string, checkInId: string): AccessibleCheckIn | null {
    const row = this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
         SELECT c.id           AS checkInId,
                c.author_uid   AS authorUid,
                p.social_id    AS authorSocialId,
                p.display_name AS authorDisplayName,
                c.caption      AS caption,
                c.created_at   AS publishedAt,
                CASE WHEN EXISTS (SELECT 1 FROM eligible_authors ea WHERE ea.uid = c.author_uid)
                     THEN 1 ELSE 0 END AS direct
           FROM social_workout_checkins c
           JOIN social_profiles p ON p.owner_uid = c.author_uid
          WHERE c.id = :checkInId
            AND c.status = 'PUBLISHED'
            AND p.status = 'ACTIVE'
            AND EXISTS (SELECT 1 FROM social_profiles vp
                         WHERE vp.owner_uid = :viewer AND vp.status = 'ACTIVE')
            AND (
              EXISTS (SELECT 1 FROM eligible_authors ea WHERE ea.uid = c.author_uid)
              OR ${groupShareVisibleSql('c.id', 'c.author_uid')}
            )
          LIMIT 1`,
      )
      .get({ viewer: viewerUid, checkInId }) as (VisibleCheckIn & { direct: number }) | undefined;

    if (!row) {
      return null;
    }

    const direct = row.direct === 1;
    const grant: CheckInAccessGrant =
      row.authorUid === viewerUid ? 'SELF' : direct ? 'FRIEND' : 'GROUP';

    return {
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId: row.authorSocialId,
      authorDisplayName: row.authorDisplayName,
      caption: row.caption,
      publishedAt: row.publishedAt,
      grant,
      canInteract: direct,
    };
  }
}
