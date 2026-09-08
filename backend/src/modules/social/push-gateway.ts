import type { NotificationType } from './notification.contract';

export interface PushPayload {
  readonly v: '1';
  readonly eventId: string;
  readonly type: NotificationType;
  readonly recipientSocialId: string;
  readonly entityId: string;
}

export interface PushResult {
  readonly success: boolean;
  readonly errorCode?: string;
  readonly permanent?: boolean;
}

export interface PushGateway {
  send(fcmToken: string, payload: PushPayload): Promise<PushResult>;
}

export const PUSH_GATEWAY = Symbol('PUSH_GATEWAY');
