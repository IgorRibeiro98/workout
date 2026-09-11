import {
  Inject,
  Injectable,
  OnApplicationBootstrap,
  OnApplicationShutdown,
  Optional,
} from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { PostgresService } from '../../database/postgres.service';
import { canonicalPair } from './friendship.repository';
import type {
  NotificationEvent,
  NotificationPreferencesDto,
  NotificationType,
} from './notification.contract';
import { NotificationRepository } from './notification.repository';
import { PUSH_GATEWAY, type PushGateway, type PushPayload } from './push-gateway';

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;

@Injectable()
export class NotificationDispatcher implements OnApplicationBootstrap, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private isProcessing = false;
  private isShuttingDown = false;

  constructor(
    private readonly repository: NotificationRepository,
    private readonly db: PostgresService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    @Optional() @Inject(PUSH_GATEWAY) private readonly pushGateway?: PushGateway,
  ) {}

  onApplicationBootstrap(): void {
    if (!this.config.socialPushEnabled) {
      this.logger.info('notification.dispatcher.disabled', {
        reason: 'SOCIAL_PUSH_ENABLED=false',
      });
      return;
    }
    if (this.config.backgroundJobsMode === 'disabled') {
      // T18.2 §32 — Cloud Run request-based/min-instances=0 não garante CPU fora de uma
      // requisição: quem agenda o ciclo é `spark-maintenance` (Cloud Scheduler), chamando
      // `runDispatchCycle()` diretamente. Nenhum `setInterval` nasce aqui neste modo.
      this.logger.info('notification.dispatcher.disabled', {
        reason: 'BACKGROUND_JOBS_MODE=disabled',
      });
      return;
    }

    const intervalMs = this.config.pushDispatchIntervalMs;
    this.logger.info('notification.dispatcher.started', { intervalMs });

    // Roda primeira passada após inicialização
    setImmediate(() => {
      this.runDispatchCycle().catch((error) => {
        this.logger.error('notification.dispatcher.initial_error', {
          errorName: error instanceof Error ? error.name : 'Unknown',
        });
      });
    });

    this.timer = setInterval(() => {
      this.runDispatchCycle().catch((error) => {
        this.logger.error('notification.dispatcher.cycle_error', {
          errorName: error instanceof Error ? error.name : 'Unknown',
        });
      });
    }, intervalMs);
  }

  onApplicationShutdown(): void {
    this.isShuttingDown = true;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  /**
   * Executa um ciclo completo de despacho.
   * Protegido contra sobreposição via mutex in-flight (T17.5 §72).
   */
  async runDispatchCycle(): Promise<void> {
    if (this.isProcessing || this.isShuttingDown) {
      return;
    }

    this.isProcessing = true;
    try {
      await this.dispatchPendingEvents();
      await this.cleanupOldData();
    } finally {
      this.isProcessing = false;
    }
  }

  private async dispatchPendingEvents(): Promise<void> {
    const now = this.clock.now();
    const expiredCount = await this.repository.markExpiredEvents(now);
    if (expiredCount > 0) {
      this.logger.info('notification.events.expired_sweep', { count: expiredCount });
    }

    const batchSize = this.config.pushBatchSize;
    const dueEvents = await this.repository.findDueEvents(now, batchSize);

    if (dueEvents.length === 0) {
      return;
    }

    for (const event of dueEvents) {
      if (this.isShuttingDown) break;
      await this.processEvent(event, now);
    }
  }

  private async processEvent(event: NotificationEvent, now: number): Promise<void> {
    // 1. Revalidação de expiração (T17.5 §83)
    if (now >= event.expiresAt) {
      await this.repository.markEventStatus(event.id, 'EXPIRED', now);
      this.logger.info('notification.event.expired', {
        eventId: event.id,
        eventType: event.type,
      });
      return;
    }

    // 2. Revalidação de relevância canônica (T17.5 §63–§68)
    const relevance = await this.checkRelevance(event, now);
    if (!relevance.relevant) {
      await this.repository.markEventStatus(
        event.id,
        relevance.reason === 'EXPIRED' ? 'EXPIRED' : 'SUPPRESSED',
        now,
      );
      this.logger.info('notification.event.suppressed', {
        eventId: event.id,
        eventType: event.type,
        reason: relevance.reason,
      });
      return;
    }

    // 3. Verificação de consentimento e preferências do usuário (T17.5 §23–§25)
    const preferences = await this.repository.getPreferences(event.recipientUid);
    if (!this.isCategoryEnabled(preferences, event.type)) {
      await this.repository.markEventStatus(event.id, 'SUPPRESSED', now);
      this.logger.info('notification.event.suppressed', {
        eventId: event.id,
        eventType: event.type,
        reason: 'PREFERENCE_DISABLED',
      });
      return;
    }

    // 4. Busca recipientSocialId para o payload mínimo seguro (T17.5 §94)
    const profileRes = await this.db.query<{ social_id: string }>(
      `SELECT social_id FROM social_profiles WHERE owner_uid = $1 AND status = 'ACTIVE'`,
      [event.recipientUid],
    );
    const profileRow = profileRes.rows[0];

    if (!profileRow) {
      await this.repository.markEventStatus(event.id, 'SUPPRESSED', now);
      return;
    }

    // 5. Busca dispositivos ativos do destinatário
    const activeDevices = await this.repository.findActiveDevicesForRecipient(event.recipientUid);
    if (activeDevices.length === 0) {
      // 0 dispositivos: SUPPRESSED_NO_DEVICE (T17.5 §38)
      await this.repository.markEventStatus(event.id, 'SUPPRESSED', now);
      this.logger.info('notification.event.suppressed', {
        eventId: event.id,
        eventType: event.type,
        reason: 'NO_ACTIVE_DEVICES',
      });
      return;
    }

    // 6. Registra deliveries para cada dispositivo ativo
    await this.repository.createDeliveries(
      activeDevices.map((d) => ({
        eventId: event.id,
        deviceRegistrationId: d.id,
      })),
    );

    // 7. Envio para cada dispositivo (fora de transação de banco, T17.5 §42/§179)
    const deliveries = await this.repository.findDeliveriesForEvent(event.id);
    const payload: PushPayload = {
      v: '1',
      eventId: event.id,
      type: event.type,
      recipientSocialId: profileRow.social_id,
      entityId: event.entityId,
    };

    let allCompleted = true;

    for (const delivery of deliveries) {
      if (delivery.status === 'SENT' || delivery.status === 'FAILED_PERMANENT') {
        continue;
      }

      if (
        delivery.status === 'FAILED_TRANSIENT' &&
        delivery.nextAttemptAt !== null &&
        delivery.nextAttemptAt !== undefined &&
        now < delivery.nextAttemptAt
      ) {
        allCompleted = false;
        continue;
      }

      if (!this.pushGateway) {
        // Sem gateway configurado: nada a fazer
        allCompleted = false;
        continue;
      }

      const result = await this.pushGateway.send(delivery.fcmToken, payload);
      const attemptCount = delivery.attemptCount + 1;

      if (result.success) {
        await this.repository.updateDelivery(
          event.id,
          delivery.deviceRegistrationId,
          'SENT',
          attemptCount,
          null,
          null,
          now,
        );
        this.logger.info('notification.delivery.sent', {
          eventId: event.id,
          eventType: event.type,
          attempt: attemptCount,
        });
      } else if (result.permanent) {
        // Token inválido/não registrado (T17.5 §79)
        await this.repository.updateDelivery(
          event.id,
          delivery.deviceRegistrationId,
          'FAILED_PERMANENT',
          attemptCount,
          null,
          result.errorCode ?? 'PERMANENT_ERROR',
          null,
        );
        await this.repository.disableDeviceByToken(delivery.fcmToken, now);
        this.logger.warn('notification.token.invalidated', {
          eventId: event.id,
          errorCode: result.errorCode,
        });
      } else {
        // Falha transitória: retry com backoff (T17.5 §76–§78)
        if (attemptCount >= this.config.pushMaxAttempts) {
          await this.repository.updateDelivery(
            event.id,
            delivery.deviceRegistrationId,
            'FAILED_PERMANENT',
            attemptCount,
            null,
            result.errorCode ?? 'MAX_ATTEMPTS_EXCEEDED',
            null,
          );
          this.logger.warn('notification.delivery.max_attempts', {
            eventId: event.id,
            attempts: attemptCount,
          });
        } else {
          allCompleted = false;
          const backoffMs = Math.min(300_000, Math.pow(2, attemptCount) * 5_000);
          await this.repository.updateDelivery(
            event.id,
            delivery.deviceRegistrationId,
            'FAILED_TRANSIENT',
            attemptCount,
            now + backoffMs,
            result.errorCode ?? 'TRANSIENT_ERROR',
            null,
          );
          this.logger.info('notification.delivery.retry', {
            eventId: event.id,
            attempt: attemptCount,
            retryInMs: backoffMs,
          });
        }
      }
    }

    if (allCompleted) {
      await this.repository.markEventStatus(event.id, 'COMPLETED', now);
      this.logger.info('notification.event.completed', {
        eventId: event.id,
        eventType: event.type,
      });
    }
  }

  private async checkRelevance(
    event: NotificationEvent,
    now: number,
  ): Promise<{ relevant: boolean; reason?: string }> {
    // Verifica se o destinatário está ativo
    const recipientActive = await this.db.query(
      `SELECT 1 FROM social_profiles WHERE owner_uid = $1 AND status = 'ACTIVE'`,
      [event.recipientUid],
    );

    if (recipientActive.rows.length === 0) {
      return { relevant: false, reason: 'RECIPIENT_DISABLED' };
    }

    switch (event.type) {
      case 'FRIEND_REQUEST_RECEIVED': {
        // Só entrega se a solicitação ainda estiver PENDING (T17.5 §64)
        const rowRes = await this.db.query<{ status: string }>(
          `SELECT status FROM friend_requests WHERE request_id = $1`,
          [event.entityId],
        );
        const row = rowRes.rows[0];

        if (!row || row.status !== 'PENDING') {
          return { relevant: false, reason: 'REQUEST_NO_LONGER_PENDING' };
        }
        return { relevant: true };
      }

      case 'FRIEND_REQUEST_ACCEPTED': {
        // Confirma que o pedido foi de fato aceito
        const rowRes = await this.db.query<{
          status: string;
          requester_uid: string;
          recipient_uid: string;
        }>(
          `SELECT status, requester_uid, recipient_uid FROM friend_requests WHERE request_id = $1`,
          [event.entityId],
        );
        const row = rowRes.rows[0];

        if (!row || row.status !== 'ACCEPTED') {
          return { relevant: false, reason: 'REQUEST_NOT_ACCEPTED' };
        }

        // Confirma se a amizade ainda existe no momento do envio (T17.5 §65)
        const [userA, userB] = canonicalPair(row.requester_uid, row.recipient_uid);
        const friendship = await this.db.query(
          `SELECT 1 FROM friendships WHERE user_a_uid = $1 AND user_b_uid = $2`,
          [userA, userB],
        );

        if (friendship.rows.length === 0) {
          return { relevant: false, reason: 'FRIENDSHIP_REMOVED' };
        }
        return { relevant: true };
      }

      case 'CHALLENGE_INVITATION_RECEIVED': {
        // Só entrega se convite PENDING e desafio UPCOMING e OPEN (T17.5 §66)
        const rowRes = await this.db.query<{
          inv_status: string;
          lifecycle: string;
          starts_at: string | number;
        }>(
          `SELECT i.status as inv_status, c.lifecycle, c.starts_at
           FROM challenge_invitations i
           JOIN challenges c ON c.challenge_id = i.challenge_id
           WHERE i.invitation_id = $1`,
          [event.entityId],
        );
        const row = rowRes.rows[0];

        if (
          !row ||
          row.inv_status !== 'PENDING' ||
          row.lifecycle !== 'OPEN' ||
          now >= Number(row.starts_at)
        ) {
          return { relevant: false, reason: 'INVITATION_NO_LONGER_VALID' };
        }
        return { relevant: true };
      }

      case 'CHALLENGE_STARTING_SOON': {
        // Só entrega se participante JOINED, desafio OPEN e now < startsAt (T17.5 §67)
        const rowRes = await this.db.query<{
          part_status: string;
          lifecycle: string;
          starts_at: string | number;
        }>(
          `SELECT p.status as part_status, c.lifecycle, c.starts_at
           FROM challenge_participants p
           JOIN challenges c ON c.challenge_id = p.challenge_id
           WHERE p.challenge_id = $1 AND p.participant_uid = $2`,
          [event.entityId, event.recipientUid],
        );
        const row = rowRes.rows[0];

        if (!row || row.part_status !== 'JOINED' || row.lifecycle !== 'OPEN') {
          return { relevant: false, reason: 'CHALLENGE_CANCELLED_OR_WITHDRAWN' };
        }

        if (now >= Number(row.starts_at)) {
          // Desafio já começou: expira o start-soon em vez de mandar push atrasado (T17.5 §56)
          return { relevant: false, reason: 'EXPIRED' };
        }
        return { relevant: true };
      }

      case 'CHALLENGE_ENDED': {
        // Só entrega se participante JOINED e desafio OPEN e now >= endsAtExclusive (T17.5 §68)
        const rowRes = await this.db.query<{
          part_status: string;
          lifecycle: string;
          ends_at_exclusive: string | number;
        }>(
          `SELECT p.status as part_status, c.lifecycle, c.ends_at_exclusive
           FROM challenge_participants p
           JOIN challenges c ON c.challenge_id = p.challenge_id
           WHERE p.challenge_id = $1 AND p.participant_uid = $2`,
          [event.entityId, event.recipientUid],
        );
        const row = rowRes.rows[0];

        if (!row || row.part_status !== 'JOINED' || row.lifecycle !== 'OPEN') {
          return { relevant: false, reason: 'CHALLENGE_CANCELLED_OR_WITHDRAWN' };
        }

        if (now < Number(row.ends_at_exclusive)) {
          return { relevant: false, reason: 'CHALLENGE_NOT_ENDED_YET' };
        }
        return { relevant: true };
      }

      case 'WORKOUT_SHARE_RECEIVED': {
        const rowRes = await this.db.query<{
          status: string;
          expires_at: string | number;
          sender_uid: string;
        }>(
          `SELECT status, expires_at, sender_uid
           FROM workout_shares
           WHERE id = $1`,
          [event.entityId],
        );
        const row = rowRes.rows[0];

        if (!row || row.status !== 'PENDING' || now >= Number(row.expires_at)) {
          return { relevant: false, reason: 'SHARE_NO_LONGER_PENDING' };
        }

        const blockedRes = await this.db.query(
          `SELECT 1 FROM social_blocks
           WHERE (blocker_uid = $1 AND blocked_uid = $2)
              OR (blocker_uid = $2 AND blocked_uid = $1)
           LIMIT 1`,
          [row.sender_uid, event.recipientUid],
        );

        if (blockedRes.rows.length > 0) {
          return { relevant: false, reason: 'BLOCKED' };
        }

        return { relevant: true };
      }

      case 'GROUP_INVITATION_RECEIVED': {
        const rowRes = await this.db.query<{
          inv_status: string;
          expires_at: string | number;
          sender_uid: string;
          group_status: string;
        }>(
          `SELECT i.status AS inv_status, i.expires_at, i.sender_uid, g.status AS group_status
             FROM social_group_invitations i
             JOIN social_groups g ON g.id = i.group_id
            WHERE i.id = $1`,
          [event.entityId],
        );
        const row = rowRes.rows[0];

        if (
          !row ||
          row.inv_status !== 'PENDING' ||
          row.group_status !== 'ACTIVE' ||
          now >= Number(row.expires_at)
        ) {
          return { relevant: false, reason: 'INVITATION_NO_LONGER_VALID' };
        }

        const blockedRes = await this.db.query(
          `SELECT 1 FROM social_blocks
           WHERE (blocker_uid = $1 AND blocked_uid = $2)
              OR (blocker_uid = $2 AND blocked_uid = $1)
           LIMIT 1`,
          [row.sender_uid, event.recipientUid],
        );

        if (blockedRes.rows.length > 0) {
          return { relevant: false, reason: 'BLOCKED' };
        }

        return { relevant: true };
      }

      default:
        return { relevant: false, reason: 'UNKNOWN_TYPE' };
    }
  }

  private isCategoryEnabled(prefs: NotificationPreferencesDto, type: NotificationType): boolean {
    if (!prefs.pushEnabled) return false;

    switch (type) {
      case 'FRIEND_REQUEST_RECEIVED':
        return prefs.friendRequestReceived;
      case 'FRIEND_REQUEST_ACCEPTED':
        return prefs.friendRequestAccepted;
      case 'CHALLENGE_INVITATION_RECEIVED':
        return prefs.challengeInvitationReceived;
      case 'CHALLENGE_STARTING_SOON':
        return prefs.challengeStartingSoon;
      case 'CHALLENGE_ENDED':
        return prefs.challengeEnded;
      case 'WORKOUT_SHARE_RECEIVED':
        return prefs.workoutShareReceived;
      case 'GROUP_INVITATION_RECEIVED':
        return prefs.groupInvitationReceived;
    }
  }

  private async cleanupOldData(): Promise<void> {
    const now = this.clock.now();
    const cutoff = now - THIRTY_DAYS_MS;
    const { cleanedEvents } = await this.repository.cleanupOldEntries(cutoff);
    if (cleanedEvents > 0) {
      this.logger.info('notification.cleanup', { cleanedEvents });
    }
  }
}
