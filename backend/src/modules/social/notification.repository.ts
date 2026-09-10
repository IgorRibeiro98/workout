import { Injectable } from '@nestjs/common';
import { DbClient, PostgresService, type PoolClient } from '../../database/postgres.service';
import type {
  DeliveryStatus,
  NotificationEvent,
  NotificationEventStatus,
  NotificationPreferencesDto,
  NotificationType,
  PushDeviceRegistrationDto,
  RegisterPushDeviceRequest,
  SupportedPlatform,
  UpdateNotificationPreferencesRequest,
} from './notification.contract';

export interface CreateEventInput {
  readonly id: string;
  readonly recipientUid: string;
  readonly type: NotificationType;
  readonly entityId: string;
  readonly dedupeKey: string;
  readonly deliverAfter: number;
  readonly expiresAt: number;
}

export interface StoredPushDevice {
  readonly id: string;
  readonly ownerUid: string;
  readonly deviceId: string;
  readonly fcmToken: string;
  readonly platform: SupportedPlatform;
  readonly enabled: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly lastRegisteredAt: number;
}

export interface StoredPendingDelivery {
  readonly eventId: string;
  readonly deviceRegistrationId: string;
  readonly fcmToken: string;
  readonly attemptCount: number;
  readonly status: DeliveryStatus;
  readonly nextAttemptAt?: number | null;
}

@Injectable()
export class NotificationRepository {
  constructor(private readonly db: PostgresService) {}

  private getRunner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  // --- Preferências de Notificação ----------------------------------------------------

  async getPreferences(ownerUid: string): Promise<NotificationPreferencesDto> {
    const res = await this.db.query<{
      push_enabled: boolean | number;
      friend_request_received: boolean | number;
      friend_request_accepted: boolean | number;
      challenge_invitation_received: boolean | number;
      challenge_starting_soon: boolean | number;
      challenge_ended: boolean | number;
      workout_share_received: boolean | number;
      group_invitation_received: boolean | number;
      updated_at: string | number;
    }>(
      `SELECT push_enabled, friend_request_received, friend_request_accepted,
              challenge_invitation_received, challenge_starting_soon, challenge_ended,
              workout_share_received, group_invitation_received, updated_at
       FROM social_notification_preferences
       WHERE owner_uid = $1`,
      [ownerUid],
    );

    const row = res.rows[0];
    if (!row) {
      return {
        pushEnabled: false,
        friendRequestReceived: true,
        friendRequestAccepted: true,
        challengeInvitationReceived: true,
        challengeStartingSoon: true,
        challengeEnded: true,
        workoutShareReceived: true,
        groupInvitationReceived: true,
        updatedAt: 0,
      };
    }

    return {
      pushEnabled: Boolean(row.push_enabled),
      friendRequestReceived: Boolean(row.friend_request_received),
      friendRequestAccepted: Boolean(row.friend_request_accepted),
      challengeInvitationReceived: Boolean(row.challenge_invitation_received),
      challengeStartingSoon: Boolean(row.challenge_starting_soon),
      challengeEnded: Boolean(row.challenge_ended),
      workoutShareReceived: Boolean(row.workout_share_received),
      groupInvitationReceived: Boolean(row.group_invitation_received),
      updatedAt: Number(row.updated_at),
    };
  }

