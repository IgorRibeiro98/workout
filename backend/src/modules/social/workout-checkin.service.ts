import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { BlockRepository } from './block.repository';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { CheckInProjector } from './checkin.projector';
import { SocialGroupRepository } from './social-group.repository';
import { SocialMediaService } from './social-media.service';
import { SocialRepository } from './social.repository';
import { WorkoutCheckInAccessPolicy, type VisibleCheckIn } from './workout-checkin.access-policy';
import {
  WorkoutCheckInContextResolver,
  type InteractionAudience,
} from './workout-checkin-context.resolver';
import type {
  CheckInCommentDto,
  CheckInCommentsDto,
  CreateWorkoutCheckInRequest,
  InteractionContextRequest,
  ReactionType,
  SocialFeedDto,
  WorkoutCheckInDto,
} from './workout-checkin.contract';
import { WorkoutCheckInErrors } from './workout-checkin.errors';
import {
  CHECKIN_CLOCK_SKEW_TOLERANCE_MS,
  CHECKIN_WINDOW_MS,
  COMMENTS_DEFAULT_LIMIT,
  COMMENTS_MAX_LIMIT,
  COMMENT_FLOOD_WINDOW_MS,
  FEED_DEFAULT_LIMIT,
  FEED_MAX_LIMIT,
  FEED_WINDOW_MS,
  MAX_COMMENTS_PER_CHECKIN_PER_WINDOW,
} from './workout-checkin.limits';
import { WorkoutCheckInRateLimiter } from './workout-checkin.rate-limit';
import { SocialContentRateLimiter } from './social-content.rate-limit';
import {
  WorkoutCheckInRepository,
  type FeedRow,
  type StoredWorkoutCheckIn,
} from './workout-checkin.repository';

