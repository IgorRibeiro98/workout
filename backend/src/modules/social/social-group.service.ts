import { Inject, Injectable, Optional } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { PoolClient } from 'pg';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { BlockRepository } from './block.repository';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { CheckInProjector, type ProjectableCheckIn } from './checkin.projector';
import { FriendshipRepository } from './friendship.repository';
import { NotificationService } from './notification.service';
import type {
  CheckInGroupSharesDto,
  CreateGroupInvitationRequest,
  CreateSocialGroupRequest,
  SocialGroupDetailDto,
  SocialGroupFeedDto,
  SocialGroupFeedItemDto,
  SocialGroupInvitationDto,
  SocialGroupInvitationListDto,
  SocialGroupListDto,
  SocialGroupMemberDto,
  SocialGroupMembersDto,
  SocialGroupShareDto,
  SocialGroupSummaryDto,
} from './social-group.contract';
import { SocialGroupErrors } from './social-group.errors';
import {
  SOCIAL_GROUP_FEED,
  SOCIAL_GROUP_INVITATION_TTL_MS,
  SOCIAL_GROUP_LIST_PAGE,
  SOCIAL_GROUP_MAX_MEMBERS,
  SOCIAL_GROUP_MAX_MEMBERSHIPS,
  SOCIAL_GROUP_MAX_OWNED,
  SOCIAL_GROUP_MAX_PENDING_INVITATIONS,
  SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN,
} from './social-group.limits';
import { SocialGroupRateLimiter } from './social-group.rate-limit';
import {
  SocialGroupRepository,
  type GroupFeedRow,
  type StoredGroupInvitation,
  type StoredSocialGroup,
} from './social-group.repository';
import { SocialRepository } from './social.repository';
import { WorkoutCheckInRepository } from './workout-checkin.repository';

@Injectable()
export class SocialGroupService {
  constructor(
    private readonly repository: SocialGroupRepository,
    private readonly socialRepository: SocialRepository,
    private readonly friendships: FriendshipRepository,
    private readonly blocks: BlockRepository,
    private readonly checkIns: WorkoutCheckInRepository,
    private readonly interactions: CheckInInteractionRepository,
    private readonly projector: CheckInProjector,
    private readonly rateLimiter: SocialGroupRateLimiter,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    @Optional() private readonly notifications?: NotificationService,
  ) {}

  // ================================================================== criação e leitura