  async upsertPreferences(
    ownerUid: string,
    updates: Partial<Omit<NotificationPreferencesDto, 'updatedAt'>>,
    now: number,
    client?: PoolClient,
  ): Promise<NotificationPreferencesDto> {
    const q = this.getRunner(client);
    const current = (await this.getPreferences(ownerUid)) ?? {
      pushEnabled: true,
      friendRequestReceived: true,
      friendRequestAccepted: true,
      challengeInvitationReceived: true,
      challengeStartingSoon: true,
      challengeEnded: true,
      workoutShareReceived: true,
      groupInvitationReceived: true,
      updatedAt: now,
    };

    const updated: NotificationPreferencesDto = {
      pushEnabled: updates.pushEnabled ?? current.pushEnabled,
      friendRequestReceived: updates.friendRequestReceived ?? current.friendRequestReceived,
      friendRequestAccepted: updates.friendRequestAccepted ?? current.friendRequestAccepted,
      challengeInvitationReceived:
        updates.challengeInvitationReceived ?? current.challengeInvitationReceived,
      challengeStartingSoon: updates.challengeStartingSoon ?? current.challengeStartingSoon,
      challengeEnded: updates.challengeEnded ?? current.challengeEnded,
      workoutShareReceived: updates.workoutShareReceived ?? current.workoutShareReceived,
      groupInvitationReceived: updates.groupInvitationReceived ?? current.groupInvitationReceived,
      updatedAt: now,
    };

    await q.query(
      `INSERT INTO social_notification_preferences
         (owner_uid, push_enabled, friend_request_received, friend_request_accepted,
          challenge_invitation_received, challenge_starting_soon, challenge_ended,
          workout_share_received, group_invitation_received, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       ON CONFLICT(owner_uid) DO UPDATE SET
         push_enabled = EXCLUDED.push_enabled,
         friend_request_received = EXCLUDED.friend_request_received,
         friend_request_accepted = EXCLUDED.friend_request_accepted,
         challenge_invitation_received = EXCLUDED.challenge_invitation_received,
         challenge_starting_soon = EXCLUDED.challenge_starting_soon,
         challenge_ended = EXCLUDED.challenge_ended,
         workout_share_received = EXCLUDED.workout_share_received,
         group_invitation_received = EXCLUDED.group_invitation_received,
         updated_at = EXCLUDED.updated_at`,
      [
        ownerUid,
        updated.pushEnabled,
        updated.friendRequestReceived,
        updated.friendRequestAccepted,
        updated.challengeInvitationReceived,
        updated.challengeStartingSoon,
        updated.challengeEnded,
        updated.workoutShareReceived,
        updated.groupInvitationReceived,
        now,
      ],
    );

    return updated;
  }

  // --- Dispositivos Push -------------------------------------------------------------

  async registerDevice(
    registrationId: string,
    ownerUid: string,
    input: RegisterPushDeviceRequest,
    now: number,
  ): Promise<PushDeviceRegistrationDto> {
    return await this.db.transaction(async (client) => {
      // Cenário de account switch (mesmo fcmToken registrado anteriormente para outra conta):
      // remove atomicamente da conta anterior para garantir que um token nunca pertença a 2 contas simultaneamente.
      await client.query(
        `DELETE FROM social_push_devices WHERE fcm_token = $1 AND owner_uid != $2`,
        [input.fcmToken, ownerUid],
      );

      // Upsert para o mesmo aparelho (owner_uid, device_id): se o token rotacionou, atualiza
      const existingRes = await client.query<{ id: string; created_at: string | number }>(
        `SELECT id, created_at FROM social_push_devices WHERE owner_uid = $1 AND device_id = $2`,
        [ownerUid, input.deviceId],
      );
      const existing = existingRes.rows[0];

      const id = existing?.id ?? registrationId;
      const createdAt = existing ? Number(existing.created_at) : now;

      await client.query(
        `INSERT INTO social_push_devices
           (id, owner_uid, device_id, fcm_token, platform, enabled, created_at, updated_at, last_registered_at)
         VALUES ($1, $2, $3, $4, $5, TRUE, $6, $7, $8)
         ON CONFLICT(owner_uid, device_id) DO UPDATE SET
           fcm_token = EXCLUDED.fcm_token,
           platform = EXCLUDED.platform,
           enabled = TRUE,
           updated_at = EXCLUDED.updated_at,
           last_registered_at = EXCLUDED.last_registered_at`,
        [id, ownerUid, input.deviceId, input.fcmToken, input.platform, createdAt, now, now],
      );

      return {
        id,
        deviceId: input.deviceId,
        platform: input.platform,
        enabled: true,
        createdAt,
        updatedAt: now,
        lastRegisteredAt: now,
      };
    });
  }

