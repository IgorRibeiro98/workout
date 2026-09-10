import { Inject, Injectable, Optional } from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { FriendshipRepository, type FriendProfileRow } from './friendship.repository';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialErrors } from './social.errors';
import { SocialRepository } from './social.repository';
import { localCalendarDate, DAY_MS } from './social-time';
import type { SocialActivityItemDto, SocialActivityResponse } from './social.contract';
import { BlockService } from './block.service';

/**
 * Serviço de projeção da atividade recente dos amigos (T17.4).
 */
@Injectable()
export class SocialActivityService {
  constructor(
    private readonly friendshipRepo: FriendshipRepository,
    private readonly socialRepo: SocialRepository,
    @Inject(CANONICAL_TRAINING_SOURCE)
    private readonly trainingSource: CanonicalTrainingSource,
    private readonly accessPolicy: SocialAccessPolicy,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    @Optional() private readonly blockService?: BlockService,
  ) {}

  async getActivity(principal: AuthenticatedPrincipal, requestId: string): Promise<SocialActivityResponse> {
    const viewerAccount = await this.socialRepo.find(principal.uid);
    if (!viewerAccount || viewerAccount.profile.status !== 'ACTIVE') {
      throw SocialErrors.notEnabled();
    }

    const viewerAccessView = {
      status: viewerAccount.profile.status,
      discoverability: viewerAccount.privacy.discoverability,
      friendRequestsEnabled: viewerAccount.privacy.friendRequestsEnabled,
      activitySharingEnabled: viewerAccount.privacy.activitySharingEnabled,
      activityTimeZoneId: viewerAccount.privacy.activityTimeZoneId,
      friendRankingParticipationEnabled: viewerAccount.privacy.friendRankingParticipationEnabled,
    };

    const activeFriends = await this.friendshipRepo.findActiveFriends(principal.uid);
    const eligibleFriends: FriendProfileRow[] = [];
    for (const friend of activeFriends) {
      if (
        this.accessPolicy.canViewFriendActivity(friend, viewerAccessView, true) &&
        !(await this.blockService?.isBlocked(principal.uid, friend.ownerUid))
      ) {
        eligibleFriends.push(friend);
      }
    }

    if (eligibleFriends.length === 0) {
      this.logger.info('social.activity.listed', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
        itemCount: 0,
      });
      return { items: [] };
    }

    const now = this.clock.now();
    // 16 dias de margem cobrem com folga os 14 dias civis em qualquer fuso horário mundial
    const cutoffMs = now - 16 * DAY_MS;
    const friendUids = eligibleFriends.map((f) => f.ownerUid);
    const workouts = await this.trainingSource.getCompletedWorkoutSummaries(friendUids, cutoffMs, now);

    const workoutsByUid = new Map<string, Array<{ startedAt: number }>>();
    for (const w of workouts) {
      let list = workoutsByUid.get(w.ownerUid);
      if (!list) {
        list = [];
        workoutsByUid.set(w.ownerUid, list);
      }
      list.push(w);
    }

    const items: SocialActivityItemDto[] = [];

    for (const friend of eligibleFriends) {
      const friendWorkouts = workoutsByUid.get(friend.ownerUid) || [];
      if (friendWorkouts.length === 0) continue;

      const tz = friend.activityTimeZoneId!;
      const todayUtc = localCalendarDate(now, tz);

      // Deduplicação estrita por dias passados (0..13)
      const seenDaysAgo = new Set<number>();

      for (const workout of friendWorkouts) {
        const workoutDateUtc = localCalendarDate(workout.startedAt, tz);
        const daysAgo = Math.round((todayUtc - workoutDateUtc) / DAY_MS);

        if (daysAgo >= 0 && daysAgo <= 13) {
          seenDaysAgo.add(daysAgo);
        }
      }

      for (const daysAgo of seenDaysAgo) {
        items.push({
          type: 'TRAINING_DAY',
          actor: {
            socialId: friend.socialId,
            displayName: friend.displayName,
          },
          daysAgo,
        });
      }
    }

    // Ordenação: daysAgo ASC, desempate por displayName ASC, depois socialId ASC
    items.sort((a, b) => {
      if (a.daysAgo !== b.daysAgo) {
        return a.daysAgo - b.daysAgo;
      }
      const nameCompare = a.actor.displayName.localeCompare(b.actor.displayName);
      if (nameCompare !== 0) {
        return nameCompare;
      }
      return a.actor.socialId.localeCompare(b.actor.socialId);
    });

    // Teto de 30 itens
    const cappedItems = items.slice(0, 30);

    this.logger.info('social.activity.listed', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      itemCount: cappedItems.length,
    });

    return { items: cappedItems };
  }
}
