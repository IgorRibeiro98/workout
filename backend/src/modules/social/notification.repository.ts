import { Injectable } from '@nestjs/common';
import type { Database } from 'better-sqlite3';
import { SqliteService } from '../../database/sqlite.service';
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
  constructor(private readonly sqlite: SqliteService) {}

  private get db(): Database {
    return this.sqlite.connection;
  }

  // --- Preferências de Notificação ----------------------------------------------------

  getPreferences(ownerUid: string): NotificationPreferencesDto {
    const row = this.db
      .prepare(
        `SELECT push_enabled, friend_request_received, friend_request_accepted,
                challenge_invitation_received, challenge_starting_soon, challenge_ended,
                workout_share_received, updated_at
         FROM social_notification_preferences
         WHERE owner_uid = ?`,
      )
      .get(ownerUid) as
      | {
          push_enabled: number;
          friend_request_received: number;
          friend_request_accepted: number;
          challenge_invitation_received: number;
          challenge_starting_soon: number;
          challenge_ended: number;
          workout_share_received: number;
          updated_at: number;
        }
      | undefined;

    if (!row) {
      return {
        pushEnabled: false,
        friendRequestReceived: true,
        friendRequestAccepted: true,
        challengeInvitationReceived: true,
        challengeStartingSoon: true,
        challengeEnded: true,
        workoutShareReceived: true,
        updatedAt: 0,
      };
    }

    return {
      pushEnabled: row.push_enabled === 1,
      friendRequestReceived: row.friend_request_received === 1,
      friendRequestAccepted: row.friend_request_accepted === 1,
      challengeInvitationReceived: row.challenge_invitation_received === 1,
      challengeStartingSoon: row.challenge_starting_soon === 1,
      challengeEnded: row.challenge_ended === 1,
      workoutShareReceived: row.workout_share_received === 1,
      updatedAt: row.updated_at,
    };
  }

  upsertPreferences(
    ownerUid: string,
    updates: UpdateNotificationPreferencesRequest,
    now: number,
  ): NotificationPreferencesDto {
    const current = this.getPreferences(ownerUid);
    const updated: NotificationPreferencesDto = {
      pushEnabled: updates.pushEnabled ?? current.pushEnabled,
      friendRequestReceived: updates.friendRequestReceived ?? current.friendRequestReceived,
      friendRequestAccepted: updates.friendRequestAccepted ?? current.friendRequestAccepted,
      challengeInvitationReceived:
        updates.challengeInvitationReceived ?? current.challengeInvitationReceived,
      challengeStartingSoon: updates.challengeStartingSoon ?? current.challengeStartingSoon,
      challengeEnded: updates.challengeEnded ?? current.challengeEnded,
      workoutShareReceived: updates.workoutShareReceived ?? current.workoutShareReceived,
      updatedAt: now,
    };

    this.db
      .prepare(
        `INSERT INTO social_notification_preferences
           (owner_uid, push_enabled, friend_request_received, friend_request_accepted,
            challenge_invitation_received, challenge_starting_soon, challenge_ended,
            workout_share_received, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(owner_uid) DO UPDATE SET
           push_enabled = excluded.push_enabled,
           friend_request_received = excluded.friend_request_received,
           friend_request_accepted = excluded.friend_request_accepted,
           challenge_invitation_received = excluded.challenge_invitation_received,
           challenge_starting_soon = excluded.challenge_starting_soon,
           challenge_ended = excluded.challenge_ended,
           workout_share_received = excluded.workout_share_received,
           updated_at = excluded.updated_at`,
      )
      .run(
        ownerUid,
        updated.pushEnabled ? 1 : 0,
        updated.friendRequestReceived ? 1 : 0,
        updated.friendRequestAccepted ? 1 : 0,
        updated.challengeInvitationReceived ? 1 : 0,
        updated.challengeStartingSoon ? 1 : 0,
        updated.challengeEnded ? 1 : 0,
        updated.workoutShareReceived ? 1 : 0,
        now,
      );

    return updated;
  }

  // --- Dispositivos Push -------------------------------------------------------------

  registerDevice(
    registrationId: string,
    ownerUid: string,
    input: RegisterPushDeviceRequest,
    now: number,
  ): PushDeviceRegistrationDto {
    return this.db.transaction(() => {
      // Cenário de account switch (mesmo fcmToken registrado anteriormente para outra conta):
      // remove atomicamente da conta anterior para garantir que um token nunca pertença a 2 contas simultaneamente.
      this.db
        .prepare(`DELETE FROM social_push_devices WHERE fcm_token = ? AND owner_uid != ?`)
        .run(input.fcmToken, ownerUid);

      // Upsert para o mesmo aparelho (owner_uid, device_id): se o token rotacionou, atualiza
      const existing = this.db
        .prepare(
          `SELECT id, created_at FROM social_push_devices WHERE owner_uid = ? AND device_id = ?`,
        )
        .get(ownerUid, input.deviceId) as { id: string; created_at: number } | undefined;

      const id = existing?.id ?? registrationId;
      const createdAt = existing?.created_at ?? now;

      this.db
        .prepare(
          `INSERT INTO social_push_devices
             (id, owner_uid, device_id, fcm_token, platform, enabled, created_at, updated_at, last_registered_at)
           VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
           ON CONFLICT(owner_uid, device_id) DO UPDATE SET
             fcm_token = excluded.fcm_token,
             platform = excluded.platform,
             enabled = 1,
             updated_at = excluded.updated_at,
             last_registered_at = excluded.last_registered_at`,
        )
        .run(id, ownerUid, input.deviceId, input.fcmToken, input.platform, createdAt, now, now);

      return {
        id,
        deviceId: input.deviceId,
        platform: input.platform,
        enabled: true,
        createdAt,
        updatedAt: now,
        lastRegisteredAt: now,
      };
    })();
  }

  unregisterDevice(ownerUid: string, deviceId: string): boolean {
    const result = this.db
      .prepare(`DELETE FROM social_push_devices WHERE owner_uid = ? AND device_id = ?`)
      .run(ownerUid, deviceId);
    return result.changes > 0;
  }

  disableDevicesForOwner(ownerUid: string, now: number): void {
    this.db
      .prepare(`UPDATE social_push_devices SET enabled = 0, updated_at = ? WHERE owner_uid = ?`)
      .run(now, ownerUid);
  }

  disableDeviceByToken(fcmToken: string, now: number): void {
    this.db
      .prepare(`UPDATE social_push_devices SET enabled = 0, updated_at = ? WHERE fcm_token = ?`)
      .run(now, fcmToken);
  }

  findActiveDevicesForRecipient(recipientUid: string): Array<{ id: string; fcmToken: string }> {
    const rows = this.db
      .prepare(
        `SELECT id, fcm_token
         FROM social_push_devices
         WHERE owner_uid = ? AND enabled = 1`,
      )
      .all(recipientUid) as Array<{ id: string; fcm_token: string }>;

    return rows.map((r) => ({ id: r.id, fcmToken: r.fcm_token }));
  }

  // --- Eventos de Notificação (Transactional Outbox) ----------------------------------

  createEvent(event: CreateEventInput, now: number, externalDb?: Database): boolean {
    const db = externalDb ?? this.db;
    const result = db
      .prepare(
        `INSERT INTO social_notification_events
           (id, recipient_uid, type, entity_id, dedupe_key, deliver_after, expires_at, status, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
         ON CONFLICT(dedupe_key) DO NOTHING`,
      )
      .run(
        event.id,
        event.recipientUid,
        event.type,
        event.entityId,
        event.dedupeKey,
        event.deliverAfter,
        event.expiresAt,
        now,
      );

    return result.changes > 0;
  }

  findDueEvents(now: number, limit: number): NotificationEvent[] {
    const rows = this.db
      .prepare(
        `SELECT id, recipient_uid, type, entity_id, dedupe_key, deliver_after, expires_at, status, created_at, completed_at
         FROM social_notification_events
         WHERE status = 'PENDING' AND deliver_after <= ? AND expires_at > ?
         ORDER BY deliver_after ASC
         LIMIT ?`,
      )
      .all(now, now, limit) as Array<{
      id: string;
      recipient_uid: string;
      type: NotificationType;
      entity_id: string;
      dedupe_key: string;
      deliver_after: number;
      expires_at: number;
      status: NotificationEventStatus;
      created_at: number;
      completed_at: number | null;
    }>;

    return rows.map((r) => ({
      id: r.id,
      recipientUid: r.recipient_uid,
      type: r.type,
      entityId: r.entity_id,
      dedupeKey: r.dedupe_key,
      deliverAfter: r.deliver_after,
      expiresAt: r.expires_at,
      status: r.status,
      createdAt: r.created_at,
      completedAt: r.completed_at,
    }));
  }

  markEventStatus(id: string, status: NotificationEventStatus, completedAt?: number): void {
    this.db
      .prepare(
        `UPDATE social_notification_events
         SET status = ?, completed_at = ?
         WHERE id = ?`,
      )
      .run(status, completedAt ?? null, id);
  }

  markExpiredEvents(now: number): number {
    const result = this.db
      .prepare(
        `UPDATE social_notification_events
         SET status = 'EXPIRED', completed_at = ?
         WHERE status = 'PENDING' AND expires_at <= ?`,
      )
      .run(now, now);
    return result.changes;
  }

  cancelEventsForEntity(entityId: string, types?: readonly NotificationType[]): number {
    if (types && types.length > 0) {
      const placeholders = types.map(() => '?').join(', ');
      const result = this.db
        .prepare(
          `UPDATE social_notification_events
           SET status = 'CANCELLED'
           WHERE entity_id = ? AND status = 'PENDING' AND type IN (${placeholders})`,
        )
        .run(entityId, ...types);
      return result.changes;
    }

    const result = this.db
      .prepare(
        `UPDATE social_notification_events
         SET status = 'CANCELLED'
         WHERE entity_id = ? AND status = 'PENDING'`,
      )
      .run(entityId);
    return result.changes;
  }

  cancelEventsForEntityAndRecipient(
    entityId: string,
    recipientUid: string,
    types?: readonly NotificationType[],
  ): number {
    if (types && types.length > 0) {
      const placeholders = types.map(() => '?').join(', ');
      const result = this.db
        .prepare(
          `UPDATE social_notification_events
           SET status = 'CANCELLED'
           WHERE entity_id = ? AND recipient_uid = ? AND status = 'PENDING' AND type IN (${placeholders})`,
        )
        .run(entityId, recipientUid, ...types);
      return result.changes;
    }

    const result = this.db
      .prepare(
        `UPDATE social_notification_events
         SET status = 'CANCELLED'
         WHERE entity_id = ? AND recipient_uid = ? AND status = 'PENDING'`,
      )
      .run(entityId, recipientUid);
    return result.changes;
  }

  // --- Entregas por Dispositivo -------------------------------------------------------

  createDeliveries(deliveries: Array<{ eventId: string; deviceRegistrationId: string }>): void {
    if (deliveries.length === 0) return;

    this.db.transaction(() => {
      const stmt = this.db.prepare(
        `INSERT INTO social_notification_deliveries
           (event_id, device_registration_id, status, attempt_count)
         VALUES (?, ?, 'PENDING', 0)
         ON CONFLICT(event_id, device_registration_id) DO NOTHING`,
      );

      for (const d of deliveries) {
        stmt.run(d.eventId, d.deviceRegistrationId);
      }
    })();
  }

  findDeliveriesForEvent(eventId: string): StoredPendingDelivery[] {
    const rows = this.db
      .prepare(
        `SELECT d.event_id, d.device_registration_id, d.status, d.attempt_count, d.next_attempt_at, dev.fcm_token
         FROM social_notification_deliveries d
         JOIN social_push_devices dev ON dev.id = d.device_registration_id
         WHERE d.event_id = ?`,
      )
      .all(eventId) as Array<{
      event_id: string;
      device_registration_id: string;
      status: DeliveryStatus;
      attempt_count: number;
      next_attempt_at: number | null;
      fcm_token: string;
    }>;

    return rows.map((r) => ({
      eventId: r.event_id,
      deviceRegistrationId: r.device_registration_id,
      fcmToken: r.fcm_token,
      attemptCount: r.attempt_count,
      status: r.status,
      nextAttemptAt: r.next_attempt_at,
    }));
  }

  updateDelivery(
    eventId: string,
    deviceRegistrationId: string,
    status: DeliveryStatus,
    attemptCount: number,
    nextAttemptAt: number | null,
    lastErrorCode: string | null,
    sentAt: number | null,
  ): void {
    this.db
      .prepare(
        `UPDATE social_notification_deliveries
         SET status = ?, attempt_count = ?, next_attempt_at = ?, last_error_code = ?, sent_at = ?
         WHERE event_id = ? AND device_registration_id = ?`,
      )
      .run(
        status,
        attemptCount,
        nextAttemptAt,
        lastErrorCode,
        sentAt,
        eventId,
        deviceRegistrationId,
      );
  }

  cleanupOldEntries(beforeMs: number): { cleanedEvents: number; cleanedDeliveries: number } {
    return this.db.transaction(() => {
      // CASCADE remove deliveries vinculadas aos eventos removidos
      const eventsResult = this.db
        .prepare(
          `DELETE FROM social_notification_events
           WHERE status IN ('COMPLETED', 'SUPPRESSED', 'EXPIRED', 'CANCELLED')
             AND (completed_at IS NOT NULL AND completed_at < ? OR created_at < ?)`,
        )
        .run(beforeMs, beforeMs);

      return {
        cleanedEvents: eventsResult.changes,
        cleanedDeliveries: 0,
      };
    })();
  }
}
