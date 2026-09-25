import { Injectable, Optional } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import {
  PROGRESS_SHARING_CONTRACT_VERSION,
  type SocialFriendProfileDto,
  type SocialFriendProfileResponse,
  type SocialProgressSettingsDto,
  type SocialProgressSharingResponse,
} from './social-profile.contract';
import { SocialProfileErrors } from './social-profile.errors';
import type { UpdateProgressSharingRequest } from './social-profile.validator';
import { SocialProgressPrivacyFilter, SocialProgressProjector } from './social-progress.projector';
import {
  PROGRESS_SHARING_FLAGS,
  type ProgressSharingFlags,
  SocialProgressSettingsRepository,
  type StoredProgressSettings,
} from './social-progress.repository';
import { FriendshipErrors } from './friendship.errors';
import { FriendshipRepository, type FriendProfileRow } from './friendship.repository';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialErrors } from './social.errors';
import { BlockService } from './block.service';

/**
 * O caso de uso do perfil social enriquecido (T17.2).
 */
@Injectable()
export class SocialProfileService {
  constructor(
    private readonly friendships: FriendshipRepository,
    private readonly settings: SocialProgressSettingsRepository,
    private readonly projector: SocialProgressProjector,
    private readonly privacy: SocialProgressPrivacyFilter,
    private readonly policy: SocialAccessPolicy,
    private readonly logger: SparkLogger,
    @Optional() private readonly blockService?: BlockService,
  ) {}

  /**
   * `GET /v1/social/friends/:socialId/profile`.
   */
  async friendProfile(
    principal: AuthenticatedPrincipal,
    requestId: string,
    socialId: string,
  ): Promise<SocialFriendProfileResponse> {
    const viewer = await this.requireActiveProfile(principal);
    const target = await this.friendships.findProfileBySocialId(socialId);

    // Alvo inexistente e alvo desativado: a mesma resposta, e a mesma de "não somos amigos".
    if (!target) {
      throw SocialProfileErrors.friendProfileNotFound();
    }

    if (await this.blockService?.isBlocked(viewer.ownerUid, target.ownerUid)) {
      throw SocialProfileErrors.friendProfileNotFound();
    }

    const areFriends = await this.friendships.areFriends(viewer.ownerUid, target.ownerUid);
    if (!this.policy.canViewFriendProfile(target, viewer, areFriends)) {
      throw SocialProfileErrors.friendProfileNotFound();
    }
    if (target.ownerUid === viewer.ownerUid) {
      throw SocialProfileErrors.friendProfileNotFound();
    }

    const profile = await this.projectFor(target);
    this.log(requestId, principal.uid, 'social.friend_profile.read', {
      fieldCount: Object.keys(profile.sharedProgress).length,
    });
    return { profile };
  }

  /**
   * `GET /v1/social/me/profile-preview` — exatamente o que um amigo veria agora (§40).
   */
  async preview(
    principal: AuthenticatedPrincipal,
    requestId: string,
  ): Promise<SocialFriendProfileResponse> {
    const owner = await this.requireActiveProfile(principal);
    const profile = await this.projectFor(owner);

    this.log(requestId, principal.uid, 'social.profile_preview.read', {
      fieldCount: Object.keys(profile.sharedProgress).length,
    });
    return { profile };
  }

  /** `GET /v1/social/me/progress-sharing` — o que eu compartilho e o que está disponível. */
  async progressSharing(
    principal: AuthenticatedPrincipal,
    requestId: string,
  ): Promise<SocialProgressSharingResponse> {
    const owner = await this.requireActiveProfile(principal);
    this.log(requestId, principal.uid, 'social.progress_settings.read', {});
    return await this.sharingResponseFor(owner.ownerUid);
  }

  /**
   * `PATCH /v1/social/me/progress-sharing`.
   */
  async updateProgressSharing(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: UpdateProgressSharingRequest,
  ): Promise<SocialProgressSharingResponse> {
    const owner = await this.requireActiveProfile(principal);

    await this.settings.update(owner.ownerUid, { ...request, now: Date.now() });

    this.log(requestId, principal.uid, 'social.progress_settings.updated', {
      fieldCount: Object.keys(request).length,
    });
    return await this.sharingResponseFor(owner.ownerUid);
  }

  // --------------------------------------------------------------------------------- comum

  private async projectFor(target: FriendProfileRow): Promise<SocialFriendProfileDto> {
    const settings = await this.settings.find(target.ownerUid);
    const projection = await this.projector.project(target.ownerUid, settings, Date.now());

    return {
      socialId: target.socialId,
      displayName: target.displayName,
      sharedProgress: this.privacy.apply(projection, settings),
    };
  }

  private async sharingResponseFor(ownerUid: string): Promise<SocialProgressSharingResponse> {
    const settings = await this.settings.find(ownerUid);
    const projection = await this.projector.project(ownerUid, settings, Date.now());

    return {
      // T19.H5: o servidor declara o que conhece. Sem isto, um app novo não distinguia "o servidor
      // não tem o dado" de "o servidor não conhece o recurso" — e mostrava o segundo como o primeiro.
      contractVersion: PROGRESS_SHARING_CONTRACT_VERSION,
      settings: toSettingsDto(settings),
      availability: this.privacy.availabilityOf(projection),
      availabilityReasons: this.privacy.availabilityReasonsOf(projection),
    };
  }

  private async requireActiveProfile(principal: AuthenticatedPrincipal): Promise<FriendProfileRow> {
    const profile = await this.friendships.findProfileByOwnerUid(principal.uid);
    if (!profile) {
      throw SocialErrors.notEnabled();
    }
    if (profile.status !== 'ACTIVE') {
      throw FriendshipErrors.profileDisabled();
    }
    return profile;
  }

  private log(
    requestId: string,
    uid: string,
    event: string,
    fields: Record<string, unknown>,
  ): void {
    this.logger.info(event, { requestId, uidPrefix: uidPrefix(uid), ...fields });
  }
}

function toSettingsDto(settings: StoredProgressSettings): SocialProgressSettingsDto {
  // Os quinze interruptores pela mesma tabela do repositório: um novo não pode ser gravado e
  // esquecido na resposta ao dono.
  const flags = Object.fromEntries(
    PROGRESS_SHARING_FLAGS.map(([flag]) => [flag, settings[flag]]),
  ) as ProgressSharingFlags;
  return {
    ...flags,
    weekTimeZone: settings.weekTimeZone,
    consistency: settings.consistency
      ? {
          trackingStartedAtEpochDay: settings.consistency.trackingStartedAtEpochDay,
          weeklyGoals: settings.consistency.weeklyGoals.map((snapshot) => ({
            weekStartEpochDay: snapshot.weekStartEpochDay,
            goal: snapshot.goal,
          })),
        }
      : null,
    updatedAt: settings.updatedAt,
  };
}
