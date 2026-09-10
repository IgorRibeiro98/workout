import { Inject, Injectable, Optional } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { ChallengeAccessPolicy, type ChallengeLifecycleView } from './challenge.access-policy';
import type {
  AcceptChallengeResponseDto,
  CancelChallengeResponseDto,
  ChallengeDetailResponseDto,
  ChallengeInvitationListResponseDto,
  ChallengeListResponseDto,
  ChallengeParticipantScoreDto,
  ChallengePreviewDto,
  ChallengeSummaryDto,
  CreateChallengeResponseDto,
  DeclineChallengeResponseDto,
  LeaveChallengeResponseDto,
} from './challenge.contract';
import { ChallengeErrors } from './challenge.errors';
import { CHALLENGE_PARTICIPANTS, CHALLENGE_MAX_OPEN_PER_CREATOR } from './challenge.limits';
import { ChallengeRateLimiter } from './challenge.rate-limit';
import {
  ChallengeFullError,
  ChallengeRepository,
  type StoredChallenge,
  type StoredChallengeParticipant,
} from './challenge.repository';
import { ChallengeScoringService, type ScorableChallenge } from './challenge.scoring';
import {
  type ChallengeListQuery,
  type CreateChallengeRequest,
  encodeChallengeCursor,
} from './challenge.validator';
import { FriendshipErrors } from './friendship.errors';
import { FriendshipRepository, type FriendProfileRow } from './friendship.repository';
import { SocialErrors } from './social.errors';
import { BlockService } from './block.service';

/**
 * O caso de uso dos desafios entre amigos (T17.3).
 */
@Injectable()
export class ChallengeService {
  constructor(
    private readonly repository: ChallengeRepository,
    private readonly friendships: FriendshipRepository,
    private readonly policy: ChallengeAccessPolicy,
    private readonly scoring: ChallengeScoringService,
    private readonly limiter: ChallengeRateLimiter,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    @Optional() private readonly blockService?: BlockService,
  ) {}

  // --------------------------------------------------------------------------------- criação

