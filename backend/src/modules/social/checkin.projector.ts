import { Injectable } from '@nestjs/common';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { SocialMediaRepository } from './social-media.repository';
import type { ReactionType, WorkoutCheckInDto } from './workout-checkin.contract';

/**
 * Uma linha pronta para virar `WorkoutCheckInDto`.
 *
 * É o menor denominador comum entre o Feed de amigos (`FeedRow`), o detalhe
 * (`AccessibleCheckIn`) e o feed de Squad (`GroupFeedRow`) — os três produzem exatamente estes
 * campos, e é por isso que os três podem passar pelo mesmo projetor.
 *
 * [canInteract] não é um campo do banco: ele é o resultado da política de acesso (T17.11 §70/§78).
 * `true` quando o viewer alcança a publicação por relação **direta** — ele é o autor, ou é amigo
 * dele; `false` quando o único caminho até ela é um Squad compartilhado.
 */
export interface ProjectableCheckIn {
  readonly checkInId: string;
  readonly authorUid: string;
  readonly authorSocialId: string;
  readonly authorDisplayName: string;
  readonly caption: string | null;
  readonly publishedAt: number;
  readonly canInteract: boolean;
}

/**
 * A **única** montagem de `WorkoutCheckInDto` do servidor (T17.9 §131/§132; T17.11 §50/§77).
 *
 * ## Por que ela virou um provider na T17.11
 *
 * Na T17.9 este código era um método privado de `WorkoutCheckInService`, e havia uma superfície
 * só que o chamava. A T17.11 acrescenta a segunda: o feed de um Squad é a **mesma** publicação
 * lida por outra audiência (§50), e um card montado por um segundo caminho divergiria do primeiro
 * no próximo campo novo — que é exatamente o que "não existe um segundo Feed authority" proíbe.
 *
 * Extrair para cá não muda o que o Feed de amigos devolve: ele passa a chamar o que já chamava,
 * pelo nome de fora em vez do de dentro.
 *
 * ## Por que quatro consultas, e não quatro por item (§121 da T17.11, §131 da T17.9)
 *
 * A tentação é resolver cada card sozinho: para cada check-in, buscar a foto, contar as reações,
 * descobrir a do viewer e contar os comentários. Uma página de 20 itens viraria 80 consultas, e o
 * custo cresceria com o tamanho da página.
 *
 * Aqui a página inteira vai junto em cada agregação: uma consulta de mídia, uma de contagem de
 * reações, uma da reação do viewer e uma de contagem de comentários. Quatro, independentemente de
 * a página ter 1 ou 50 itens.
 *
 * ## As contagens são **do viewer**, não do post (T17.9 §69/§92)
 *
 * `countReactionsForCheckIns` e `countCommentsForCheckIns` recebem o `viewerUid` e filtram por
 * ele. Não existe caminho aqui que produza um número global: o cenário de §69 — A reage ao post de
 * C, A bloqueou B, B lê o post de C — precisa que a participação de A não transpareça nem como
 * número, e um `COUNT(*)` sem viewer vazaria exatamente isso.
 *
 * ## E por que um item de Squad vem com interação zerada (T17.11 §70/§71/§144)
 *
 * Quando o único caminho até a publicação é um Squad, o viewer não pode reagir nem comentar, e
 * também não pode **listar** os comentários — `listComments` continua exigindo relação direta.
 * Devolver `commentCount: 7` para alguém que recebe `404` ao tentar abrir a lista seria um beco
 * sem saída na tela e, pior, uma contagem sobre uma conversa entre amigos do autor que este viewer
 * não faz parte. Um item de Squad carrega o que §69 lista — autor, quando publicou, legenda e
 * foto — e nada mais.
 */
@Injectable()
export class CheckInProjector {
  constructor(
    private readonly media: SocialMediaRepository,
    private readonly interactions: CheckInInteractionRepository,
  ) {}

  project(viewerUid: string, rows: readonly ProjectableCheckIn[]): WorkoutCheckInDto[] {
    if (rows.length === 0) {
      return [];
    }

    const ids = rows.map((row) => row.checkInId);
    const mediaById = new Map(
      this.media
        .findAttachedForCheckIns(ids)
        .map((item) => [
          item.checkInId,
          { mediaId: item.mediaId, width: item.width, height: item.height },
        ]),
    );

    // Só os itens com direito de interação entram nas agregações. Além de honrar §70, isso evita
    // trabalho: um feed de Squad entre não-amigos não faz nenhuma das três consultas.
    const interactableIds = rows.filter((row) => row.canInteract).map((row) => row.checkInId);

    const reactionTotals = new Map<string, Record<string, number>>();
    const viewerReactions = new Map<string, ReactionType>();
    const commentCounts = new Map<string, number>();

    if (interactableIds.length > 0) {
      for (const row of this.interactions.countReactionsForCheckIns(viewerUid, interactableIds)) {
        const bucket = reactionTotals.get(row.checkInId) ?? {};
        bucket[row.type] = row.total;
        reactionTotals.set(row.checkInId, bucket);
      }
      for (const [id, type] of this.interactions.findViewerReactions(viewerUid, interactableIds)) {
        viewerReactions.set(id, type);
      }
      for (const [id, count] of this.interactions.countCommentsForCheckIns(
        viewerUid,
        interactableIds,
      )) {
        commentCounts.set(id, count);
      }
    }

    return rows.map((row) => ({
      type: 'WORKOUT_CHECK_IN' as const,
      checkInId: row.checkInId,
      author: { socialId: row.authorSocialId, displayName: row.authorDisplayName },
      publishedAt: row.publishedAt,
      caption: row.caption,
      media: mediaById.get(row.checkInId) ?? null,
      reactions: reactionTotals.get(row.checkInId) ?? {},
      currentUserReaction: viewerReactions.get(row.checkInId) ?? null,
      commentCount: commentCounts.get(row.checkInId) ?? 0,
      isCurrentUser: row.authorUid === viewerUid,
      canInteract: row.canInteract,
    }));
  }
}