  async unregisterDevice(ownerUid: string, deviceId: string): Promise<boolean> {
    const result = await this.db.query(
      `DELETE FROM social_push_devices WHERE owner_uid = $1 AND device_id = $2`,
      [ownerUid, deviceId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  async disableDevicesForOwner(ownerUid: string, now: number, client?: PoolClient): Promise<void> {
    const q = this.getRunner(client);
    await q.query(
      `UPDATE social_push_devices SET enabled = FALSE, updated_at = $1 WHERE owner_uid = $2`,
      [now, ownerUid],
    );
  }

  async disableDeviceByToken(fcmToken: string, now: number): Promise<void> {
    await this.db.query(
      `UPDATE social_push_devices SET enabled = FALSE, updated_at = $1 WHERE fcm_token = $2`,
      [now, fcmToken],
    );
  }

  async findActiveDevicesForRecipient(recipientUid: string): Promise<Array<{ id: string; fcmToken: string }>> {
    const res = await this.db.query<{ id: string; fcm_token: string }>(
      `SELECT id, fcm_token
       FROM social_push_devices
       WHERE owner_uid = $1 AND enabled = TRUE`,
      [recipientUid],
    );

    return res.rows.map((r) => ({ id: r.id, fcmToken: r.fcm_token }));
  }

  // --- Eventos de Notificação (Transactional Outbox) ----------------------------------

  async createEvent(event: CreateEventInput, now: number, externalClient?: PoolClient): Promise<boolean> {
    const q = this.getRunner(externalClient);
    const result = await q.query(
      `INSERT INTO social_notification_events
         (id, recipient_uid, type, entity_id, dedupe_key, deliver_after, expires_at, status, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, 'PENDING', $8)
       ON CONFLICT(dedupe_key) DO NOTHING`,
      [
        event.id,
        event.recipientUid,
        event.type,
        event.entityId,
        event.dedupeKey,
        event.deliverAfter,
        event.expiresAt,
        now,
      ],
    );

    return (result.rowCount ?? 0) > 0;
  }

  async findDueEvents(now: number, limit: number): Promise<NotificationEvent[]> {
    const res = await this.db.query<{
      id: string;
      recipient_uid: string;
      type: NotificationType;
      entity_id: string;
      dedupe_key: string;
      deliver_after: string | number;
      expires_at: string | number;
      status: NotificationEventStatus;
      created_at: string | number;
      completed_at: string | number | null;
    }>(
      `SELECT id, recipient_uid, type, entity_id, dedupe_key, deliver_after, expires_at, status, created_at, completed_at
       FROM social_notification_events
       WHERE status = 'PENDING' AND deliver_after <= $1 AND expires_at > $2
       ORDER BY deliver_after ASC
       LIMIT $3`,
      [now, now, limit],
    );

    return res.rows.map((r) => ({
      id: r.id,
      recipientUid: r.recipient_uid,
      type: r.type,
      entityId: r.entity_id,
      dedupeKey: r.dedupe_key,
      deliverAfter: Number(r.deliver_after),
      expiresAt: Number(r.expires_at),
      status: r.status,
      createdAt: Number(r.created_at),
      completedAt: r.completed_at !== null ? Number(r.completed_at) : null,
    }));
  }

  async markEventStatus(id: string, status: NotificationEventStatus, completedAt?: number): Promise<void> {
    await this.db.query(
      `UPDATE social_notification_events
       SET status = $1, completed_at = $2
       WHERE id = $3`,
      [status, completedAt ?? null, id],
    );
  }

  async markExpiredEvents(now: number): Promise<number> {
    const result = await this.db.query(
      `UPDATE social_notification_events
       SET status = 'EXPIRED', completed_at = $1
       WHERE status = 'PENDING' AND expires_at <= $2`,
      [now, now],
    );
    return result.rowCount ?? 0;
  }

  async cancelEventsForEntity(entityId: string, types?: readonly NotificationType[]): Promise<number> {
    if (types && types.length > 0) {
      const result = await this.db.query(
        `UPDATE social_notification_events
         SET status = 'CANCELLED'
         WHERE entity_id = $1 AND status = 'PENDING' AND type = ANY($2::text[])`,
        [entityId, types as string[]],
      );
      return result.rowCount ?? 0;
    }

    const result = await this.db.query(
      `UPDATE social_notification_events
       SET status = 'CANCELLED'
       WHERE entity_id = $1 AND status = 'PENDING'`,
      [entityId],
    );
    return result.rowCount ?? 0;
  }

  async cancelEventsForEntityAndRecipient(
    entityId: string,
    recipientUid: string,
    types?: readonly NotificationType[],
  ): Promise<number> {
    if (types && types.length > 0) {
      const result = await this.db.query(
        `UPDATE social_notification_events
         SET status = 'CANCELLED'
         WHERE entity_id = $1 AND recipient_uid = $2 AND status = 'PENDING' AND type = ANY($3::text[])`,
        [entityId, recipientUid, types as string[]],
      );
      return result.rowCount ?? 0;
    }

    const result = await this.db.query(
      `UPDATE social_notification_events
       SET status = 'CANCELLED'
       WHERE entity_id = $1 AND recipient_uid = $2 AND status = 'PENDING'`,
      [entityId, recipientUid],
    );
    return result.rowCount ?? 0;
  }

  // --- Entregas por Dispositivo -------------------------------------------------------

  async createDeliveries(deliveries: Array<{ eventId: string; deviceRegistrationId: string }>): Promise<void> {
    if (deliveries.length === 0) return;

    await this.db.transaction(async (client) => {
      for (const d of deliveries) {
        await client.query(
          `INSERT INTO social_notification_deliveries
             (event_id, device_registration_id, status, attempt_count)
           VALUES ($1, $2, 'PENDING', 0)
           ON CONFLICT(event_id, device_registration_id) DO NOTHING`,
          [d.eventId, d.deviceRegistrationId],
        );
      }
    });
  }

  async findDeliveriesForEvent(eventId: string): Promise<StoredPendingDelivery[]> {
    const res = await this.db.query<{
      event_id: string;
      device_registration_id: string;
      status: DeliveryStatus;
      attempt_count: number;
      next_attempt_at: string | number | null;
      fcm_token: string;
    }>(
      `SELECT d.event_id, d.device_registration_id, d.status, d.attempt_count, d.next_attempt_at, dev.fcm_token
       FROM social_notification_deliveries d
       JOIN social_push_devices dev ON dev.id = d.device_registration_id
       WHERE d.event_id = $1`,
      [eventId],
    );

    return res.rows.map((r) => ({
      eventId: r.event_id,
      deviceRegistrationId: r.device_registration_id,
      fcmToken: r.fcm_token,
      attemptCount: r.attempt_count,
      status: r.status,
      nextAttemptAt: r.next_attempt_at !== null ? Number(r.next_attempt_at) : null,
    }));
  }

  async updateDelivery(
    eventId: string,
    deviceRegistrationId: string,
    status: DeliveryStatus,
    attemptCount: number,
    nextAttemptAt: number | null,
    lastErrorCode: string | null,
    sentAt: number | null,
  ): Promise<void> {
    await this.db.query(
      `UPDATE social_notification_deliveries
       SET status = $1, attempt_count = $2, next_attempt_at = $3, last_error_code = $4, sent_at = $5
       WHERE event_id = $6 AND device_registration_id = $7`,
      [
        status,
        attemptCount,
        nextAttemptAt,
        lastErrorCode,
        sentAt,
        eventId,
        deviceRegistrationId,
      ],
    );
  }

  async cleanupOldEntries(beforeMs: number): Promise<{ cleanedEvents: number; cleanedDeliveries: number }> {
    const eventsResult = await this.db.query(
      `DELETE FROM social_notification_events
       WHERE status IN ('COMPLETED', 'SUPPRESSED', 'EXPIRED', 'CANCELLED')
         AND (completed_at IS NOT NULL AND completed_at < $1 OR created_at < $2)`,
      [beforeMs, beforeMs],
    );

    return {
      cleanedEvents: eventsResult.rowCount ?? 0,
      cleanedDeliveries: 0,
    };
  }
}
