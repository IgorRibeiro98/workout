import { Injectable, Optional } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type {
  AcceptFriendRequestResponseDto,
  CancelFriendRequestResponseDto,
  FriendDto,
  FriendListResponseDto,
  FriendLookupResponseDto,
  FriendRequestDto,
  FriendRequestListResponseDto,
  RejectFriendRequestResponseDto,
  RemoveFriendResponseDto,
  SendFriendRequestResponseDto,
} from './friendship.contract';
import { FriendshipAccessPolicy } from './friendship.access-policy';
import { FriendshipErrors } from './friendship.errors';
import { FriendshipRateLimiter } from './friendship.rate-limit';
import {
  type FriendProfileRow,
  type FriendRequestWithProfile,
  FriendshipRepository,
  type StoredFriend,
  type StoredFriendRequest,
} from './friendship.repository';
import { encodeCursor, type ListQuery } from './friendship.validator';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialErrors } from './social.errors';
import { generateFriendRequestId, normalizeFriendCode } from './social.identity';
import type { SocialProfilePreviewDto } from './social.contract';
import { BlockService } from './block.service';

/**
 * O caso de uso do grafo social (T17.1).
 */
@Injectable()
export class FriendshipService {
  constructor(
    private readonly repository: FriendshipRepository,
    private readonly socialPolicy: SocialAccessPolicy,
    private readonly policy: FriendshipAccessPolicy,
    private readonly limiter: FriendshipRateLimiter,
    private readonly logger: SparkLogger,
    @Optional() private readonly blockService?: BlockService,
  ) {}

  // --------------------------------------------------------------------------------- lookup

  async lookup(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawFriendCode: string,
  ): Promise<FriendLookupResponseDto> {
    const viewer = await this.requireActiveProfile(principal);

    if (!this.limiter.tryAcquireLookup(principal.uid)) {
      this.logger.warn('social.friends.lookup.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw FriendshipErrors.rateLimited('muitas buscas por código de amigo nesta conta');
    }

    const canonical = normalizeFriendCode(rawFriendCode);
    const target = canonical ? await this.repository.findProfileByFriendCode(canonical) : null;

    if (!target) {
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'NOT_FOUND' });
      return { result: 'NOT_FOUND' };
    }

