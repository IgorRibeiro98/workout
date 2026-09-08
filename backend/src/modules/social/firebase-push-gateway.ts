import { Inject, Injectable } from '@nestjs/common';
import { getApps } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { SparkLogger } from '../../common/logger';
import type { PushGateway, PushPayload, PushResult } from './push-gateway';

const ADMIN_APP_NAME = 'spark-backend-auth';

const PERMANENT_ERROR_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

/**
 * Implementação de produção do PushGateway via Firebase Admin SDK (FCM).
 *
 * Princípios de segurança e operação:
 * 1. Data-only payload: nenhuma notificação visual automática criada pelo Google Play Services.
 *    O Android intercepta a data message e valida o escopo de conta antes de exibir.
 * 2. Reutilização de credencial: consome o app Admin 'spark-backend-auth' inicializado por
 *    FirebaseAuthTokenVerifier (mesmo projeto e service account).
 * 3. Classificação de erros: tokens cancelados/inválidos são sinalizados como permanentemente
 *    inválidos para desativação no banco. Falhas de rede/cota/503 recebem retry.
 * 4. Respeito ao log: NUNCA loga fcmToken, payload completo, ou UID.
 */
@Injectable()
export class FirebasePushGateway implements PushGateway {
  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
  ) {}

  async send(fcmToken: string, payload: PushPayload): Promise<PushResult> {
    const adminApp = getApps().find((a) => a.name === ADMIN_APP_NAME);
    if (!adminApp) {
      return {
        success: false,
        errorCode: 'FIREBASE_ADMIN_NOT_INITIALIZED',
        permanent: false,
      };
    }

    try {
      const messaging = getMessaging(adminApp);
      await messaging.send({
        token: fcmToken,
        data: {
          v: payload.v,
          eventId: payload.eventId,
          type: payload.type,
          recipientSocialId: payload.recipientSocialId,
          entityId: payload.entityId,
        },
        android: {
          priority: 'normal',
        },
      });

      return { success: true };
    } catch (error: unknown) {
      const code =
        typeof error === 'object' && error !== null && 'code' in error
          ? String((error as { code: unknown }).code)
          : '';

      const permanent = PERMANENT_ERROR_CODES.has(code);

      this.logger.warn('social.push.delivery.failed', {
        errorCode: code || 'unknown',
        permanent,
        eventType: payload.type,
      });

      return {
        success: false,
        errorCode: code || 'UNKNOWN_ERROR',
        permanent,
      };
    }
  }
}