@Injectable()
export class WorkoutCheckInService {
  constructor(
    private readonly repository: WorkoutCheckInRepository,
    private readonly socialRepository: SocialRepository,
    private readonly rateLimiter: WorkoutCheckInRateLimiter,
    private readonly interactions: CheckInInteractionRepository,
    private readonly projector: CheckInProjector,
    private readonly media: SocialMediaService,
    private readonly accessPolicy: WorkoutCheckInAccessPolicy,
    private readonly contextResolver: WorkoutCheckInContextResolver,
    private readonly groups: SocialGroupRepository,
    private readonly blocks: BlockRepository,
    private readonly contentRateLimiter: SocialContentRateLimiter,
    @Inject(CANONICAL_TRAINING_SOURCE)
    private readonly canonicalTraining: CanonicalTrainingSource,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  /**
   * `POST /v1/social/workout-checkins`.
   */
  async createCheckIn(
    callerUid: string,
    requestId: string,
    request: CreateWorkoutCheckInRequest,
  ): Promise<WorkoutCheckInDto> {
    if (!this.rateLimiter.tryAcquireCreate(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }

    const profile = await this.requireActiveProfile(callerUid);
    const { sessionSyncId, clientRequestId, caption, mediaId } = request;

    const byRequest = await this.repository.findByAuthorAndClientRequest(
      callerUid,
      clientRequestId,
    );
    if (byRequest && byRequest.sourceSessionSyncId !== sessionSyncId) {
      throw WorkoutCheckInErrors.requestConflict();
    }

    const bySession = await this.repository.findByAuthorAndSession(callerUid, sessionSyncId);
    if (bySession) {
      if (bySession.status === 'PUBLISHED') {
        return await this.projectSingle(
          callerUid,
          bySession.id,
          profile.socialId,
          profile.displayName,
        );
      }
      throw WorkoutCheckInErrors.alreadyExists();
    }

    const now = this.clock.now();
    await this.assertSessionEligible(callerUid, sessionSyncId, now);

    const created: StoredWorkoutCheckIn = {
      id: randomUUID(),
      authorUid: callerUid,
      sourceSessionSyncId: sessionSyncId,
      clientRequestId,
      status: 'PUBLISHED',
      caption: caption ?? null,
      createdAt: now,
      deletedAt: null,
    };

    const published = await this.insertOrResolveRace(created);

    if (mediaId) {
      const attached = await this.media.attachToCheckIn(
        mediaId,
        callerUid,
        sessionSyncId,
        published.id,
      );
      if (!attached) {
        if (published.id === created.id) {
          await this.repository.hardDelete(published.id);
        }
        throw WorkoutCheckInErrors.mediaNotFound();
      }
    }

    this.logger.info('social.checkin.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      status: 'PUBLISHED',
      hasCaption: published.caption !== null,
      hasMedia: Boolean(mediaId),
    });

    return await this.projectSingle(callerUid, published.id, profile.socialId, profile.displayName);
  }

  /**
   * `DELETE /v1/social/workout-checkins/{checkInId}`.
   */
  async deleteCheckIn(callerUid: string, requestId: string, checkInId: string): Promise<void> {
    const existing = await this.repository.findById(checkInId);
    if (!existing || existing.authorUid !== callerUid) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }

    const now = this.clock.now();
    const changed = await this.repository.transaction(async (client) => {
      const result = await this.repository.softDelete(checkInId, callerUid, now, client);
      if (result) {
        await this.media.revokeForCheckIn(checkInId, now, client);
      }
      return result;
    });

    this.logger.info('social.checkin.deleted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      status: changed ? 'DELETED' : 'ALREADY_DELETED',
    });
  }

  /**
   * `GET /v1/social/feed`.
   */
  async getFeed(callerUid: string, requestId: string, limit?: number): Promise<SocialFeedDto> {
    await this.requireActiveProfile(callerUid);

    const now = this.clock.now();
    const boundedLimit = Math.min(Math.max(limit ?? FEED_DEFAULT_LIMIT, 1), FEED_MAX_LIMIT);
    const rows = await this.repository.findFeed(callerUid, now - FEED_WINDOW_MS, boundedLimit);

    this.logger.info('social.feed.read', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      returnedCount: rows.length,
    });

    return { items: await this.enrich(callerUid, rows) };
  }

  /**
   * `GET /v1/social/workout-checkins/{checkInId}` — o detalhe (T17.9 §118).
   */
  async getCheckIn(
    callerUid: string,
    checkInId: string,
    context?: InteractionContextRequest,
  ): Promise<WorkoutCheckInDto> {
    await this.requireActiveProfile(callerUid);

    if (context) {
      const { audience, checkIn } = await this.contextResolver.resolve(
        callerUid,
        checkInId,
        context,
      );
      return await this.projectInAudience(callerUid, checkIn, audience);
    }

    const accessible = await this.accessPolicy.findAccessibleCheckIn(callerUid, checkInId);
    if (!accessible) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }
    const projected = await this.projector.project(
      callerUid,
      [
        {
          checkInId: accessible.checkInId,
          authorUid: accessible.authorUid,
          authorSocialId: accessible.authorSocialId,
          authorDisplayName: accessible.authorDisplayName,
          caption: accessible.caption,
          publishedAt: accessible.publishedAt,
          canInteract: accessible.canInteract,
        },
      ],
      { type: 'FRIEND' },
    );
    return projected[0];
  }

  // ================================================================== reações (§61–§73)

  /**
   * `PUT /v1/social/workout-checkins/{id}/reaction` (§66).
   */
  async putReaction(
    callerUid: string,
    requestId: string,
    checkInId: string,
    type: ReactionType,
    context?: InteractionContextRequest,
  ): Promise<WorkoutCheckInDto> {
    if (!this.contentRateLimiter.tryAcquireReaction(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);
    const { audience, checkIn } = await this.contextResolver.resolve(callerUid, checkInId, context);

    await this.interactions.putReaction(checkInId, callerUid, type, audience, this.clock.now());

    this.logger.info('social.reaction.changed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      type,
      audience: audience.type,
      operation: 'PUT',
    });

    return await this.projectInAudience(callerUid, checkIn, audience);
  }

  /**
   * `DELETE /v1/social/workout-checkins/{id}/reaction` (§65).
   */
  async removeReaction(
    callerUid: string,
    requestId: string,
    checkInId: string,
    context?: InteractionContextRequest,
  ): Promise<WorkoutCheckInDto> {
    if (!this.contentRateLimiter.tryAcquireReaction(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);
    const { audience, checkIn } = await this.contextResolver.resolve(callerUid, checkInId, context);

    await this.interactions.removeReaction(checkInId, callerUid, audience);

    this.logger.info('social.reaction.changed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      audience: audience.type,
      operation: 'DELETE',
    });

    return await this.projectInAudience(callerUid, checkIn, audience);
  }

  // ================================================================== comentários (§74–§98)

  /**
   * `GET /v1/social/workout-checkins/{id}/comments` (§89/§90/§91).
   */
  async listComments(
    callerUid: string,
    checkInId: string,
    limit?: number,
    context?: InteractionContextRequest,
  ): Promise<CheckInCommentsDto> {
    await this.requireActiveProfile(callerUid);
    const { audience, checkIn } = await this.contextResolver.resolve(callerUid, checkInId, context);

    const bounded = Math.min(Math.max(limit ?? COMMENTS_DEFAULT_LIMIT, 1), COMMENTS_MAX_LIMIT);
    const rows = await this.interactions.listComments(callerUid, checkInId, audience, bounded);

    const moderatesAudience = await this.moderatesGroupAudience(callerUid, audience);

    return {
      items: rows.map<CheckInCommentDto>((row) => ({
        commentId: row.commentId,
        author: { socialId: row.authorSocialId, displayName: row.authorDisplayName },
        body: row.body,
        createdAt: row.createdAt,
        isCurrentUser: row.authorUid === callerUid,
        canDelete:
          row.authorUid === callerUid || checkIn.authorUid === callerUid || moderatesAudience,
      })),
    };
  }

  /**
   * `POST /v1/social/workout-checkins/{id}/comments` (§81/§82).
   */
  async createComment(
    callerUid: string,
    requestId: string,
    checkInId: string,
    body: string,
    context?: InteractionContextRequest,
  ): Promise<CheckInCommentDto> {
    if (!this.contentRateLimiter.tryAcquireComment(callerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    const profile = await this.requireActiveProfile(callerUid);
    const { audience } = await this.contextResolver.resolve(callerUid, checkInId, context);

    const now = this.clock.now();
    const recent = await this.interactions.countRecentCommentsBy(
      checkInId,
      callerUid,
      now - COMMENT_FLOOD_WINDOW_MS,
    );
    if (recent >= MAX_COMMENTS_PER_CHECKIN_PER_WINDOW) {
      throw WorkoutCheckInErrors.rateLimited();
    }

    const commentId = randomUUID();
    await this.interactions.createComment(commentId, checkInId, callerUid, body, audience, now);

    this.logger.info(
      audience.type === 'GROUP' ? 'social.group.comment.created' : 'social.comment.created',
      {
        requestId,
        uidPrefix: uidPrefix(callerUid),
        bodyLength: body.length,
        audience: audience.type,
      },
    );

    return {
      commentId,
      author: { socialId: profile.socialId, displayName: profile.displayName },
      body,
      createdAt: now,
      isCurrentUser: true,
      canDelete: true,
    };
  }

  /**
   * `DELETE /v1/social/workout-checkins/{checkInId}/comments/{commentId}` (§93–§98).
   */
  async deleteComment(
    callerUid: string,
    requestId: string,
    checkInId: string,
    commentId: string,
  ): Promise<void> {
    await this.requireActiveProfile(callerUid);

    const comment = await this.interactions.findComment(commentId);
    if (!comment || comment.checkInId !== checkInId) {
      throw WorkoutCheckInErrors.commentNotFound();
    }

    const checkIn = await this.repository.findById(checkInId);
    const isCommentAuthor = comment.authorUid === callerUid;
    const isPostAuthor = checkIn?.authorUid === callerUid && checkIn.status === 'PUBLISHED';
    const moderatesAsGroupOwner =
      !isCommentAuthor &&
      !isPostAuthor &&
      comment.audienceType === 'GROUP' &&
      comment.groupId !== null &&
      (await this.moderatesGroupAudience(callerUid, { type: 'GROUP', groupId: comment.groupId })) &&
      !(await this.blocks.isBlockedBidirectional(callerUid, comment.authorUid));

    if (!isCommentAuthor && !isPostAuthor && !moderatesAsGroupOwner) {
      throw WorkoutCheckInErrors.commentNotFound();
    }

    const changed = await this.interactions.softDeleteComment(commentId, this.clock.now());

    this.logger.info(
      moderatesAsGroupOwner ? 'social.group.comment.moderated' : 'social.comment.deleted',
      {
        requestId,
        uidPrefix: uidPrefix(callerUid),
        status: changed ? 'DELETED' : 'ALREADY_DELETED',
        actor: isCommentAuthor ? 'COMMENT_AUTHOR' : isPostAuthor ? 'POST_AUTHOR' : 'GROUP_OWNER',
      },
    );
  }

  // ------------------------------------------------------------------ internas

  private async requireActiveProfile(callerUid: string) {
    const account = await this.socialRepository.find(callerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw WorkoutCheckInErrors.socialNotEnabled();
    }
    return account.profile;
  }

  private async assertSessionEligible(
    callerUid: string,
    sessionSyncId: string,
    now: number,
  ): Promise<void> {
    const session = await this.canonicalTraining.findSessionForCheckIn(callerUid, sessionSyncId);

    if (!session || session.deleted) {
      throw WorkoutCheckInErrors.sessionNotFound();
    }

    if (session.status !== 'COMPLETED') {
      throw WorkoutCheckInErrors.sessionNotCompleted();
    }

    const finishedAt = session.finishedAt;
    if (finishedAt === null) {
      throw WorkoutCheckInErrors.windowExpired();
    }
    if (finishedAt > now + CHECKIN_CLOCK_SKEW_TOLERANCE_MS) {
      throw WorkoutCheckInErrors.windowExpired();
    }
    if (now - finishedAt > CHECKIN_WINDOW_MS) {
      throw WorkoutCheckInErrors.windowExpired();
    }
  }

  private async insertOrResolveRace(item: StoredWorkoutCheckIn): Promise<StoredWorkoutCheckIn> {
    try {
      await this.repository.create(item);
      return item;
    } catch (error) {
      const code = (error as { code?: unknown }).code;
      if (code !== '23505' && (typeof code !== 'string' || !code.startsWith('SQLITE_CONSTRAINT'))) {
        throw error;
      }

      const winner = await this.repository.findByAuthorAndSession(
        item.authorUid,
        item.sourceSessionSyncId,
      );
      if (winner && winner.status === 'PUBLISHED') {
        return winner;
      }
      throw WorkoutCheckInErrors.unavailable('não foi possível publicar o check-in agora');
    }
  }

  private async enrich(viewerUid: string, rows: readonly FeedRow[]): Promise<WorkoutCheckInDto[]> {
    return await this.projector.project(
      viewerUid,
      rows.map((row) => ({
        checkInId: row.checkInId,
        authorUid: row.authorUid,
        authorSocialId: row.authorSocialId,
        authorDisplayName: row.authorDisplayName,
        caption: row.caption,
        publishedAt: row.publishedAt,
        canInteract: true,
      })),
      { type: 'FRIEND' },
    );
  }

  private async projectInAudience(
    viewerUid: string,
    checkIn: VisibleCheckIn,
    audience: InteractionAudience,
  ): Promise<WorkoutCheckInDto> {
    const projected = await this.projector.project(
      viewerUid,
      [
        {
          checkInId: checkIn.checkInId,
          authorUid: checkIn.authorUid,
          authorSocialId: checkIn.authorSocialId,
          authorDisplayName: checkIn.authorDisplayName,
          caption: checkIn.caption,
          publishedAt: checkIn.publishedAt,
          canInteract: true,
        },
      ],
      audience,
    );
    return projected[0];
  }

  private async moderatesGroupAudience(
    viewerUid: string,
    audience: InteractionAudience,
  ): Promise<boolean> {
    if (audience.type !== 'GROUP') {
      return false;
    }
    const membership = await this.groups.findActiveMembership(audience.groupId, viewerUid);
    return membership?.role === 'OWNER';
  }

  private async projectSingle(
    callerUid: string,
    checkInId: string,
    socialId: string,
    displayName: string,
  ): Promise<WorkoutCheckInDto> {
    const visible = await this.accessPolicy.findVisibleCheckIn(callerUid, checkInId);
    if (visible) {
      const enriched = await this.enrich(callerUid, [visible as FeedRow]);
      return enriched[0];
    }
    const stored = await this.repository.findById(checkInId);
    return {
      type: 'WORKOUT_CHECK_IN',
      checkInId,
      author: { socialId, displayName },
      publishedAt: stored?.createdAt ?? this.clock.now(),
      caption: stored?.caption ?? null,
      media: null,
      reactions: {},
      currentUserReaction: null,
      commentCount: 0,
      isCurrentUser: true,
      canInteract: true,
    };
  }
}