    if (await this.blockService?.isBlocked(viewer.ownerUid, target.ownerUid)) {
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'NOT_FOUND' });
      return { result: 'NOT_FOUND' };
    }

    if (target.ownerUid === viewer.ownerUid) {
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'SELF' });
      return { result: 'SELF' };
    }

    if (!this.socialPolicy.canDiscoverByFriendCode(target)) {
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'NOT_FOUND' });
      return { result: 'NOT_FOUND' };
    }

    const relationship = await this.repository.relationship(viewer.ownerUid, target.ownerUid);
    this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'FOUND', relationship });

    return {
      result: 'FOUND',
      profile: toPreview(target),
      relationship,
      canSendFriendRequest:
        this.socialPolicy.canReceiveFriendRequest(target) &&
        relationship !== 'FRIENDS' &&
        relationship !== 'OUTGOING_PENDING',
    };
  }

  // --------------------------------------------------------------------------------- envio

  async send(
    principal: AuthenticatedPrincipal,
    requestId: string,
    socialId: string,
  ): Promise<SendFriendRequestResponseDto> {
    const requester = await this.requireActiveProfile(principal);

    if (!this.limiter.tryAcquireSend(principal.uid)) {
      this.logger.warn('social.friends.request.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw FriendshipErrors.rateLimited('muitos pedidos de amizade enviados por esta conta');
    }

    const target = await this.repository.findProfileBySocialId(socialId);
    if (
      !target ||
      target.status !== 'ACTIVE' ||
      (await this.blockService?.isBlocked(requester.ownerUid, target.ownerUid))
    ) {
      throw FriendshipErrors.profileNotFound();
    }
    if (target.ownerUid === requester.ownerUid) {
      throw FriendshipErrors.selfRequest();
    }
    if (await this.repository.areFriends(requester.ownerUid, target.ownerUid)) {
      throw FriendshipErrors.alreadyFriends();
    }
    if (!this.socialPolicy.canReceiveFriendRequest(target)) {
      throw FriendshipErrors.requestsDisabled();
    }

    const outcome = await this.repository.sendRequest({
      requestId: generateFriendRequestId(),
      requesterUid: requester.ownerUid,
      recipientUid: target.ownerUid,
      now: Date.now(),
    });

    this.log(requestId, principal.uid, 'social.friends.request.sent', { result: outcome.kind });

    switch (outcome.kind) {
      case 'CREATED':
        return {
          result: 'REQUEST_CREATED',
          request: toRequestDto(outcome.request, target, 'OUTGOING'),
        };
      case 'ALREADY_PENDING':
        return {
          result: 'REQUEST_ALREADY_PENDING',
          request: toRequestDto(outcome.request, target, 'OUTGOING'),
        };
      case 'FRIENDSHIP_CREATED':
        return { result: 'FRIENDSHIP_CREATED', friend: toFriend(target, outcome.friendsSince) };
      case 'ALREADY_FRIENDS':
        throw FriendshipErrors.alreadyFriends();
      case 'BLOCKED':
        // O mesmo desfecho do pré-check de bloqueio acima (linha ~123): este caminho só é
        // alcançado quando o bloqueio foi criado **depois** do pré-check e **sob o lock do par**
        // antes deste envio (T18.0.3) — o resultado para quem chamou precisa ser idêntico.
        throw FriendshipErrors.profileNotFound();
    }
  }

  // --------------------------------------------------------------------------------- respostas

  async accept(
    principal: AuthenticatedPrincipal,
    requestId: string,
    friendRequestId: string,
  ): Promise<AcceptFriendRequestResponseDto> {
    const recipient = await this.requireActiveProfile(principal);
    const { request, other } = await this.requireParticipation(recipient, friendRequestId);

    if (await this.blockService?.isBlocked(recipient.ownerUid, other.ownerUid)) {
      throw FriendshipErrors.requestNotFound();
    }

    if (!this.policy.canRespond(request, recipient.ownerUid)) {
      throw FriendshipErrors.notRecipient();
    }

    const outcome = await this.repository.acceptRequest(friendRequestId, Date.now());
    this.log(requestId, principal.uid, 'social.friends.request.accepted', {
      friendRequestId,
      result: outcome.kind,
    });

    if (outcome.kind === 'NOT_PENDING') {
      throw FriendshipErrors.requestNotPending();
    }
    return {
      result: outcome.kind === 'ACCEPTED' ? 'ACCEPTED' : 'ALREADY_FRIENDS',
      friend: toFriend(other, outcome.friendsSince),
    };
  }

  async reject(
    principal: AuthenticatedPrincipal,
    requestId: string,
    friendRequestId: string,
  ): Promise<RejectFriendRequestResponseDto> {
    const recipient = await this.requireActiveProfile(principal);
    const { request } = await this.requireParticipation(recipient, friendRequestId);

    if (!this.policy.canRespond(request, recipient.ownerUid)) {
      throw FriendshipErrors.notRecipient();
    }

    const result = await this.resolve(friendRequestId, 'REJECTED');
    this.log(requestId, principal.uid, 'social.friends.request.rejected', {
      friendRequestId,
      result,
    });
    return { result: result === 'RESOLVED' ? 'REJECTED' : 'ALREADY_REJECTED' };
  }

  async cancel(
    principal: AuthenticatedPrincipal,
    requestId: string,
    friendRequestId: string,
  ): Promise<CancelFriendRequestResponseDto> {
    const requester = await this.requireActiveProfile(principal);
    const { request } = await this.requireParticipation(requester, friendRequestId);

    if (!this.policy.canCancel(request, requester.ownerUid)) {
      throw FriendshipErrors.notSender();
    }

    const result = await this.resolve(friendRequestId, 'CANCELLED');
    this.log(requestId, principal.uid, 'social.friends.request.cancelled', {
      friendRequestId,
      result,
    });
    return { result: result === 'RESOLVED' ? 'CANCELLED' : 'ALREADY_CANCELLED' };
  }

  private async resolve(
    friendRequestId: string,
    status: 'REJECTED' | 'CANCELLED',
  ): Promise<'RESOLVED' | 'ALREADY'> {
    if (await this.repository.resolveRequest(friendRequestId, status, Date.now())) {
      return 'RESOLVED';
    }
    const current = await this.repository.findRequestById(friendRequestId);
    if (current?.status === status) {
      return 'ALREADY';
    }
    throw FriendshipErrors.requestNotPending();
  }

  // --------------------------------------------------------------------------------- listagens

  async incoming(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ListQuery,
  ): Promise<FriendRequestListResponseDto> {
    return await this.requests(principal, requestId, 'INCOMING', query);
  }

  async outgoing(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ListQuery,
  ): Promise<FriendRequestListResponseDto> {
    return await this.requests(principal, requestId, 'OUTGOING', query);
  }

  private async requests(
    principal: AuthenticatedPrincipal,
    requestId: string,
    direction: 'INCOMING' | 'OUTGOING',
    query: ListQuery,
  ): Promise<FriendRequestListResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    const page = await this.repository.listRequests(owner.ownerUid, direction, query);

    this.log(requestId, principal.uid, 'social.friends.requests.listed', {
      direction,
      count: page.items.length,
    });

    return {
      requests: page.items.map(toListedRequestDto),
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeCursor(page.nextCursor) } : {}),
    };
  }

  async friends(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ListQuery,
  ): Promise<FriendListResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    const page = await this.repository.listFriends(owner.ownerUid, query);

    this.log(requestId, principal.uid, 'social.friends.listed', { count: page.items.length });

    return {
      friends: page.items.map(toFriendDto),
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeCursor(page.nextCursor) } : {}),
    };
  }

  async remove(
    principal: AuthenticatedPrincipal,
    requestId: string,
    socialId: string,
  ): Promise<RemoveFriendResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    const target = await this.repository.findProfileBySocialId(socialId);

    if (!target || !(await this.repository.areFriends(owner.ownerUid, target.ownerUid))) {
      throw FriendshipErrors.friendshipNotFound();
    }
    if (!this.policy.canRemoveFriendship(owner.ownerUid, target.ownerUid, owner.ownerUid)) {
      throw FriendshipErrors.friendshipNotFound();
    }

    await this.repository.removeFriendship(owner.ownerUid, target.ownerUid);
    this.log(requestId, principal.uid, 'social.friends.removed', {});
    return { result: 'REMOVED' };
  }

  // --------------------------------------------------------------------------------- comum

  private async requireActiveProfile(principal: AuthenticatedPrincipal): Promise<FriendProfileRow> {
    const profile = await this.repository.findProfileByOwnerUid(principal.uid);
    if (!profile) {
      throw SocialErrors.notEnabled();
    }
    if (profile.status !== 'ACTIVE') {
      throw FriendshipErrors.profileDisabled();
    }
    return profile;
  }

  private async requireParticipation(
    viewer: FriendProfileRow,
    friendRequestId: string,
  ): Promise<{ request: StoredFriendRequest; other: FriendProfileRow }> {
    const request = await this.repository.findRequestById(friendRequestId);
    if (!request || !this.policy.isParticipant(request, viewer.ownerUid)) {
      throw FriendshipErrors.requestNotFound();
    }

    const otherUid =
      request.requesterUid === viewer.ownerUid ? request.recipientUid : request.requesterUid;
    const other = await this.repository.findProfileByOwnerUid(otherUid);
    if (!other || other.status !== 'ACTIVE') {
      throw FriendshipErrors.requestNotFound();
    }
    return { request, other };
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

function toPreview(profile: FriendProfileRow): SocialProfilePreviewDto {
  return { socialId: profile.socialId, displayName: profile.displayName };
}

function toFriend(profile: FriendProfileRow, friendsSince: number): FriendDto {
  return {
    socialId: profile.socialId,
    displayName: profile.displayName,
    friendsSince,
  };
}

function toFriendDto(friend: StoredFriend): FriendDto {
  return {
    socialId: friend.socialId,
    displayName: friend.displayName,
    friendsSince: friend.friendsSince,
  };
}

function toRequestDto(
  request: StoredFriendRequest,
  other: FriendProfileRow,
  direction: 'INCOMING' | 'OUTGOING',
): FriendRequestDto {
  return {
    requestId: request.requestId,
    profile: toPreview(other),
    direction,
    createdAt: request.createdAt,
  };
}

function toListedRequestDto(request: FriendRequestWithProfile): FriendRequestDto {
  return {
    requestId: request.requestId,
    profile: { socialId: request.otherSocialId, displayName: request.otherDisplayName },
    direction: request.direction,
    createdAt: request.createdAt,
  };
}
