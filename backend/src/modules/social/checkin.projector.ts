import { Inject, Injectable } from '@nestjs/common';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { SocialMediaRepository } from './social-media.repository';
import { SocialProgressSettingsRepository } from './social-progress.repository';
import {
  SOCIAL_WORKOUT_FACTS_SOURCE,
  factsKey,
  type SocialWorkoutFactsSource,
} from './social-workout-facts.source';
import { projectWorkoutSummary, sharesAnyWorkoutDetail } from './social-workout-summary';
import type { InteractionAudience } from './workout-checkin-context.resolver';
import type {
  ReactionType,
  WorkoutCheckInDto,
  WorkoutSocialSummaryDto,
} from './workout-checkin.contract';
import { WorkoutCheckInRepository } from './workout-checkin.repository';

/**
 * Uma linha pronta para virar `WorkoutCheckInDto`.
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
 */
@Injectable()
export class CheckInProjector {
  constructor(
    private readonly media: SocialMediaRepository,
    private readonly interactions: CheckInInteractionRepository,
    private readonly checkIns: WorkoutCheckInRepository,
    private readonly progressSettings: SocialProgressSettingsRepository,
    @Inject(SOCIAL_WORKOUT_FACTS_SOURCE) private readonly workoutFacts: SocialWorkoutFactsSource,
  ) {}

  async project(
    viewerUid: string,
    rows: readonly ProjectableCheckIn[],
    audience: InteractionAudience,
  ): Promise<WorkoutCheckInDto[]> {
    if (rows.length === 0) {
      return [];
    }

    const ids = rows.map((row) => row.checkInId);
    const attachedMedia = await this.media.findAttachedForCheckIns(ids);
    const mediaById = new Map(
      attachedMedia.map((item) => [
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
      const reactionRows = await this.interactions.countReactionsForCheckIns(
        viewerUid,
        interactableIds,
        audience,
      );
      for (const row of reactionRows) {
        const bucket = reactionTotals.get(row.checkInId) ?? {};
        bucket[row.type] = row.total;
        reactionTotals.set(row.checkInId, bucket);
      }
      const reactionsMap = await this.interactions.findViewerReactions(
        viewerUid,
        interactableIds,
        audience,
      );
      for (const [id, type] of reactionsMap) {
        viewerReactions.set(id, type);
      }
      const commentsMap = await this.interactions.countCommentsForCheckIns(
        viewerUid,
        interactableIds,
        audience,
      );
      for (const [id, count] of commentsMap) {
        commentCounts.set(id, count);
      }
    }

    const summaries = await this.workoutSummaries(rows);

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
      // Por inclusão: sem resumo, a chave não existe no JSON (T19.H3 §35/§39).
      ...withSummary(summaries.get(row.checkInId)),
    }));
  }

  /**
   * O resumo de treino de cada publicação, **filtrado pelas escolhas atuais do autor**
   * (T19.H3 §28/§37/§38).
   *
   * Três leituras para o lote inteiro, nenhuma por item: as escolhas dos autores; a sessão de
   * origem das publicações cujo autor ligou algum detalhe; os fatos dessas sessões. Um Feed de 50
   * publicações de autores que não compartilham nada custa uma consulta, e não 50.
   *
   * **Quem vê** já foi decidido antes de chegar aqui — pela política de acesso do Feed, do Squad
   * ou do detalhe, que inclui o bloqueio. Este passo só decide **o que** do treino aparece, e a
   * resposta é a mesma para qualquer viewer que alcança a publicação: amigo, membro de Squad ou o
   * próprio autor (que assim vê exatamente o que os outros veem — §36).
   */
  private async workoutSummaries(
    rows: readonly ProjectableCheckIn[],
  ): Promise<Map<string, WorkoutSocialSummaryDto>> {
    const result = new Map<string, WorkoutSocialSummaryDto>();
    const authors = [...new Set(rows.map((row) => row.authorUid))];
    const flagsByAuthor = await this.progressSettings.findFlagsForOwners(authors);

    const sharing = rows.filter((row) => {
      const flags = flagsByAuthor.get(row.authorUid);
      return flags !== undefined && sharesAnyWorkoutDetail(flags);
    });
    if (sharing.length === 0) {
      return result;
    }

    const sources = await this.checkIns.findSourceSessions(sharing.map((row) => row.checkInId));
    const refs = [...sources.values()].map((source) => ({
      ownerUid: source.authorUid,
      sessionSyncId: source.sessionSyncId,
    }));
    const factsBySession = await this.workoutFacts.findSessions(refs);

    for (const row of sharing) {
      const source = sources.get(row.checkInId);
      // O autor da linha **e** o dono da sessão: uma publicação nunca empresta o treino de
      // outra conta, mesmo que um `source_session_sync_id` coincidisse.
      if (!source || source.authorUid !== row.authorUid) continue;
      const facts = factsBySession.get(factsKey(source.authorUid, source.sessionSyncId));
      const flags = flagsByAuthor.get(row.authorUid);
      if (!facts || !flags) continue;
      const summary = projectWorkoutSummary(facts, flags);
      if (summary) {
        result.set(row.checkInId, summary);
      }
    }
    return result;
  }
}

function withSummary(summary: WorkoutSocialSummaryDto | undefined): {
  workoutSummary?: WorkoutSocialSummaryDto;
} {
  return summary ? { workoutSummary: summary } : {};
}