  /**
   * `POST /v1/social/groups` (§16/§17/§18).
   */
  async createGroup(
    callerUid: string,
    requestId: string,
    request: CreateSocialGroupRequest,
  ): Promise<SocialGroupSummaryDto> {
    await this.requireActiveProfile(callerUid);

    const existing = await this.repository.findGroupByClientRequest(
      callerUid,
      request.clientRequestId,
    );
    if (existing && existing.status === 'ACTIVE') {
      if (existing.name !== request.name) {
        throw SocialGroupErrors.idempotencyConflict(
          'este clientRequestId já criou um squad com outro nome',
        );
      }
      return await this.summaryOf(existing, 'OWNER');
    }

    if (!this.rateLimiter.tryAcquireCreate(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }

    if ((await this.repository.countOwnedActiveGroups(callerUid)) >= SOCIAL_GROUP_MAX_OWNED) {
      throw SocialGroupErrors.ownedLimitReached(SOCIAL_GROUP_MAX_OWNED);
    }
    if ((await this.repository.countActiveMemberships(callerUid)) >= SOCIAL_GROUP_MAX_MEMBERSHIPS) {
      throw SocialGroupErrors.membershipLimitReached(SOCIAL_GROUP_MAX_MEMBERSHIPS);
    }

    const now = this.clock.now();
    const group: StoredSocialGroup = {
      id: randomUUID(),
      ownerUid: callerUid,
      name: request.name,
      status: 'ACTIVE',
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      clientRequestId: request.clientRequestId,
    };

    await this.repository.transaction(async (client) => {
      await this.repository.createGroup(group, client);
      await this.repository.createMembership(
        {
          id: randomUUID(),
          groupId: group.id,
          memberUid: callerUid,
          role: 'OWNER',
          joinedAt: now,
        },
        client,
      );
    });

    this.logger.info('social.group.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });

    return { groupId: group.id, name: group.name, memberCount: 1, role: 'OWNER', createdAt: now };
  }

  /** `GET /v1/social/groups` (§131/§132). Só as participações **ativas** do próprio viewer. */
  async listGroups(callerUid: string): Promise<SocialGroupListDto> {
    await this.requireActiveProfile(callerUid);
    const rows = await this.repository.listGroupsForMember(
      callerUid,
      SOCIAL_GROUP_LIST_PAGE.maxLimit,
    );
    return {
      items: rows.map<SocialGroupSummaryDto>((row) => ({
        groupId: row.groupId,
        name: row.name,
        memberCount: row.memberCount,
        role: row.role,
        createdAt: row.createdAt,
      })),
    };
  }

  /** `GET /v1/social/groups/{groupId}` (§135). Exige participação — ter o `groupId` não basta. */
  async getGroup(callerUid: string, groupId: string): Promise<SocialGroupDetailDto> {
    await this.requireActiveProfile(callerUid);
    const { group, membership } = await this.requireMembership(callerUid, groupId);

    const memberCount = await this.repository.countMembers(group.id);
    const pendingInvitationCount =
      membership.role === 'OWNER' ? await this.repository.countPendingInvitations(group.id) : null;

    return {
      groupId: group.id,
      name: group.name,
      memberCount,
      role: membership.role,
      createdAt: group.createdAt,
      pendingInvitationCount,
    };
  }

  /**
   * `GET /v1/social/groups/{groupId}/members` (§34/§35/§36/§136).
   */
  async listMembers(callerUid: string, groupId: string): Promise<SocialGroupMembersDto> {
    await this.requireActiveProfile(callerUid);
    await this.requireMembership(callerUid, groupId);

    const rows = await this.repository.listMembers(groupId, callerUid);
    return {
      items: rows.map<SocialGroupMemberDto>((row) => {
        const blocked = row.blocked === 1;
        return {
          membershipId: row.membershipId,
          role: row.role,
          joinedAt: row.joinedAt,
          available: !blocked,
          socialId: blocked ? null : row.socialId,
          displayName: blocked ? null : row.displayName,
          isCurrentUser: row.memberUid === callerUid,
        };
      }),
    };
  }

  // ================================================================== convites

  /**
   * `POST /v1/social/groups/{groupId}/invitations` (§22–§28).
   */
  async invite(
    callerUid: string,
    requestId: string,
    groupId: string,
    request: CreateGroupInvitationRequest,
  ): Promise<SocialGroupInvitationDto> {
    await this.requireActiveProfile(callerUid);
    const { group, membership } = await this.requireMembership(callerUid, groupId);
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode convidar');
    }

    await this.sweepExpiredInvitations();

    const target = await this.friendships.findProfileBySocialId(request.socialId);
    if (
      !target ||
      target.status !== 'ACTIVE' ||
      target.ownerUid === callerUid ||
      !(await this.friendships.areFriends(callerUid, target.ownerUid)) ||
      (await this.blocks.isBlockedBidirectional(callerUid, target.ownerUid))
    ) {
      throw SocialGroupErrors.inviteNotAllowed();
    }

    const byRequest = await this.repository.findInvitationByClientRequest(
      groupId,
      request.clientRequestId,
    );
    if (byRequest) {
      if (byRequest.recipientUid !== target.ownerUid) {
        throw SocialGroupErrors.idempotencyConflict(
          'este clientRequestId já convidou outra pessoa para este squad',
        );
      }
      return await this.invitationDtoOf(byRequest, group, callerUid);
    }

    if (!this.rateLimiter.tryAcquireInvite(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }

    if (await this.repository.findActiveMembership(groupId, target.ownerUid)) {
      throw SocialGroupErrors.alreadyMember();
    }
    if ((await this.repository.countMembers(groupId)) >= SOCIAL_GROUP_MAX_MEMBERS) {
      throw SocialGroupErrors.full(SOCIAL_GROUP_MAX_MEMBERS);
    }

    const pending = await this.repository.findPendingInvitation(groupId, target.ownerUid);
    if (pending) {
      return await this.invitationDtoOf(pending, group, callerUid);
    }
    if (
      (await this.repository.countPendingInvitations(groupId)) >=
      SOCIAL_GROUP_MAX_PENDING_INVITATIONS
    ) {
      throw SocialGroupErrors.inviteLimitReached(SOCIAL_GROUP_MAX_PENDING_INVITATIONS);
    }

    const now = this.clock.now();
    const invitation: StoredGroupInvitation = {
      id: randomUUID(),
      groupId,
      senderUid: callerUid,
      recipientUid: target.ownerUid,
      status: 'PENDING',
      createdAt: now,
      expiresAt: now + SOCIAL_GROUP_INVITATION_TTL_MS,
      respondedAt: null,
      clientRequestId: request.clientRequestId,
    };

    await this.repository.transaction(async (client) => {
      await this.repository.createInvitation(invitation, client);
      await this.notifications?.enqueueGroupInvitationReceived({
        invitationId: invitation.id,
        recipientUid: invitation.recipientUid,
        expiresAt: invitation.expiresAt,
      });
    });

    this.logger.info('social.group.invitation.created', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });

    return await this.invitationDtoOf(invitation, group, callerUid);
  }

  /**
   * Marca no banco todo convite pendente que já venceu (T17.13.1 §27/§28).
   */
  private async sweepExpiredInvitations(): Promise<void> {
    const changed = await this.repository.expirePendingInvitations(this.clock.now());
    if (changed > 0) {
      this.logger.info('social.group.invitation.expired', { count: changed });
    }
  }

  /** `GET /v1/social/groups/invitations` (§138/§139). Só os pendentes, e só os do próprio viewer. */
  async listInvitations(callerUid: string): Promise<SocialGroupInvitationListDto> {
    await this.requireActiveProfile(callerUid);
    await this.sweepExpiredInvitations();
    const now = this.clock.now();
    const rows = await this.repository.listInvitationsForRecipient(
      callerUid,
      SOCIAL_GROUP_LIST_PAGE.maxLimit,
    );

    return {
      items: rows
        .filter((row) => now < row.expiresAt)
        .map<SocialGroupInvitationDto>((row) => ({
          invitationId: row.invitationId,
          groupId: row.groupId,
          groupName: row.groupName,
          memberCount: row.memberCount,
          inviterSocialId: row.blocked === 1 ? null : row.inviterSocialId,
          inviterDisplayName: row.blocked === 1 ? null : row.inviterDisplayName,
          status: 'PENDING',
          createdAt: row.createdAt,
          expiresAt: row.expiresAt,
        })),
    };
  }

  /**
   * `POST /v1/social/group-invitations/{invitationId}/accept` (§29/§30/§31).
   */
  async acceptInvitation(
    callerUid: string,
    requestId: string,
    invitationId: string,
  ): Promise<SocialGroupSummaryDto> {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);

    await this.sweepExpiredInvitations();

    const invitation = await this.repository.findInvitation(invitationId);
    const now = this.clock.now();
    if (
      !invitation ||
      invitation.recipientUid !== callerUid ||
      invitation.status !== 'PENDING' ||
      now >= invitation.expiresAt
    ) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    const group = await this.repository.findGroup(invitation.groupId);
    if (!group || group.status !== 'ACTIVE') {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    if (
      !(await this.friendships.areFriends(callerUid, invitation.senderUid)) ||
      (await this.blocks.isBlockedBidirectional(callerUid, invitation.senderUid))
    ) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    const already = await this.repository.findActiveMembership(group.id, callerUid);
    if (already) {
      await this.repository.resolveInvitation(invitationId, 'ACCEPTED', now);
      return await this.summaryOf(group, already.role);
    }

    if ((await this.repository.countMembers(group.id)) >= SOCIAL_GROUP_MAX_MEMBERS) {
      throw SocialGroupErrors.full(SOCIAL_GROUP_MAX_MEMBERS);
    }
    if ((await this.repository.countActiveMemberships(callerUid)) >= SOCIAL_GROUP_MAX_MEMBERSHIPS) {
      throw SocialGroupErrors.membershipLimitReached(SOCIAL_GROUP_MAX_MEMBERSHIPS);
    }

    const created = await this.repository.transaction(async (client) => {
      if (!(await this.repository.resolveInvitation(invitationId, 'ACCEPTED', now, client))) {
        return false;
      }
      await this.repository.createMembership(
        {
          id: randomUUID(),
          groupId: group.id,
          memberUid: callerUid,
          role: 'MEMBER',
          joinedAt: now,
        },
        client,
      );
      return true;
    });

    if (!created) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    this.logger.info('social.group.invitation.accepted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });

    return await this.summaryOf(group, 'MEMBER');
  }

  /** `POST /v1/social/group-invitations/{invitationId}/decline`. Só o destinatário. Idempotente. */
  async declineInvitation(
    callerUid: string,
    requestId: string,
    invitationId: string,
  ): Promise<void> {
    await this.respondToInvitation(callerUid, requestId, invitationId, 'DECLINED');
  }

  /**
   * `POST /v1/social/group-invitations/{invitationId}/cancel`. Só quem enviou.
   */
  async cancelInvitation(
    callerUid: string,
    requestId: string,
    invitationId: string,
  ): Promise<void> {
    await this.respondToInvitation(callerUid, requestId, invitationId, 'CANCELLED');
  }

  // ================================================================== composição

  /**
   * `POST /v1/social/groups/{groupId}/leave` (§38/§39/§44/§62).
   */
  async leaveGroup(callerUid: string, requestId: string, groupId: string): Promise<void> {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);

    const group = await this.repository.findGroup(groupId);
    const membership =
      group?.status === 'ACTIVE'
        ? await this.repository.findActiveMembership(groupId, callerUid)
        : null;

    if (!group || group.status !== 'ACTIVE' || !membership) {
      return;
    }
    if (membership.role === 'OWNER') {
      throw SocialGroupErrors.ownerActionRequired(
        'transfira a posse ou exclua o squad antes de sair',
      );
    }

    const now = this.clock.now();
    await this.repository.transaction(async (client) => {
      await this.purgeMemberFootprint(groupId, callerUid, now, client);
      await this.repository.deleteMembership(groupId, callerUid, client);
    });

    this.logger.info('social.group.member.left', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  /**
   * `DELETE /v1/social/groups/{groupId}/members/{membershipId}` (§42/§43/§44/§63).
   */
  async removeMember(
    callerUid: string,
    requestId: string,
    groupId: string,
    membershipId: string,
  ): Promise<void> {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);
    const { membership } = await this.requireMembership(callerUid, groupId);
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode remover participantes');
    }

    const target = await this.repository.findMembershipById(groupId, membershipId);
    if (!target) {
      return;
    }
    if (target.memberUid === callerUid) {
      throw SocialGroupErrors.ownerActionRequired(
        'o dono não pode remover a si mesmo; transfira a posse ou exclua o squad',
      );
    }

    const now = this.clock.now();
    await this.repository.transaction(async (client) => {
      await this.purgeMemberFootprint(groupId, target.memberUid, now, client);
      await this.repository.deleteMembership(groupId, target.memberUid, client);
    });

    this.logger.info('social.group.member.removed', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  /**
   * `POST /v1/social/groups/{groupId}/transfer-ownership` (§40/§41/§150).
   */
  async transferOwnership(
    callerUid: string,
    requestId: string,
    groupId: string,
    membershipId: string,
  ): Promise<void> {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);
    const { group, membership } = await this.requireMembership(callerUid, groupId);
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode transferir a posse');
    }

    const target = await this.repository.findMembershipById(groupId, membershipId);
    if (!target || target.memberUid === callerUid) {
      throw SocialGroupErrors.memberNotFound();
    }

    const now = this.clock.now();
    await this.repository.transaction(async (client) => {
      await this.repository.updateMembershipRole(membership.id, 'MEMBER', client);
      await this.repository.updateMembershipRole(target.id, 'OWNER', client);
      await this.repository.updateGroupOwner(group.id, target.memberUid, now, client);
    });

    this.logger.info('social.group.ownership.transferred', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  /**
   * `DELETE /v1/social/groups/{groupId}` (§46/§47/§48/§49).
   */
  async deleteGroup(callerUid: string, requestId: string, groupId: string): Promise<void> {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);

    const group = await this.repository.findGroup(groupId);
    if (!group || group.status !== 'ACTIVE') {
      return;
    }
    const membership = await this.repository.findActiveMembership(groupId, callerUid);
    if (!membership) {
      throw SocialGroupErrors.notFound();
    }
    if (membership.role !== 'OWNER') {
      throw SocialGroupErrors.forbidden('apenas o dono do squad pode excluí-lo');
    }

    const now = this.clock.now();
    await this.repository.transaction(async (client) => {
      await this.repository.markGroupDeleted(groupId, callerUid, now, client);
      await this.repository.purgeGroupContext(groupId, now, client);
      await this.interactions.purgeGroupInteractions(groupId, now, client);
    });

    this.logger.info('social.group.deleted', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
    });
  }

  // ================================================================== feed

  /**
   * `POST /v1/social/groups/{groupId}/checkins/{checkInId}` (§51–§58/§68).
   */
  async shareCheckIn(
    callerUid: string,
    requestId: string,
    groupId: string,
    checkInId: string,
  ): Promise<SocialGroupShareDto> {
    if (!this.rateLimiter.tryAcquireShare(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);
    await this.requireMembership(callerUid, groupId);

    const checkIn = await this.checkIns.findById(checkInId);
    if (!checkIn || checkIn.authorUid !== callerUid || checkIn.status !== 'PUBLISHED') {
      throw SocialGroupErrors.checkInNotFound();
    }

    const existing = await this.repository.findShare(groupId, checkInId);
    if (!existing) {
      if (
        (await this.repository.countSharesForCheckIn(checkInId)) >=
        SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN
      ) {
        throw SocialGroupErrors.shareLimitReached(SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN);
      }
      const now = this.clock.now();
      try {
        await this.repository.createShare({
          id: randomUUID(),
          groupId,
          checkInId,
          authorUid: callerUid,
          createdAt: now,
        });
      } catch (error) {
        const code = (error as { code?: unknown }).code;
        if (
          code !== '23505' &&
          (typeof code !== 'string' || !code.startsWith('SQLITE_CONSTRAINT'))
        ) {
          throw error;
        }
        if (!(await this.repository.findShare(groupId, checkInId))) {
          throw SocialGroupErrors.unavailable('não foi possível compartilhar agora');
        }
      }

      this.logger.info('social.group.checkin_shared', {
        requestId,
        uidPrefix: uidPrefix(callerUid),
      });
    }

    const share = await this.repository.findShare(groupId, checkInId);
    if (!share) {
      throw SocialGroupErrors.unavailable('não foi possível compartilhar agora');
    }

    const [item] = await this.projectFeedRows(callerUid, groupId, [
      {
        checkInId,
        authorUid: callerUid,
        authorSocialId: '',
        authorDisplayName: '',
        caption: checkIn.caption,
        publishedAt: checkIn.createdAt,
        sharedToGroupAt: share.createdAt,
      },
    ]);

    return {
      item,
      sharedGroupCount: await this.repository.countSharesForCheckIn(checkInId),
    };
  }

  /**
   * `DELETE /v1/social/groups/{groupId}/checkins/{checkInId}` (§128/§129/§130).
   */
  async unshareCheckIn(
    callerUid: string,
    requestId: string,
    groupId: string,
    checkInId: string,
  ): Promise<void> {
    if (!this.rateLimiter.tryAcquireShare(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);

    const now = this.clock.now();
    const removed = await this.repository.transaction(async (client) => {
      const deleted = await this.repository.deleteShare(groupId, checkInId, callerUid, client);
      if (deleted) {
        await this.interactions.purgeGroupInteractionsForShare(groupId, checkInId, now, client);
      }
      return deleted;
    });

    if (removed) {
      this.logger.info('social.group.checkin_unshared', {
        requestId,
        uidPrefix: uidPrefix(callerUid),
      });
    }
  }

  /**
   * `GET /v1/social/groups/{groupId}/feed` (§59/§60/§61/§86).
   */
  async getFeed(
    callerUid: string,
    requestId: string,
    groupId: string,
    limit?: number,
  ): Promise<SocialGroupFeedDto> {
    await this.requireActiveProfile(callerUid);
    await this.requireMembership(callerUid, groupId);

    const now = this.clock.now();
    const bounded = Math.min(
      Math.max(limit ?? SOCIAL_GROUP_FEED.defaultLimit, 1),
      SOCIAL_GROUP_FEED.maxLimit,
    );
    const rows = await this.repository.findGroupFeed(
      callerUid,
      groupId,
      now - SOCIAL_GROUP_FEED.windowMs,
      bounded,
    );

    this.logger.info('social.group.feed.read', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      returnedCount: rows.length,
    });

    return { items: await this.projectFeedRows(callerUid, groupId, rows) };
  }

  /** §141 — em quais Squads este check-in **próprio** já está. Alimenta o seletor da tela. */
  async listGroupsForCheckIn(callerUid: string, checkInId: string): Promise<CheckInGroupSharesDto> {
    await this.requireActiveProfile(callerUid);
    const checkIn = await this.checkIns.findById(checkInId);
    if (!checkIn || checkIn.authorUid !== callerUid || checkIn.status !== 'PUBLISHED') {
      throw SocialGroupErrors.checkInNotFound();
    }
    return { groupIds: await this.repository.listGroupIdsForCheckIn(checkInId) };
  }

  // ================================================================== ciclo de vida da conta

  /**
   * A política de desativação do Social (§96–§99).
   */
  async applySocialDisable(
    ownerUid: string,
    now: number,
    client?: PoolClient,
  ): Promise<{
    groupsDeleted: number;
    groupsLeft: number;
    invitationsCancelled: number;
  }> {
    const blocking = await this.repository.listOwnedActiveGroupsWithOthers(ownerUid, client);
    if (blocking.length > 0) {
      throw SocialGroupErrors.ownerActionRequired(
        `transfira a posse ou exclua ${blocking.length} squad(s) antes de desativar o social`,
      );
    }

    let groupsDeleted = 0;
    const owned = await this.repository.listOwnedActiveGroups(ownerUid, client);
    for (const groupId of owned) {
      await this.repository.markGroupDeleted(groupId, ownerUid, now, client);
      await this.repository.purgeGroupContext(groupId, now, client);
      await this.interactions.purgeGroupInteractions(groupId, now, client);
      groupsDeleted += 1;
    }

    let groupsLeft = 0;
    const memberGroups = await this.repository.listActiveMemberGroups(ownerUid, client);
    for (const groupId of memberGroups) {
      await this.purgeMemberFootprint(groupId, ownerUid, now, client);
      await this.repository.deleteMembership(groupId, ownerUid, client);
      groupsLeft += 1;
    }

    const invitationsCancelled = await this.repository.cancelAllPendingInvitationsFor(
      ownerUid,
      now,
      client,
    );

    return { groupsDeleted, groupsLeft, invitationsCancelled };
  }

  /**
   * A pergunta que a desativação faz **antes** de tentar (§99).
   */
  async countGroupsRequiringOwnerAction(ownerUid: string, client?: PoolClient): Promise<number> {
    const groups = await this.repository.listOwnedActiveGroupsWithOthers(ownerUid, client);
    return groups.length;
  }

  // ------------------------------------------------------------------ internas

  private async requireActiveProfile(callerUid: string) {
    const account = await this.socialRepository.find(callerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw SocialGroupErrors.socialNotEnabled();
    }
    return account.profile;
  }

  private async requireMembership(callerUid: string, groupId: string) {
    const group = await this.repository.findGroup(groupId);
    if (!group || group.status !== 'ACTIVE') {
      throw SocialGroupErrors.notFound();
    }
    const membership = await this.repository.findActiveMembership(groupId, callerUid);
    if (!membership) {
      throw SocialGroupErrors.notFound();
    }
    return { group, membership };
  }

  private async purgeMemberFootprint(
    groupId: string,
    memberUid: string,
    now: number,
    client?: PoolClient,
  ): Promise<void> {
    const sharedIds = await this.repository.listSharedCheckInIdsByAuthorInGroup(
      groupId,
      memberUid,
      client,
    );
    for (const checkInId of sharedIds) {
      await this.interactions.purgeGroupInteractionsForShare(groupId, checkInId, now, client);
    }
    await this.repository.deleteSharesByAuthorInGroup(groupId, memberUid, client);
    await this.interactions.purgeGroupInteractionsByActor(groupId, memberUid, now, client);
  }

  private async respondToInvitation(
    callerUid: string,
    requestId: string,
    invitationId: string,
    outcome: 'DECLINED' | 'CANCELLED',
  ): Promise<void> {
    if (!this.rateLimiter.tryAcquireMembership(callerUid)) {
      throw SocialGroupErrors.rateLimited();
    }
    await this.requireActiveProfile(callerUid);
    await this.sweepExpiredInvitations();

    const invitation = await this.repository.findInvitation(invitationId);
    const authorized =
      invitation &&
      (outcome === 'DECLINED'
        ? invitation.recipientUid === callerUid
        : invitation.senderUid === callerUid);
    if (!authorized) {
      throw SocialGroupErrors.invitationNotAvailable();
    }

    await this.repository.resolveInvitation(invitationId, outcome, this.clock.now());

    this.logger.info('social.group.invitation.resolved', {
      requestId,
      uidPrefix: uidPrefix(callerUid),
      outcome,
    });
  }

  private async summaryOf(
    group: StoredSocialGroup,
    role: 'OWNER' | 'MEMBER',
  ): Promise<SocialGroupSummaryDto> {
    return {
      groupId: group.id,
      name: group.name,
      memberCount: await this.repository.countMembers(group.id),
      role,
      createdAt: group.createdAt,
    };
  }

  private async invitationDtoOf(
    invitation: StoredGroupInvitation,
    group: StoredSocialGroup,
    inviterUid: string,
  ): Promise<SocialGroupInvitationDto> {
    const inviter = await this.socialRepository.find(inviterUid);
    return {
      invitationId: invitation.id,
      groupId: group.id,
      groupName: group.name,
      memberCount: await this.repository.countMembers(group.id),
      inviterSocialId: inviter?.profile.socialId ?? null,
      inviterDisplayName: inviter?.profile.displayName ?? null,
      status:
        this.clock.now() >= invitation.expiresAt && invitation.status === 'PENDING'
          ? 'EXPIRED'
          : invitation.status,
      createdAt: invitation.createdAt,
      expiresAt: invitation.expiresAt,
    };
  }

  private async projectFeedRows(
    viewerUid: string,
    groupId: string,
    rows: readonly GroupFeedRow[],
  ): Promise<SocialGroupFeedItemDto[]> {
    if (rows.length === 0) {
      return [];
    }

    const selfProfile = (await this.socialRepository.find(viewerUid))?.profile;
    const projectable: ProjectableCheckIn[] = rows.map((row) => ({
      checkInId: row.checkInId,
      authorUid: row.authorUid,
      authorSocialId:
        row.authorSocialId || (row.authorUid === viewerUid ? (selfProfile?.socialId ?? '') : ''),
      authorDisplayName:
        row.authorDisplayName ||
        (row.authorUid === viewerUid ? (selfProfile?.displayName ?? '') : ''),
      caption: row.caption,
      publishedAt: row.publishedAt,
      canInteract: true,
    }));

    const projected = await this.projector.project(viewerUid, projectable, {
      type: 'GROUP',
      groupId,
    });
    return projected.map((checkIn, index) => ({
      checkIn,
      sharedToGroupAt: rows[index].sharedToGroupAt,
    }));
  }
}
