/**
 * O contrato de notificações sociais (T17.5).
 *
 * ## Princípios Fundamentais
 *
 * 1. **Push é sinal best-effort, nunca source of truth**:
 *    Receber um push convida a abrir o app; o app então consulta o backend para o estado canônico.
 * 2. **Sem dados pessoais ou de treino em push**:
 *    O payload FCM é data-only e mínimo: apenas versão, eventId, type, recipientSocialId e entityId.
 *    Nunca contém UID, email, displayName, dados de séries, XP ou ranking.
 * 3. **Tokens FCM nunca são expostos**:
 *    Tokens FCM não são autenticação, não são devolvidos em endpoints de leitura e nunca são logados.
 * 4. **Isolamento estrito entre contas**:
 *    O token é associado atomicamente ao owner_uid autenticado pelo Firebase ID Token.
 */

export const NOTIFICATION_TYPES = [
  'FRIEND_REQUEST_RECEIVED',
  'FRIEND_REQUEST_ACCEPTED',
  'CHALLENGE_INVITATION_RECEIVED',
  'CHALLENGE_STARTING_SOON',
  'CHALLENGE_ENDED',
] as const;

export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

export function isNotificationType(value: unknown): value is NotificationType {
  return typeof value === 'string' && (NOTIFICATION_TYPES as readonly string[]).includes(value);
}

export const NOTIFICATION_EVENT_STATUSES = [
  'PENDING',
  'COMPLETED',
  'SUPPRESSED',
  'EXPIRED',
  'CANCELLED',
] as const;

export type NotificationEventStatus = (typeof NOTIFICATION_EVENT_STATUSES)[number];

export const DELIVERY_STATUSES = [
  'PENDING',
  'SENT',
  'FAILED_PERMANENT',
  'FAILED_TRANSIENT',
] as const;

export type DeliveryStatus = (typeof DELIVERY_STATUSES)[number];

export const SUPPORTED_PLATFORMS = ['ANDROID'] as const;
export type SupportedPlatform = (typeof SUPPORTED_PLATFORMS)[number];

/**
 * Preferências de notificação do usuário.
 *
 * Defaults:
 * - pushEnabled: false (master switch desligado até opt-in explícito do usuário)
 * - categorias: todas true quando ativadas
 */
export interface NotificationPreferencesDto {
  readonly pushEnabled: boolean;
  readonly friendRequestReceived: boolean;
  readonly friendRequestAccepted: boolean;
  readonly challengeInvitationReceived: boolean;
  readonly challengeStartingSoon: boolean;
  readonly challengeEnded: boolean;
  readonly updatedAt: number;
}

export interface UpdateNotificationPreferencesRequest {
  readonly pushEnabled?: boolean;
  readonly friendRequestReceived?: boolean;
  readonly friendRequestAccepted?: boolean;
  readonly challengeInvitationReceived?: boolean;
  readonly challengeStartingSoon?: boolean;
  readonly challengeEnded?: boolean;
}

export interface RegisterPushDeviceRequest {
  readonly deviceId: string;
  readonly platform: SupportedPlatform;
  readonly fcmToken: string;
}

export interface PushDeviceRegistrationDto {
  readonly id: string;
  readonly deviceId: string;
  readonly platform: SupportedPlatform;
  readonly enabled: boolean;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly lastRegisteredAt: number;
}

/** Evento durável persistido no backend (Transactional Outbox). */
export interface NotificationEvent {
  readonly id: string;
  readonly recipientUid: string;
  readonly type: NotificationType;
  readonly entityId: string;
  readonly dedupeKey: string;
  readonly deliverAfter: number;
  readonly expiresAt: number;
  readonly status: NotificationEventStatus;
  readonly createdAt: number;
  readonly completedAt?: number | null;
}

/** Registro de tentativa de entrega por dispositivo registrado. */
export interface NotificationDelivery {
  readonly eventId: string;
  readonly deviceRegistrationId: string;
  readonly status: DeliveryStatus;
  readonly attemptCount: number;
  readonly nextAttemptAt?: number | null;
  readonly lastErrorCode?: string | null;
  readonly sentAt?: number | null;
}
