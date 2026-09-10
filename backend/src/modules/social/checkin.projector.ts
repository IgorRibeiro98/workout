import { Injectable } from '@nestjs/common';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { SocialMediaRepository } from './social-media.repository';
import type { InteractionAudience } from './workout-checkin-context.resolver';
import type { ReactionType, WorkoutCheckInDto } from './workout-checkin.contract';

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
