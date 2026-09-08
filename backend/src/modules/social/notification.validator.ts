import { SocialErrors } from './social.errors';
import {
  SUPPORTED_PLATFORMS,
  type RegisterPushDeviceRequest,
  type UpdateNotificationPreferencesRequest,
} from './notification.contract';

const SERVER_OWNED_FIELDS = [
  'id',
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'socialId',
  'social_id',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  'lastRegisteredAt',
  'last_registered_at',
] as const;

export function validateRegisterPushDevice(
  body: unknown,
  contentLength?: number,
): RegisterPushDeviceRequest {
  if (contentLength !== undefined && contentLength > 4096) {
    throw SocialErrors.invalid('corpo da requisição excede o limite máximo');
  }

  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw SocialErrors.invalid('o corpo precisa ser um objeto JSON');
  }

  const record = body as Record<string, unknown>;

  for (const forbidden of SERVER_OWNED_FIELDS) {
    if (forbidden in record) {
      throw SocialErrors.invalid(`campo ${forbidden} é decidido pelo servidor`);
    }
  }

  const allowedKeys = new Set(['deviceId', 'platform', 'fcmToken']);
  for (const key of Object.keys(record)) {
    if (!allowedKeys.has(key)) {
      throw SocialErrors.invalid(`campo não reconhecido: ${key}`);
    }
  }

  const { deviceId, platform, fcmToken } = record;

  if (typeof deviceId !== 'string' || deviceId.trim().length === 0) {
    throw SocialErrors.invalid('deviceId precisa ser uma string não vazia');
  }
  if (deviceId.length > 128) {
    throw SocialErrors.invalid('deviceId longo demais');
  }

  if (
    typeof platform !== 'string' ||
    !(SUPPORTED_PLATFORMS as readonly string[]).includes(platform)
  ) {
    throw SocialErrors.invalid(
      `platform inválida: esperada uma de ${SUPPORTED_PLATFORMS.join(', ')}`,
    );
  }

  if (typeof fcmToken !== 'string' || fcmToken.trim().length === 0) {
    throw SocialErrors.invalid('fcmToken precisa ser uma string não vazia');
  }
  if (fcmToken.length > 4096) {
    throw SocialErrors.invalid('fcmToken longo demais');
  }

  return {
    deviceId: deviceId.trim(),
    platform: platform as 'ANDROID',
    fcmToken: fcmToken.trim(),
  };
}

export function validateUpdateNotificationPreferences(
  body: unknown,
  contentLength?: number,
): UpdateNotificationPreferencesRequest {
  if (contentLength !== undefined && contentLength > 2048) {
    throw SocialErrors.invalid('corpo da requisição excede o limite máximo');
  }

  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw SocialErrors.invalid('o corpo precisa ser um objeto JSON');
  }

  const record = body as Record<string, unknown>;

  for (const forbidden of SERVER_OWNED_FIELDS) {
    if (forbidden in record) {
      throw SocialErrors.invalid(`campo ${forbidden} é decidido pelo servidor`);
    }
  }

  const allowedKeys = new Set([
    'pushEnabled',
    'friendRequestReceived',
    'friendRequestAccepted',
    'challengeInvitationReceived',
    'challengeStartingSoon',
    'challengeEnded',
  ]);

  for (const key of Object.keys(record)) {
    if (!allowedKeys.has(key)) {
      throw SocialErrors.invalid(`campo não reconhecido: ${key}`);
    }
  }

  const keys = Object.keys(record);
  if (keys.length === 0) {
    throw SocialErrors.invalid('ao menos um campo de preferência precisa ser informado');
  }

  for (const key of keys) {
    if (typeof record[key] !== 'boolean') {
      throw SocialErrors.invalid(`campo ${key} precisa ser um booleano`);
    }
  }

  return {
    pushEnabled: record.pushEnabled as boolean | undefined,
    friendRequestReceived: record.friendRequestReceived as boolean | undefined,
    friendRequestAccepted: record.friendRequestAccepted as boolean | undefined,
    challengeInvitationReceived: record.challengeInvitationReceived as boolean | undefined,
    challengeStartingSoon: record.challengeStartingSoon as boolean | undefined,
    challengeEnded: record.challengeEnded as boolean | undefined,
  };
}
