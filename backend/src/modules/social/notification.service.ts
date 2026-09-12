import { randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { PoolClient } from '../../database/postgres.service';
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

  private async requireActiveProfile(principal: AuthenticatedPrincipal) {
    const account = await this.socialRepository.find(principal.uid);
    if (!account) {
      throw SocialErrors.notEnabled();
    }
    if (account.profile.status !== 'ACTIVE') {
      throw SocialErrors.invalid('perfil social desativado');
    }
    return account.profile;
  }

  // --- Dispositivos ------------------------------------------------------------------

  async registerDevice(
    principal: AuthenticatedPrincipal,
    requestId: string,
    input: RegisterPushDeviceRequest,
  ): Promise<PushDeviceRegistrationDto> {
    await this.requireActiveProfile(principal);

    const now = this.clock.now();
    const id = randomUUID();
    const registration = await this.repository.registerDevice(id, principal.uid, input, now);

    this.logger.info('notification.device.registered', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      deviceId: input.deviceId,
      platform: input.platform,
    });

    return registration;
  }

  async unregisterDevice(principal: AuthenticatedPrincipal, deviceId: string): Promise<void> {
    await this.repository.unregisterDevice(principal.uid, deviceId);
  }

  // --- Preferências ------------------------------------------------------------------

  async getPreferences(principal: AuthenticatedPrincipal): Promise<NotificationPreferencesDto> {
    await this.requireActiveProfile(principal);
    return await this.repository.getPreferences(principal.uid);
  }

  async updatePreferences(
    principal: AuthenticatedPrincipal,
    requestId: string,
    updates: UpdateNotificationPreferencesRequest,
  ): Promise<NotificationPreferencesDto> {
    await this.requireActiveProfile(principal);

    const now = this.clock.now();
    const updated = await this.repository.upsertPreferences(principal.uid, updates, now);

    this.logger.info('notification.preferences.updated', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      pushEnabled: updated.pushEnabled,
    });

    return updated;
  }

  // --- Ciclo de Vida Social ----------------------------------------------------------

  async onSocialDisable(ownerUid: string, client?: PoolClient): Promise<void> {
    const now = this.clock.now();
    // 1. pushEnabled -> false
    await this.repository.upsertPreferences(ownerUid, { pushEnabled: false }, now, client);
    // 2. Dispositivos desabilitados
    await this.repository.disableDevicesForOwner(ownerUid, now, client);
  }

  // --- Enfileiramento de Eventos (Domain Integration) ---------------------------------

  async enqueueFriendRequestReceived(
    client: PoolClient | undefined,
    input: { requestId: string; recipientUid: string; now?: number },
  ): Promise<void> {
    const now = this.clock.now();
    await this.repository.createEvent(
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
      client,
    );
  }

  async enqueueFriendRequestAccepted(
    client: PoolClient | undefined,
    input: { requestId: string; requesterUid: string; now?: number },
  ): Promise<void> {
    const now = this.clock.now();
    await this.repository.createEvent(
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
      client,
    );
  }

  async enqueueChallengeInvitationReceived(
    client: PoolClient | undefined,
    input: {
      invitationId: string;
      challengeId: string;
      recipientUid: string;
      startsAt: number;
      now?: number;
    },
  ): Promise<void> {
    const now = this.clock.now();
    await this.repository.createEvent(
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
      client,
    );
  }

  async enqueueChallengeStartingSoon(
    client: PoolClient | undefined,
    input: { challengeId: string; participantUid: string; startsAt: number; now?: number },
  ): Promise<void> {
    const now = this.clock.now();
    // 24h antes do início, ou imediatamente se o desafio foi criado a menos de 24h do início
    const deliverAfter = Math.max(now, input.startsAt - TWENTY_FOUR_HOURS_MS);

    // Se já começou ou ultrapassou, não agenda
    if (deliverAfter >= input.startsAt) return;

    await this.repository.createEvent(
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
      client,
    );
  }

  async enqueueChallengeEnded(
    client: PoolClient | undefined,
    input: { challengeId: string; participantUid: string; endsAtExclusive: number; now?: number },
  ): Promise<void> {
    const now = this.clock.now();
    await this.repository.createEvent(
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
      client,
    );
  }

  /**
   * O convite para um Squad — o **único** push da T17.11 (§90/§95).
   *
   * O `client` é o mesmo parâmetro dos demais `enqueue*`, e pela mesma razão (T17.13.1 §45–§47):
   * o evento é o **outbox** do convite, e as duas escritas precisam ser a mesma transação. Sem
   * ele, esta chamada saía por uma conexão nova: o convite podia ser desfeito por um ROLLBACK e o
   * evento continuar lá — ou o evento falhar e o convite ficar gravado sem que ninguém fosse
   * avisado dele.
   */
  async enqueueGroupInvitationReceived(
    client: PoolClient | undefined,
    input: {
      invitationId: string;
      recipientUid: string;
      expiresAt: number;
    },
  ): Promise<void> {
    const now = this.clock.now();
    await this.repository.createEvent(
      {
        id: randomUUID(),
        recipientUid: input.recipientUid,
        type: 'GROUP_INVITATION_RECEIVED',
        entityId: input.invitationId,
        dedupeKey: `group-invitation-received:${input.invitationId}:${input.recipientUid}`,
        deliverAfter: now,
        expiresAt: input.expiresAt,
      },
      now,
      client,
    );
  }

  async cancelChallengeEvents(challengeId: string): Promise<void> {
    await this.repository.cancelEventsForEntity(challengeId, [
      'CHALLENGE_STARTING_SOON',
      'CHALLENGE_ENDED',
    ]);
  }

  async cancelParticipantEvents(challengeId: string, participantUid: string): Promise<void> {
    await this.repository.cancelEventsForEntityAndRecipient(challengeId, participantUid, [
      'CHALLENGE_STARTING_SOON',
      'CHALLENGE_ENDED',
    ]);
  }
}
