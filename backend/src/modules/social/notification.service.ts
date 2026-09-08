import { randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { Database } from 'better-sqlite3';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { SocialErrors } from './social.errors';
import { SocialRepository } from './social.repository';
import type {
  NotificationPreferencesDto,
  PushDeviceRegistrationDto,
  RegisterPushDeviceRequest,
  UpdateNotificationPreferencesRequest,
} from './notification.contract';
import { NotificationRepository } from './notification.repository';

const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000;

@Injectable()
export class NotificationService {
  constructor(
    private readonly repository: NotificationRepository,
    private readonly socialRepository: SocialRepository,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  private requireActiveProfile(principal: AuthenticatedPrincipal) {
    const account = this.socialRepository.find(principal.uid);
    if (!account) {
      throw SocialErrors.notEnabled();
    }
    if (account.profile.status !== 'ACTIVE') {
      throw SocialErrors.invalid('perfil social desativado');
    }
    return account.profile;
  }

  // --- Dispositivos ------------------------------------------------------------------

  registerDevice(
    principal: AuthenticatedPrincipal,
    requestId: string,
    input: RegisterPushDeviceRequest,
  ): PushDeviceRegistrationDto {
    this.requireActiveProfile(principal);

    const now = this.clock.now();
    const id = randomUUID();
    const registration = this.repository.registerDevice(id, principal.uid, input, now);

    this.logger.info('notification.device.registered', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      deviceId: input.deviceId,
      platform: input.platform,
    });

    return registration;
  }

  unregisterDevice(principal: AuthenticatedPrincipal, deviceId: string): void {
    this.repository.unregisterDevice(principal.uid, deviceId);
  }

  // --- Preferências ------------------------------------------------------------------

  getPreferences(principal: AuthenticatedPrincipal): NotificationPreferencesDto {
    this.requireActiveProfile(principal);
    return this.repository.getPreferences(principal.uid);
  }

  updatePreferences(
    principal: AuthenticatedPrincipal,
    requestId: string,
    updates: UpdateNotificationPreferencesRequest,
  ): NotificationPreferencesDto {
    this.requireActiveProfile(principal);

    const now = this.clock.now();
    const updated = this.repository.upsertPreferences(principal.uid, updates, now);

    this.logger.info('notification.preferences.updated', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      pushEnabled: updated.pushEnabled,
    });

    return updated;
  }

  // --- Ciclo de Vida Social ----------------------------------------------------------

  onSocialDisable(ownerUid: string): void {
    const now = this.clock.now();
    // 1. pushEnabled -> false
    this.repository.upsertPreferences(ownerUid, { pushEnabled: false }, now);
    // 2. Dispositivos desabilitados
    this.repository.disableDevicesForOwner(ownerUid, now);
  }

  // --- Enfileiramento de Eventos (Domain Integration) ---------------------------------

  enqueueFriendRequestReceived(
    db: Database,
    input: { requestId: string; recipientUid: string; now?: number },
  ): void {
    const now = this.clock.now();
    this.repository.createEvent(
      {
        id: randomUUID(),
        recipientUid: input.recipientUid,
        type: 'FRIEND_REQUEST_RECEIVED',
        entityId: input.requestId,
        dedupeKey: `friend-request-received:${input.requestId}:${input.recipientUid}`,
        deliverAfter: now,
        expiresAt: now + TWENTY_FOUR_HOURS_MS,
      },
      now,
      db,
    );
  }

  enqueueFriendRequestAccepted(
    db: Database,
    input: { requestId: string; requesterUid: string; now?: number },
  ): void {
    const now = this.clock.now();
    this.repository.createEvent(
      {
        id: randomUUID(),
        recipientUid: input.requesterUid,
        type: 'FRIEND_REQUEST_ACCEPTED',
        entityId: input.requestId,
        dedupeKey: `friend-request-accepted:${input.requestId}:${input.requesterUid}`,
        deliverAfter: now,
        expiresAt: now + TWENTY_FOUR_HOURS_MS,
      },
      now,
      db,
    );
  }

  enqueueChallengeInvitationReceived(
    db: Database,
    input: {
      invitationId: string;
      challengeId: string;
      recipientUid: string;
      startsAt: number;
      now?: number;
    },
  ): void {
    const now = this.clock.now();
    this.repository.createEvent(
      {
        id: randomUUID(),
        recipientUid: input.recipientUid,
        type: 'CHALLENGE_INVITATION_RECEIVED',
        entityId: input.invitationId,
        dedupeKey: `challenge-invite:${input.invitationId}:${input.recipientUid}`,
        deliverAfter: now,
        expiresAt: input.startsAt,
      },
      now,
      db,
    );
  }

  enqueueChallengeStartingSoon(
    db: Database,
    input: { challengeId: string; participantUid: string; startsAt: number; now?: number },
  ): void {
    const now = this.clock.now();
    // 24h antes do início, ou imediatamente se o desafio foi criado a menos de 24h do início
    const deliverAfter = Math.max(now, input.startsAt - TWENTY_FOUR_HOURS_MS);

    // Se já começou ou ultrapassou, não agenda
    if (deliverAfter >= input.startsAt) return;

    this.repository.createEvent(
      {
        id: randomUUID(),
        recipientUid: input.participantUid,
        type: 'CHALLENGE_STARTING_SOON',
        entityId: input.challengeId,
        dedupeKey: `challenge-starting:${input.challengeId}:${input.participantUid}`,
        deliverAfter,
        expiresAt: input.startsAt,
      },
      now,
      db,
    );
  }

  enqueueChallengeEnded(
    db: Database,
    input: { challengeId: string; participantUid: string; endsAtExclusive: number; now?: number },
  ): void {
    const now = this.clock.now();
    this.repository.createEvent(
      {
        id: randomUUID(),
        recipientUid: input.participantUid,
        type: 'CHALLENGE_ENDED',
        entityId: input.challengeId,
        dedupeKey: `challenge-ended:${input.challengeId}:${input.participantUid}`,
        deliverAfter: input.endsAtExclusive,
        expiresAt: input.endsAtExclusive + TWENTY_FOUR_HOURS_MS,
      },
      now,
      db,
    );
  }

  cancelChallengeEvents(challengeId: string): void {
    this.repository.cancelEventsForEntity(challengeId, [
      'CHALLENGE_STARTING_SOON',
      'CHALLENGE_ENDED',
    ]);
  }

  cancelParticipantEvents(challengeId: string, participantUid: string): void {
    this.repository.cancelEventsForEntityAndRecipient(challengeId, participantUid, [
      'CHALLENGE_STARTING_SOON',
      'CHALLENGE_ENDED',
    ]);
  }
}
