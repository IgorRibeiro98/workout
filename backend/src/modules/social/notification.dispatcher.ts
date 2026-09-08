import {
  Inject,
  Injectable,
  OnApplicationBootstrap,
  OnApplicationShutdown,
  Optional,
} from '@nestjs/common';
import type { Database } from 'better-sqlite3';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { SqliteService } from '../../database/sqlite.service';
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
    private readonly sqlite: SqliteService,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    @Optional() @Inject(PUSH_GATEWAY) private readonly pushGateway?: PushGateway,
  ) {}

  private get db(): Database {
    return this.sqlite.connection;
  }

  onApplicationBootstrap(): void {
    if (!this.config.socialPushEnabled) {
      this.logger.info('notification.dispatcher.disabled', {
        reason: 'SOCIAL_PUSH_ENABLED=false',
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
      this.cleanupOldData();
    } finally {
      this.isProcessing = false;
    }
  }

  private async dispatchPendingEvents(): Promise<void> {
    const now = this.clock.now();
    const batchSize = this.config.pushBatchSize;
    const dueEvents = this.repository.findDueEvents(now, batchSize);

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
      this.repository.markEventStatus(event.id, 'EXPIRED', now);
      this.logger.info('notification.event.expired', {
        eventId: event.id,
        eventType: event.type,
      });
      return;
    }

    // 2. Revalidação de relevância canônica (T17.5 §63–§68)
    const relevance = this.checkRelevance(event, now);
    if (!relevance.relevant) {
      this.repository.markEventStatus(
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
    const preferences = this.repository.getPreferences(event.recipientUid);
    if (!this.isCategoryEnabled(preferences, event.type)) {
      this.repository.markEventStatus(event.id, 'SUPPRESSED', now);
      this.logger.info('notification.event.suppressed', {
        eventId: event.id,
        eventType: event.type,
        reason: 'PREFERENCE_DISABLED',
      });
      return;
    }

    // 4. Busca recipientSocialId para o payload mínimo seguro (T17.5 §94)
    const profileRow = this.db
      .prepare(`SELECT social_id FROM social_profiles WHERE owner_uid = ? AND status = 'ACTIVE'`)
      .get(event.recipientUid) as { social_id: string } | undefined;

    if (!profileRow) {
      this.repository.markEventStatus(event.id, 'SUPPRESSED', now);
      return;
    }

    // 5. Busca dispositivos ativos do destinatário
    const activeDevices = this.repository.findActiveDevicesForRecipient(event.recipientUid);
    if (activeDevices.length === 0) {
      // 0 dispositivos: SUPPRESSED_NO_DEVICE (T17.5 §38)
      this.repository.markEventStatus(event.id, 'SUPPRESSED', now);
      this.logger.info('notification.event.suppressed', {
        eventId: event.id,
        eventType: event.type,
        reason: 'NO_ACTIVE_DEVICES',
      });
      return;
    }

    // 6. Registra deliveries para cada dispositivo ativo
    this.repository.createDeliveries(
      activeDevices.map((d) => ({
        eventId: event.id,
        deviceRegistrationId: d.id,
      })),
    );

    // 7. Envio para cada dispositivo (fora de transação de banco, T17.5 §42/§179)
    const deliveries = this.repository.findDeliveriesForEvent(event.id);
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
        this.repository.updateDelivery(
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
        this.repository.updateDelivery(
          event.id,
          delivery.deviceRegistrationId,
          'FAILED_PERMANENT',
          attemptCount,
          null,
          result.errorCode ?? 'PERMANENT_ERROR',
          null,
        );
        this.repository.disableDeviceByToken(delivery.fcmToken, now);
        this.logger.warn('notification.token.invalidated', {
          eventId: event.id,
          errorCode: result.errorCode,
        });
      } else {
        // Falha transitória: retry com backoff (T17.5 §76–§78)
        if (attemptCount >= this.config.pushMaxAttempts) {
          this.repository.updateDelivery(
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
          this.repository.updateDelivery(
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
      this.repository.markEventStatus(event.id, 'COMPLETED', now);
      this.logger.info('notification.event.completed', {
        eventId: event.id,
        eventType: event.type,
      });
    }
  }

  private checkRelevance(
    event: NotificationEvent,
    now: number,
  ): { relevant: boolean; reason?: string } {
    // Verifica se o destinatário está ativo
    const recipientActive = this.db
      .prepare(`SELECT 1 FROM social_profiles WHERE owner_uid = ? AND status = 'ACTIVE'`)
      .get(event.recipientUid);

    if (!recipientActive) {
      return { relevant: false, reason: 'RECIPIENT_DISABLED' };
    }

    switch (event.type) {
      case 'FRIEND_REQUEST_RECEIVED': {
        // Só entrega se a solicitação ainda estiver PENDING (T17.5 §64)
        const row = this.db
          .prepare(`SELECT status FROM friend_requests WHERE request_id = ?`)
          .get(event.entityId) as { status: string } | undefined;

        if (!row || row.status !== 'PENDING') {
          return { relevant: false, reason: 'REQUEST_NO_LONGER_PENDING' };
        }
        return { relevant: true };
      }

      case 'FRIEND_REQUEST_ACCEPTED': {
        // Confirma que o pedido foi de fato aceito
        const row = this.db
          .prepare(
            `SELECT status, requester_uid, recipient_uid FROM friend_requests WHERE request_id = ?`,
          )
          .get(event.entityId) as
          | { status: string; requester_uid: string; recipient_uid: string }
          | undefined;

        if (!row || row.status !== 'ACCEPTED') {
          return { relevant: false, reason: 'REQUEST_NOT_ACCEPTED' };
        }

        // Confirma se a amizade ainda existe no momento do envio (T17.5 §65)
        const [userA, userB] = canonicalPair(row.requester_uid, row.recipient_uid);
        const friendship = this.db
          .prepare(`SELECT 1 FROM friendships WHERE user_a_uid = ? AND user_b_uid = ?`)
          .get(userA, userB);

        if (!friendship) {
          return { relevant: false, reason: 'FRIENDSHIP_REMOVED' };
        }
        return { relevant: true };
      }

      case 'CHALLENGE_INVITATION_RECEIVED': {
        // Só entrega se convite PENDING e desafio UPCOMING e OPEN (T17.5 §66)
        const row = this.db
          .prepare(
            `SELECT i.status as inv_status, c.lifecycle, c.starts_at
             FROM challenge_invitations i
             JOIN challenges c ON c.challenge_id = i.challenge_id
             WHERE i.invitation_id = ?`,
          )
          .get(event.entityId) as
          | { inv_status: string; lifecycle: string; starts_at: number }
          | undefined;

        if (
          !row ||
          row.inv_status !== 'PENDING' ||
          row.lifecycle !== 'OPEN' ||
          now >= row.starts_at
        ) {
          return { relevant: false, reason: 'INVITATION_NO_LONGER_VALID' };
        }
        return { relevant: true };
      }

      case 'CHALLENGE_STARTING_SOON': {
        // Só entrega se participante JOINED, desafio OPEN e now < startsAt (T17.5 §67)
        const row = this.db
          .prepare(
            `SELECT p.status as part_status, c.lifecycle, c.starts_at
             FROM challenge_participants p
             JOIN challenges c ON c.challenge_id = p.challenge_id
             WHERE p.challenge_id = ? AND p.participant_uid = ?`,
          )
          .get(event.entityId, event.recipientUid) as
          | { part_status: string; lifecycle: string; starts_at: number }
          | undefined;

        if (!row || row.part_status !== 'JOINED' || row.lifecycle !== 'OPEN') {
          return { relevant: false, reason: 'CHALLENGE_CANCELLED_OR_WITHDRAWN' };
        }

        if (now >= row.starts_at) {
          // Desafio já começou: expira o start-soon em vez de mandar push atrasado (T17.5 §56)
          return { relevant: false, reason: 'EXPIRED' };
        }
        return { relevant: true };
      }

      case 'CHALLENGE_ENDED': {
        // Só entrega se participante JOINED e desafio OPEN e now >= endsAtExclusive (T17.5 §68)
        const row = this.db
          .prepare(
            `SELECT p.status as part_status, c.lifecycle, c.ends_at_exclusive
             FROM challenge_participants p
             JOIN challenges c ON c.challenge_id = p.challenge_id
             WHERE p.challenge_id = ? AND p.participant_uid = ?`,
          )
          .get(event.entityId, event.recipientUid) as
          | { part_status: string; lifecycle: string; ends_at_exclusive: number }
          | undefined;

        if (!row || row.part_status !== 'JOINED' || row.lifecycle !== 'OPEN') {
          return { relevant: false, reason: 'CHALLENGE_CANCELLED_OR_WITHDRAWN' };
        }

        if (now < row.ends_at_exclusive) {
          return { relevant: false, reason: 'CHALLENGE_NOT_ENDED_YET' };
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
    }
  }

  private cleanupOldData(): void {
    const now = this.clock.now();
    const cutoff = now - THIRTY_DAYS_MS;
    const { cleanedEvents } = this.repository.cleanupOldEntries(cutoff);
    if (cleanedEvents > 0) {
      this.logger.info('notification.cleanup', { cleanedEvents });
    }
  }
}
