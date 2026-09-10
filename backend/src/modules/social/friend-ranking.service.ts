import { Inject, Injectable, Optional } from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { FriendshipRepository } from './friendship.repository';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialErrors } from './social.errors';
import { SocialRepository } from './social.repository';
import { DAY_MS } from './social-time';
import type { FriendRankingEntryDto, FriendRankingResponse } from './social.contract';
import { BlockService } from './block.service';

interface CandidateParticipant {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly displayName: string;
  readonly isCurrentUser: boolean;
}

/**
 * Serviço de cálculo do ranking contextual entre amigos (T17.4).
 */
@Injectable()
export class FriendRankingService {
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

  async getRanking(principal: AuthenticatedPrincipal, requestId: string): Promise<FriendRankingResponse> {
    const viewerAccount = await this.socialRepo.find(principal.uid);
    if (!viewerAccount || viewerAccount.profile.status !== 'ACTIVE') {
      throw SocialErrors.notEnabled();
    }

    if (!viewerAccount.privacy.friendRankingParticipationEnabled) {
      throw SocialErrors.rankingNotEnabled();
    }

    const participants: CandidateParticipant[] = [
      {
        ownerUid: principal.uid,
        socialId: viewerAccount.profile.socialId,
        displayName: viewerAccount.profile.displayName,
        isCurrentUser: true,
      },
    ];

    const activeFriends = await this.friendshipRepo.findActiveFriends(principal.uid);
    for (const friend of activeFriends) {
      if (
        this.accessPolicy.canParticipateInRanking(friend) &&
        !(await this.blockService?.isBlocked(principal.uid, friend.ownerUid))
      ) {
        participants.push({
          ownerUid: friend.ownerUid,
          socialId: friend.socialId,
          displayName: friend.displayName,
          isCurrentUser: false,
        });
      }
    }

    const now = this.clock.now();
    const windowStartMs = now - 7 * DAY_MS;
    const participantUids = participants.map((p) => p.ownerUid);

    const countsByUid = await this.trainingSource.getCompletedWorkoutCounts(
      participantUids,
      windowStartMs,
      now,
    );

    const scored = participants.map((p) => ({
      ...p,
      score: countsByUid.get(p.ownerUid) ?? 0,
    }));

    // Ordenação: score DESC, displayName ASC, socialId ASC
    scored.sort((a, b) => {
      if (b.score !== a.score) {
        return b.score - a.score;
      }
      const nameCompare = a.displayName.localeCompare(b.displayName);
      if (nameCompare !== 0) {
        return nameCompare;
      }
      return a.socialId.localeCompare(b.socialId);
    });

    // Competition ranking: 1, 1, 3...
    let currentRank = 1;
    let previousScore: number | null = null;
    const rankedEntries: FriendRankingEntryDto[] = scored.map((item, index) => {
      if (previousScore === null || item.score !== previousScore) {
        currentRank = index + 1;
        previousScore = item.score;
      }
      return {
        socialId: item.socialId,
        displayName: item.displayName,
        score: item.score,
        rank: currentRank,
        isCurrentUser: item.isCurrentUser,
      };
    });

    const cappedEntries = rankedEntries.slice(0, 50);
    const userInTop50 = cappedEntries.some((e) => e.isCurrentUser);
    const currentUserEntry = rankedEntries.find((e) => e.isCurrentUser);

    const finalEntries =
      userInTop50 || !currentUserEntry ? cappedEntries : [...cappedEntries, currentUserEntry];

    this.logger.info('social.rankings.listed', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      participantCount: scored.length,
      entryCount: finalEntries.length,
    });

    return {
      type: 'WORKOUTS_COMPLETED_LAST_7_DAYS',
      participantCount: scored.length,
      entries: finalEntries,
    };
  }
}