  /**
   * `POST /v1/social/challenges`.
   */
  async create(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: CreateChallengeRequest,
  ): Promise<CreateChallengeResponseDto> {
    const creator = await this.requireActiveProfile(principal);

    if (!this.limiter.tryAcquireCreate(principal.uid)) {
      this.logger.warn('social.challenge.create.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw ChallengeErrors.rateLimited('muitos desafios criados por esta conta');
    }

    const now = this.clock.now();
    if (
      (await this.repository.countOpenChallengesBy(creator.ownerUid, now)) >=
      CHALLENGE_MAX_OPEN_PER_CREATOR
    ) {
      throw ChallengeErrors.tooManyOpenChallenges();
    }

    // O criador ocupa uma vaga (§31), e o validador já garantiu o teto sobre a lista. A checagem
    // é repetida aqui porque o teto é do servidor (§166), e não da forma do corpo.
    if (request.invitedSocialIds.length + 1 > CHALLENGE_PARTICIPANTS.max) {
      throw ChallengeErrors.tooManyParticipants();
    }

    const invitations = await Promise.all(
      request.invitedSocialIds.map(async (socialId) => ({
        invitationId: randomUUID(),
        recipientUid: await this.resolveInvitableFriend(creator, socialId),
      })),
    );

    const outcome = await this.repository.create({
      challengeId: randomUUID(),
      creatorUid: creator.ownerUid,
      name: request.name,
      type: request.type,
      target: request.target,
      startDate: request.startDate,
      endDate: request.endDate,
      timeZoneId: request.timeZoneId,
      startsAt: request.startsAt,
      endsAtExclusive: request.endsAtExclusive,
      invitations,
      clientRequestId: request.clientRequestId,
      requestHash: request.requestHash,
      now,
    });

    if (outcome.kind === 'IDEMPOTENCY_CONFLICT') {
      throw ChallengeErrors.idempotencyConflict();
    }

    this.log(requestId, principal.uid, 'social.challenge.created', {
      result: outcome.kind,
      challengeType: outcome.challenge.type,
      invitedCount: invitations.length,
    });

    return {
      result: outcome.kind === 'CREATED' ? 'CREATED' : 'ALREADY_CREATED',
      challenge: await this.summaryOf(outcome.challenge, creator, now),
    };
  }

  /**
   * `socialId` → `ownerUid`, exigindo amizade ativa agora (§32/§34).
   */
  private async resolveInvitableFriend(
    creator: FriendProfileRow,
    socialId: string,
  ): Promise<string> {
    const target = await this.friendships.findProfileBySocialId(socialId);
    if (!target || target.status !== 'ACTIVE') {
      throw ChallengeErrors.participantNotAvailable();
    }
    if (target.ownerUid === creator.ownerUid) {
      throw ChallengeErrors.participantNotAvailable();
    }
    const areFriends = await this.friendships.areFriends(creator.ownerUid, target.ownerUid);
    const isBlocked = this.blockService
      ? await this.blockService.isBlocked(creator.ownerUid, target.ownerUid)
      : false;
    if (!areFriends || isBlocked) {
      throw ChallengeErrors.participantNotAvailable();
    }
    return target.ownerUid;
  }

  // --------------------------------------------------------------------------------- leitura

  /**
   * `GET /v1/social/challenges/:challengeId` — regras e placar.
   */
  async detail(
    principal: AuthenticatedPrincipal,
    requestId: string,
    challengeId: string,
  ): Promise<ChallengeDetailResponseDto> {
    const viewerProfile = await this.requireActiveProfile(principal);
    const challenge = await this.repository.findById(challengeId);
    if (!challenge) {
      throw ChallengeErrors.notFound();
    }

    const participation = await this.repository.findParticipation(
      challengeId,
      viewerProfile.ownerUid,
    );
    if (!participation) {
      throw ChallengeErrors.notFound();
    }

    const now = this.clock.now();
    const participants = await this.repository.listParticipants(challengeId);
    const view = this.lifecycleView(challenge, participants);
    const status = this.policy.statusOf(view, now);

    const active = participants.filter((participant) => participant.status === 'JOINED');
    const withdrawn = participants.length - active.length;

    const scored =
      status === 'CANCELLED' || status === 'VOID'
        ? []
        : await this.scoring.leaderboard(scorableOf(challenge), active);

    const creator = participants.find((participant) => participant.role === 'CREATOR');
    const isCreatorBlocked =
      creator && this.blockService
        ? await this.blockService.isBlocked(viewerProfile.ownerUid, creator.ownerUid)
        : false;

    this.log(requestId, principal.uid, 'social.challenge.read', {
      challengeType: challenge.type,
      status,
      participantCount: active.length,
    });

    const pendingCount =
      participation.role === 'CREATOR'
        ? await this.repository.countPendingInvitations(challengeId)
        : undefined;

    const mappedParticipants = await Promise.all(
      scored.map(async (participant): Promise<ChallengeParticipantScoreDto> => {
        const isParticipantBlocked = this.blockService
          ? await this.blockService.isBlocked(viewerProfile.ownerUid, participant.ownerUid)
          : false;
        return {
          socialId: isParticipantBlocked ? '' : participant.socialId,
          displayName: isParticipantBlocked ? 'Participante indisponível' : participant.displayName,
          score: participant.score,
          goalReached: participant.goalReached,
          rank: participant.rank,
          role: participant.role,
          isViewer: participant.ownerUid === viewerProfile.ownerUid,
        };
      }),
    );

    return {
      challenge: {
        challengeId: challenge.challengeId,
        name: challenge.name,
        type: challenge.type,
        target: challenge.target,
        startDate: challenge.startDate,
        endDate: challenge.endDate,
        timeZoneId: challenge.timeZoneId,
        status,
        creator: {
          socialId: isCreatorBlocked ? '' : (creator?.socialId ?? ''),
          displayName: isCreatorBlocked
            ? 'Participante indisponível'
            : (creator?.displayName ?? ''),
        },
        participantCount: active.length,
        createdAt: challenge.createdAt,
      },
      participants: mappedParticipants,
      ...(pendingCount !== undefined ? { pendingInvitationCount: pendingCount } : {}),
      withdrawnCount: withdrawn,
      viewer: {
        role: participation.role,
        status: participation.status,
        canCancel: this.policy.canCancel(view, participation, now),
        canLeave: this.policy.canLeave(view, participation, now),
      },
      resultMayStillChange: this.policy.resultMayStillChange(view, now),
    };
  }

  /**
   * `GET /v1/social/challenges` — os meus.
   */
  async list(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ChallengeListQuery,
  ): Promise<ChallengeListResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    const page = await this.repository.listForParticipant(owner.ownerUid, query);
    const now = this.clock.now();

    const summaries = await Promise.all(
      page.items.map(async (challenge) => {
        const participants = await this.repository.listParticipants(challenge.challengeId);
        const creator = participants.find((participant) => participant.role === 'CREATOR');
        const active = participants.filter((participant) => participant.status === 'JOINED').length;
        return {
          challengeId: challenge.challengeId,
          name: challenge.name,
          type: challenge.type,
          target: challenge.target,
          startDate: challenge.startDate,
          endDate: challenge.endDate,
          timeZoneId: challenge.timeZoneId,
          status: this.policy.statusOf(
            {
              lifecycle: challenge.lifecycle,
              startsAt: challenge.startsAt,
              endsAtExclusive: challenge.endsAtExclusive,
              participantCount: active,
            },
            now,
          ),
          creator: {
            socialId: creator?.socialId ?? '',
            displayName: creator?.displayName ?? '',
          },
          participantCount: active,
          createdAt: challenge.createdAt,
        };
      }),
    );

    summaries.sort(
      (a, b) =>
        STATUS_ORDER[a.status] - STATUS_ORDER[b.status] ||
        (a.status === 'UPCOMING'
          ? a.startDate.localeCompare(b.startDate)
          : b.startDate.localeCompare(a.startDate)) ||
        a.challengeId.localeCompare(b.challengeId),
    );

    this.log(requestId, principal.uid, 'social.challenge.listed', { count: summaries.length });

    return {
      challenges: summaries,
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeChallengeCursor(page.nextCursor) } : {}),
    };
  }

  /**
   * `GET /v1/social/challenge-invitations` — os convites que eu recebi.
   */
  async invitations(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ChallengeListQuery,
  ): Promise<ChallengeInvitationListResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    const page = await this.repository.listPendingInvitations(owner.ownerUid, query);
    const now = this.clock.now();

    const visibleItems: (typeof page.items)[number][] = [];
    for (const invitation of page.items) {
      const blocked = this.blockService
        ? await this.blockService.isBlocked(owner.ownerUid, invitation.inviterUid)
        : false;
      if (!blocked) {
        visibleItems.push(invitation);
      }
    }

    const previews = await Promise.all(
      visibleItems.map(async (invitation): Promise<ChallengePreviewDto> => {
        const participants = await this.repository.listParticipants(invitation.challengeId);
        const creator = participants.find((participant) => participant.role === 'CREATOR');
        const active = participants.filter((participant) => participant.status === 'JOINED').length;
        const view: ChallengeLifecycleView = {
          lifecycle: invitation.challenge.lifecycle,
          startsAt: invitation.challenge.startsAt,
          endsAtExclusive: invitation.challenge.endsAtExclusive,
          participantCount: active,
        };

        return {
          challenge: {
            challengeId: invitation.challenge.challengeId,
            name: invitation.challenge.name,
            type: invitation.challenge.type,
            target: invitation.challenge.target,
            startDate: invitation.challenge.startDate,
            endDate: invitation.challenge.endDate,
            timeZoneId: invitation.challenge.timeZoneId,
            status: this.policy.statusOf(view, now),
            creator: {
              socialId: creator?.socialId ?? '',
              displayName: creator?.displayName ?? '',
            },
            participantCount: active,
            createdAt: invitation.challenge.createdAt,
          },
          invitationId: invitation.invitationId,
          status: this.policy.invitationStatusOf(invitation.status, view, now),
          createdAt: invitation.createdAt,
        };
      }),
    );

    this.log(requestId, principal.uid, 'social.challenge.invitations.listed', {
      count: previews.length,
    });

    return {
      invitations: previews,
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeChallengeCursor(page.nextCursor) } : {}),
    };
  }

  // --------------------------------------------------------------------------------- respostas

  /**
   * `POST /v1/social/challenge-invitations/:invitationId/accept` — só o destinatário (§51).
   */
  async accept(
    principal: AuthenticatedPrincipal,
    requestId: string,
    invitationId: string,
  ): Promise<AcceptChallengeResponseDto> {
    const recipient = await this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const invitation = await this.repository.findInvitationById(invitationId);
    if (!invitation || invitation.recipientUid !== recipient.ownerUid) {
      throw ChallengeErrors.invitationNotFound();
    }

    const challenge = await this.repository.findById(invitation.challengeId);
    if (!challenge) {
      throw ChallengeErrors.invitationNotFound();
    }

    const now = this.clock.now();
    const participantsList = await this.repository.listParticipants(challenge.challengeId);
    const view = this.lifecycleView(challenge, participantsList);

    if (challenge.lifecycle === 'CANCELLED') {
      throw ChallengeErrors.cancelled();
    }
    if (!this.policy.canAcceptInvitation(view, now)) {
      throw ChallengeErrors.alreadyStarted();
    }

    const areFriends = await this.friendships.areFriends(recipient.ownerUid, invitation.inviterUid);
    const isBlocked = this.blockService
      ? await this.blockService.isBlocked(recipient.ownerUid, invitation.inviterUid)
      : false;
    if (!areFriends || isBlocked) {
      throw ChallengeErrors.invitationNotFound();
    }

    let outcome;
    try {
      outcome = await this.repository.acceptInvitation(
        invitationId,
        challenge.challengeId,
        recipient.ownerUid,
        CHALLENGE_PARTICIPANTS.max,
        now,
      );
    } catch (error) {
      if (error instanceof ChallengeFullError) {
        throw ChallengeErrors.tooManyParticipants();
      }
      throw error;
    }

    if (outcome.kind === 'NOT_PENDING') {
      throw ChallengeErrors.invitationNotPending();
    }

    this.log(requestId, principal.uid, 'social.challenge.invitation.accepted', {
      result: outcome.kind,
      challengeType: challenge.type,
    });

    const participants = await this.repository.listParticipants(challenge.challengeId);
    const creator = participants.find((participant) => participant.role === 'CREATOR');
    return {
      result: outcome.kind === 'ACCEPTED' ? 'ACCEPTED' : 'ALREADY_PARTICIPATING',
      challenge: {
        challengeId: challenge.challengeId,
        name: challenge.name,
        type: challenge.type,
        target: challenge.target,
        startDate: challenge.startDate,
        endDate: challenge.endDate,
        timeZoneId: challenge.timeZoneId,
        status: this.policy.statusOf(this.lifecycleView(challenge, participants), now),
        creator: {
          socialId: creator?.socialId ?? '',
          displayName: creator?.displayName ?? '',
        },
        participantCount: participants.filter((p) => p.status === 'JOINED').length,
        createdAt: challenge.createdAt,
      },
    };
  }

  /** `POST /v1/social/challenge-invitations/:invitationId/decline` — só o destinatário (§52). */
  async decline(
    principal: AuthenticatedPrincipal,
    requestId: string,
    invitationId: string,
  ): Promise<DeclineChallengeResponseDto> {
    const recipient = await this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const invitation = await this.repository.findInvitationById(invitationId);
    if (!invitation || invitation.recipientUid !== recipient.ownerUid) {
      throw ChallengeErrors.invitationNotFound();
    }

    const now = this.clock.now();
    if (await this.repository.declineInvitation(invitationId, now)) {
      this.log(requestId, principal.uid, 'social.challenge.invitation.declined', {
        result: 'DECLINED',
      });
      return { result: 'DECLINED' };
    }

    const current = await this.repository.findInvitationById(invitationId);
    if (current?.status === 'DECLINED') {
      this.log(requestId, principal.uid, 'social.challenge.invitation.declined', {
        result: 'ALREADY_DECLINED',
      });
      return { result: 'ALREADY_DECLINED' };
    }
    throw ChallengeErrors.invitationNotPending();
  }

  /**
   * `POST /v1/social/challenges/:challengeId/leave` — só membro (§61/§62).
   */
  async leave(
    principal: AuthenticatedPrincipal,
    requestId: string,
    challengeId: string,
  ): Promise<LeaveChallengeResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const { challenge, participation } = await this.requireParticipation(
      owner.ownerUid,
      challengeId,
    );
    if (participation.role === 'CREATOR') {
      throw ChallengeErrors.cannotLeaveAsCreator();
    }

    const now = this.clock.now();
    if (await this.repository.leave(challengeId, owner.ownerUid, now)) {
      this.log(requestId, principal.uid, 'social.challenge.left', {
        result: 'LEFT',
        challengeType: challenge.type,
      });
      return { result: 'LEFT' };
    }
    this.log(requestId, principal.uid, 'social.challenge.left', { result: 'ALREADY_LEFT' });
    return { result: 'ALREADY_LEFT' };
  }

  /**
   * `POST /v1/social/challenges/:challengeId/cancel` — só o criador (§67).
   */
  async cancel(
    principal: AuthenticatedPrincipal,
    requestId: string,
    challengeId: string,
  ): Promise<CancelChallengeResponseDto> {
    const owner = await this.requireActiveProfile(principal);
    this.requireRespondQuota(principal, requestId);

    const { challenge, participation } = await this.requireParticipation(
      owner.ownerUid,
      challengeId,
    );
    if (participation.role !== 'CREATOR') {
      throw ChallengeErrors.notCreator();
    }

    const now = this.clock.now();
    if (challenge.lifecycle === 'CANCELLED') {
      this.log(requestId, principal.uid, 'social.challenge.cancelled', {
        result: 'ALREADY_CANCELLED',
      });
      return { result: 'ALREADY_CANCELLED' };
    }
    if (now >= challenge.endsAtExclusive) {
      throw ChallengeErrors.notFound();
    }

    if (await this.repository.cancel(challengeId, now)) {
      this.log(requestId, principal.uid, 'social.challenge.cancelled', {
        result: 'CANCELLED',
        challengeType: challenge.type,
      });
      return { result: 'CANCELLED' };
    }
    return { result: 'ALREADY_CANCELLED' };
  }

  // --------------------------------------------------------------------------------- comum

  /**
   * O perfil da conta autenticada, exigindo que ele exista e esteja ativo (§112–§114).
   */
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

  /** O desafio, quando ele existe e **envolve quem perguntou**. Caso contrário, "não existe". */
  private async requireParticipation(
    ownerUid: string,
    challengeId: string,
  ): Promise<{
    challenge: StoredChallenge;
    participation: { role: 'CREATOR' | 'MEMBER'; status: 'JOINED' | 'WITHDRAWN' };
  }> {
    const challenge = await this.repository.findById(challengeId);
    if (!challenge) {
      throw ChallengeErrors.notFound();
    }
    const participation = await this.repository.findParticipation(challengeId, ownerUid);
    if (!participation) {
      throw ChallengeErrors.notFound();
    }
    return { challenge, participation };
  }

  private requireRespondQuota(principal: AuthenticatedPrincipal, requestId: string): void {
    if (!this.limiter.tryAcquireRespond(principal.uid)) {
      this.logger.warn('social.challenge.respond.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw ChallengeErrors.rateLimited('muitas ações de desafio nesta conta');
    }
  }

  private lifecycleView(
    challenge: StoredChallenge,
    participants: readonly StoredChallengeParticipant[],
  ): ChallengeLifecycleView {
    return {
      lifecycle: challenge.lifecycle,
      startsAt: challenge.startsAt,
      endsAtExclusive: challenge.endsAtExclusive,
      participantCount: participants.filter((participant) => participant.status === 'JOINED')
        .length,
    };
  }

  /** O resumo logo depois da criação: o criador é o único participante, e ele é quem chamou. */
  private async summaryOf(
    challenge: StoredChallenge,
    creator: FriendProfileRow,
    now: number,
  ): Promise<ChallengeSummaryDto> {
    const participants = await this.repository.listParticipants(challenge.challengeId);
    const active = participants.filter((participant) => participant.status === 'JOINED').length;
    return {
      challengeId: challenge.challengeId,
      name: challenge.name,
      type: challenge.type,
      target: challenge.target,
      startDate: challenge.startDate,
      endDate: challenge.endDate,
      timeZoneId: challenge.timeZoneId,
      status: this.policy.statusOf(
        {
          lifecycle: challenge.lifecycle,
          startsAt: challenge.startsAt,
          endsAtExclusive: challenge.endsAtExclusive,
          participantCount: active,
        },
        now,
      ),
      creator: { socialId: creator.socialId, displayName: creator.displayName },
      participantCount: active,
      createdAt: challenge.createdAt,
    };
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

/** As regras que o serviço de pontuação precisa. Nada de identidade, nada de participante. */
function scorableOf(challenge: StoredChallenge): ScorableChallenge {
  return {
    type: challenge.type,
    target: challenge.target,
    startDate: challenge.startDate,
    endDate: challenge.endDate,
    timeZoneId: challenge.timeZoneId,
    startsAt: challenge.startsAt,
    endsAtExclusive: challenge.endsAtExclusive,
  };
}

/** A ordem das seções da lista (§107): o que está acontecendo, o que vem, e o que passou. */
const STATUS_ORDER = {
  ACTIVE: 0,
  UPCOMING: 1,
  ENDED: 2,
  VOID: 3,
  CANCELLED: 4,
} as const;
