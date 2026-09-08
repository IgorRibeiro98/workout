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
import { localCalendarDate, DAY_MS } from './social-time';
import type { SocialActivityItemDto, SocialActivityResponse } from './social.contract';
import { BlockService } from './block.service';

/**
 * Serviço de projeção da atividade recente dos amigos (T17.4).
 *
 * ## Regras de privacidade e arquitetura
 * - Somente amigos mútuos diretos com perfil ACTIVE.
 * - Amigos precisam ter consentido explicitamente com `activitySharingEnabled = true`
 *   e possuir fuso horário IANA válido.
 * - Projeção agregada em memória a partir de `sync_entities`: nenhum payload, carga,
 *   exercício ou timestamp exato de treino é retornado no DTO.
 * - Deduplicação por amigo por dia civil (dias 0 a 13).
 * - Sem N+1: consulta em lote das sessões no SQLite.
 * - Leitura sem efeitos colaterais.
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

  getActivity(principal: AuthenticatedPrincipal, requestId: string): SocialActivityResponse {
    const viewerAccount = this.socialRepo.find(principal.uid);
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

    const activeFriends = this.friendshipRepo.findActiveFriends(principal.uid);
    const eligibleFriends = activeFriends.filter(
      (friend) =>
        this.accessPolicy.canViewFriendActivity(friend, viewerAccessView, true) &&
        !this.blockService?.isBlocked(principal.uid, friend.ownerUid),
    );

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
    const workouts = this.trainingSource.getCompletedWorkoutSummaries(friendUids, cutoffMs, now);

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
