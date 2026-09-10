import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PostgresService } from '../src/database/postgres.service';
import { NotificationDispatcher } from '../src/modules/social/notification.dispatcher';
import { NotificationRepository } from '../src/modules/social/notification.repository';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { saoPauloInstant } from './support/challenge-fixtures';
import { FakeClock } from './support/fake-clock';
import { FakePushGateway } from './support/fake-push-gateway';

const TOKEN_A = 'token-user-a';
const UID_A = 'uid-user-a';
const TOKEN_B = 'token-user-b';
const UID_B = 'uid-user-b';
const TOKEN_C = 'token-user-c';
const UID_C = 'uid-user-c';

const INITIAL_TIME = saoPauloInstant('2026-09-08T12:00:00');

describe('T17.5 — Notificações Sociais com Firebase Cloud Messaging', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let pushGateway: FakePushGateway;
  let dispatcher: NotificationDispatcher;
  let repository: NotificationRepository;

  beforeEach(async () => {
    temp = createTempDb();
    clock = new FakeClock(INITIAL_TIME);
    pushGateway = new FakePushGateway();

    const config = configFor(temp.path, {
      SOCIAL_PUSH_ENABLED: 'true',
      PUSH_DISPATCH_INTERVAL_MS: '60000',
      PUSH_MAX_ATTEMPTS: '5',
      PUSH_BATCH_SIZE: '50',
    });

    const verifier = new FakeAuthTokenVerifier()
      .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
      .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
      .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' });

    app = await createTestApp(config, verifier, undefined, clock, pushGateway);
    dispatcher = app.get(NotificationDispatcher);
    repository = app.get(NotificationRepository);
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();

  const activate = (token: string, displayName = 'User') =>
    request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', `Bearer ${token}`)
      .send({ displayName });

  const registerDevice = (token: string, body: object) =>
    request(server())
      .post('/v1/social/notifications/devices')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  const unregisterDevice = (token: string, deviceId: string) =>
    request(server())
      .delete(`/v1/social/notifications/devices/${deviceId}`)
      .set('Authorization', `Bearer ${token}`);

  const getPreferences = (token: string) =>
    request(server())
      .get('/v1/social/notifications/preferences')
      .set('Authorization', `Bearer ${token}`);

  const patchPreferences = (token: string, body: object) =>
    request(server())
      .patch('/v1/social/notifications/preferences')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  const sendFriendRequest = (token: string, recipientSocialId: string) =>
    request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', `Bearer ${token}`)
      .send({ socialId: recipientSocialId });

  const acceptFriendRequest = (token: string, requestId: string) =>
    request(server())
      .post(`/v1/social/friend-requests/${requestId}/accept`)
      .set('Authorization', `Bearer ${token}`);

  const cancelFriendRequest = (token: string, requestId: string) =>
    request(server())
      .post(`/v1/social/friend-requests/${requestId}/cancel`)
      .set('Authorization', `Bearer ${token}`);

  const createChallenge = (token: string, clientRequestId: string, invitedSocialIds: string[]) =>
    request(server()).post('/v1/social/challenges').set('Authorization', `Bearer ${token}`).send({
      clientRequestId,
      name: 'Desafio Teste',
      type: 'WORKOUTS_COMPLETED',
      target: 5,
      startDate: '2026-09-10',
      endDate: '2026-09-20',
      timeZoneId: 'America/Sao_Paulo',
      invitedSocialIds,
    });

  // -----------------------------------------------------------------------------------------
  // 1. Registro e Gerenciamento de Dispositivos (T17.5 §9–§20)
  // -----------------------------------------------------------------------------------------

  describe('Registro de dispositivos push', () => {
    it('exige autenticação Bearer', async () => {
      const res = await request(server())
        .post('/v1/social/notifications/devices')
        .send({ deviceId: 'dev-1', platform: 'ANDROID', fcmToken: 'token-fcm-1' });
      expect(res.status).toBe(401);
    });

    it('exige perfil Social ativo (T17.5 §20)', async () => {
      // User A não ativou o social ainda
      const res = await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'ANDROID',
        fcmToken: 'token-fcm-1',
      });
      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });

    it('registra dispositivo com sucesso para usuário ativo', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'ANDROID',
        fcmToken: 'token-fcm-alice',
      });

      expect(res.status).toBe(201);
      expect(res.body.deviceId).toBe('dev-1');
      expect(res.body.platform).toBe('ANDROID');
      expect(res.body.enabled).toBe(true);
      // NUNCA expõe o token FCM na resposta (T17.5 §12)
      expect(res.body.fcmToken).toBeUndefined();
      expect(res.body.ownerUid).toBeUndefined();
    });

    it('recusa campos controlados pelo servidor como autoridade (T17.5 §18)', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'ANDROID',
        fcmToken: 'token-fcm-1',
        ownerUid: 'hack-uid',
      });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
      expect(res.body.error.message).toContain('ownerUid');
    });

    it('recusa plataformas não suportadas (T17.5 §193)', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'IOS',
        fcmToken: 'token-fcm-1',
      });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
    });

    it('atualiza token quando mesmo dispositivo rotaciona token (T17.5 §14)', async () => {
      await activate(TOKEN_A, 'Alice');

      await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'ANDROID',
        fcmToken: 'token-velho',
      });

      clock.advance(1000);
      const res = await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'ANDROID',
        fcmToken: 'token-novo',
      });

      expect(res.status).toBe(201);

      const devices = await repository.findActiveDevicesForRecipient(UID_A);
      expect(devices).toHaveLength(1);
      expect(devices[0].fcmToken).toBe('token-novo');
    });

    it('transfere atomicamente o token ao trocar de conta no mesmo celular (T17.5 §15)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');

      // Alice usa o celular com token X
      await registerDevice(TOKEN_A, {
        deviceId: 'phone-1',
        platform: 'ANDROID',
        fcmToken: 'token-compartilhado',
      });

      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(1);
      expect(await repository.findActiveDevicesForRecipient(UID_B)).toHaveLength(0);

      // Alice faz logout, Bob faz login no mesmo celular com mesmo token X
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-1',
        platform: 'ANDROID',
        fcmToken: 'token-compartilhado',
      });

      // Token pertence agora atomicamente a Bob, e nunca a ambos simultaneamente
      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(0);
      expect(await repository.findActiveDevicesForRecipient(UID_B)).toHaveLength(1);
      expect((await repository.findActiveDevicesForRecipient(UID_B))[0].fcmToken).toBe(
        'token-compartilhado',
      );
    });

    it('remove dispositivo com unregister', async () => {
      await activate(TOKEN_A, 'Alice');

      await registerDevice(TOKEN_A, {
        deviceId: 'dev-1',
        platform: 'ANDROID',
        fcmToken: 'token-1',
      });

      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(1);

      const res = await unregisterDevice(TOKEN_A, 'dev-1');
      expect(res.status).toBe(204);

      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(0);
    });

    it('não permite unregister de aparelho pertencente a outra conta (T17.5 §189)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');

      await registerDevice(TOKEN_A, {
        deviceId: 'dev-alice',
        platform: 'ANDROID',
        fcmToken: 'token-alice',
      });

      // Bob tenta apagar o aparelho da Alice
      const res = await unregisterDevice(TOKEN_B, 'dev-alice');
      expect(res.status).toBe(204);

      // O aparelho de Alice continua intacto
      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(1);
    });
  });

  // -----------------------------------------------------------------------------------------
  // 2. Preferências de Notificação (T17.5 §21–§29)
  // -----------------------------------------------------------------------------------------

  describe('Preferências de notificação', () => {
    it('retorna defaults seguros: pushEnabled=false, categorias=true (T17.5 §23)', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await getPreferences(TOKEN_A);
      expect(res.status).toBe(200);
      expect(res.body.pushEnabled).toBe(false);
      expect(res.body.friendRequestReceived).toBe(true);
      expect(res.body.friendRequestAccepted).toBe(true);
      expect(res.body.challengeInvitationReceived).toBe(true);
      expect(res.body.challengeStartingSoon).toBe(true);
      expect(res.body.challengeEnded).toBe(true);
    });

    it('atualiza master switch de push parcialmente', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await patchPreferences(TOKEN_A, { pushEnabled: true });
      expect(res.status).toBe(200);
      expect(res.body.pushEnabled).toBe(true);
      expect(res.body.friendRequestReceived).toBe(true); // preservado
    });

    it('atualiza categorias individuais parcialmente', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await patchPreferences(TOKEN_A, {
        pushEnabled: true,
        challengeStartingSoon: false,
      });

      expect(res.status).toBe(200);
      expect(res.body.pushEnabled).toBe(true);
      expect(res.body.challengeStartingSoon).toBe(false);
      expect(res.body.challengeEnded).toBe(true);
    });

    it('recusa corpo vazio ou sem campos válidos no patch', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await patchPreferences(TOKEN_A, {});
      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
    });

    it('recusa campos de servidor no patch', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await patchPreferences(TOKEN_A, {
        pushEnabled: true,
        ownerUid: 'hacker',
      });
      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
    });
  });

  // -----------------------------------------------------------------------------------------
  // 3. Criação Transacional de Eventos (T17.5 §41–§62)
  // -----------------------------------------------------------------------------------------

  describe('Criação transacional de eventos', () => {
    it('cria FRIEND_REQUEST_RECEIVED ao enviar solicitação de amizade (T17.5 §43)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      const res = await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      expect(res.status).toBe(200);

      const events = await repository.findDueEvents(clock.now(), 10);
      expect(events).toHaveLength(1);
      expect(events[0].type).toBe('FRIEND_REQUEST_RECEIVED');
      expect(events[0].recipientUid).toBe(UID_B);
      expect(events[0].entityId).toBe(res.body.request.requestId);
    });

    it('não duplica evento de solicitação em retry idempotente (T17.5 §44)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await sendFriendRequest(TOKEN_A, b.body.profile.socialId); // retry

      const events = await repository.findDueEvents(clock.now(), 10);
      expect(events).toHaveLength(1);
    });

    it('cria FRIEND_REQUEST_ACCEPTED para ambos em cross-request bilateral (T17.5 §45)', async () => {
      const a = await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      // Alice pede para Bob
      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      // Bob pede para Alice (cross-request resolve em amizade imediata)
      const res = await sendFriendRequest(TOKEN_B, a.body.profile.socialId);
      expect(res.status).toBe(200);
      expect(res.body.result).toBe('FRIENDSHIP_CREATED');

      const events = await repository.findDueEvents(clock.now(), 10);
      const acceptedEvents = events.filter((e) => e.type === 'FRIEND_REQUEST_ACCEPTED');
      expect(acceptedEvents).toHaveLength(2);
      expect(acceptedEvents.some((e) => e.recipientUid === UID_A)).toBe(true);
      expect(acceptedEvents.some((e) => e.recipientUid === UID_B)).toBe(true);
    });

    it('cria FRIEND_REQUEST_ACCEPTED ao aceitar solicitação (T17.5 §46)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      const sent = await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      const res = await acceptFriendRequest(TOKEN_B, sent.body.request.requestId);
      expect(res.status).toBe(200);

      const events = await repository.findDueEvents(clock.now(), 10);
      const accepted = events.find((e) => e.type === 'FRIEND_REQUEST_ACCEPTED');
      expect(accepted).toBeDefined();
      expect(accepted?.recipientUid).toBe(UID_A);
    });

    it('cria eventos ao criar desafio: convite, start-soon e ended (T17.5 §50–§52)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      // Make friends first
      const sent = await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await acceptFriendRequest(TOKEN_B, sent.body.request.requestId);

      const res = await createChallenge(TOKEN_A, 'req-chal-1', [b.body.profile.socialId]);
      expect(res.status).toBe(200);
      const challengeId = res.body.challenge.challengeId;
      const startsAt = saoPauloInstant('2026-09-10T00:00:00');
      const endsAtExclusive = saoPauloInstant('2026-09-21T00:00:00');

      // Convite é imediatamente due para Bob
      const currentEvents = await repository.findDueEvents(clock.now(), 50);
      const inviteEvent = currentEvents.find((e) => e.type === 'CHALLENGE_INVITATION_RECEIVED');
      expect(inviteEvent).toBeDefined();
      expect(inviteEvent?.recipientUid).toBe(UID_B);

      // STARTING_SOON é due pouco antes do início
      const startEvents = await repository.findDueEvents(startsAt - 3600 * 1000, 50);
      const startEvent = startEvents.find(
        (e) =>
          e.type === 'CHALLENGE_STARTING_SOON' &&
          e.recipientUid === UID_A &&
          e.entityId === challengeId,
      );
      expect(startEvent).toBeDefined();

      // ENDED é due após encerramento
      const endEvents = await repository.findDueEvents(endsAtExclusive + 3600 * 1000, 50);
      const endEvent = endEvents.find(
        (e) =>
          e.type === 'CHALLENGE_ENDED' && e.recipientUid === UID_A && e.entityId === challengeId,
      );
      expect(endEvent).toBeDefined();
    });

    it('cancela eventos futuros ao cancelar desafio (T17.5 §60)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      const sent = await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await acceptFriendRequest(TOKEN_B, sent.body.request.requestId);

      const created = await createChallenge(TOKEN_A, 'req-chal-cancel', [b.body.profile.socialId]);
      expect(created.status).toBe(200);
      const challengeId = created.body.challenge.challengeId;

      // Cancelar desafio
      const cancelRes = await request(server())
        .post(`/v1/social/challenges/${challengeId}/cancel`)
        .set('Authorization', `Bearer ${TOKEN_A}`);
      expect(cancelRes.status).toBe(200);

      // Eventos programados do desafio foram cancelados
      const startsAt = saoPauloInstant('2026-09-10T00:00:00');
      const due = await repository.findDueEvents(startsAt + 1000, 50);
      const startingSoon = due.filter(
        (e) => e.entityId === challengeId && e.type === 'CHALLENGE_STARTING_SOON',
      );
      expect(startingSoon).toHaveLength(0);
    });
  });

  // -----------------------------------------------------------------------------------------
  // 4. Despachante e Entrega FCM (T17.5 §63–§94)
  // -----------------------------------------------------------------------------------------

  describe('Despachante e envio de push', () => {
    it('entrega push para dispositivo ativo quando usuário tem push habilitado', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      // Bob ativa push e registra seu celular
      await patchPreferences(TOKEN_B, { pushEnabled: true });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'fcm-bob-123',
      });

      // Alice envia solicitação para Bob
      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);

      // Despachante roda ciclo
      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(1);
      const push = pushGateway.sentPushes[0];
      expect(push.fcmToken).toBe('fcm-bob-123');
      expect(push.payload.v).toBe('1');
      expect(push.payload.type).toBe('FRIEND_REQUEST_RECEIVED');
      expect(push.payload.recipientSocialId).toBe(b.body.profile.socialId);

      // NUNCA vaza UID, email, ou nomes no payload (T17.5 §86)
      const rawPayload = push.payload as unknown as Record<string, unknown>;
      expect(rawPayload.ownerUid).toBeUndefined();
      expect(rawPayload.recipientUid).toBeUndefined();
      expect(rawPayload.email).toBeUndefined();
      expect(rawPayload.displayName).toBeUndefined();
    });

    it('suprime push se pushEnabled=false (default) (T17.5 §24)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      // Bob registra aparelho mas mantém pushEnabled=false (default)
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'fcm-bob-123',
      });

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(0);
    });

    it('suprime push se categoria específica estiver desabilitada (T17.5 §25)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      // Bob ativa master push, mas desliga friendRequestReceived
      await patchPreferences(TOKEN_B, {
        pushEnabled: true,
        friendRequestReceived: false,
      });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'fcm-bob-123',
      });

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(0);
    });

    it('suprime evento se o destinatário tiver 0 aparelhos registrados (T17.5 §38)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      await patchPreferences(TOKEN_B, { pushEnabled: true });
      // Nenhum dispositivo registrado

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(0);
    });

    it('suprime push se solicitação for cancelada antes do despacho (T17.5 §64)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      await patchPreferences(TOKEN_B, { pushEnabled: true });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'fcm-bob-123',
      });

      const sent = await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      // Alice cancela antes do despachante rodar
      await cancelFriendRequest(TOKEN_A, sent.body.request.requestId);

      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(0);
    });

    it('entrega para múltiplos dispositivos do mesmo usuário (T17.5 §16)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      await patchPreferences(TOKEN_B, { pushEnabled: true });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'fcm-phone-bob',
      });
      await registerDevice(TOKEN_B, {
        deviceId: 'tablet-bob',
        platform: 'ANDROID',
        fcmToken: 'fcm-tablet-bob',
      });

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(2);
      const tokens = pushGateway.sentPushes.map((p) => p.fcmToken);
      expect(tokens).toContain('fcm-phone-bob');
      expect(tokens).toContain('fcm-tablet-bob');
    });

    it('desativa dispositivo em erro permanente do FCM (T17.5 §79)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      await patchPreferences(TOKEN_B, { pushEnabled: true });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'dead-token-123',
      });

      pushGateway.setTokenResult('dead-token-123', {
        success: false,
        permanent: true,
        errorCode: 'messaging/registration-token-not-registered',
      });

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await dispatcher.runDispatchCycle();

      // Dispositivo foi desativado no banco
      const activeDevices = await repository.findActiveDevicesForRecipient(UID_B);
      expect(activeDevices).toHaveLength(0);
    });

    it('reagenda entrega com backoff em falha transitória (T17.5 §76–§78)', async () => {
      await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      await patchPreferences(TOKEN_B, { pushEnabled: true });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob',
        platform: 'ANDROID',
        fcmToken: 'retry-token-123',
      });

      pushGateway.setTokenResult('retry-token-123', {
        success: false,
        permanent: false,
        errorCode: 'messaging/server-unavailable',
      });

      await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      await dispatcher.runDispatchCycle();

      // Primeira tentativa falhou transitório
      expect(pushGateway.sentPushes).toHaveLength(1);

      // Imediatamente no mesmo instante: ainda não venceu o backoff
      await dispatcher.runDispatchCycle();
      expect(pushGateway.sentPushes).toHaveLength(1);

      // Avança tempo além do backoff e simula recuperação do provider
      pushGateway.setTokenResult('retry-token-123', { success: true });
      clock.advance(60000);

      await dispatcher.runDispatchCycle();
      expect(pushGateway.sentPushes).toHaveLength(2);
    });

    it('ao desativar Social, desabilita push e dispositivos (T17.5 §28)', async () => {
      await activate(TOKEN_A, 'Alice');

      await patchPreferences(TOKEN_A, { pushEnabled: true });
      await registerDevice(TOKEN_A, {
        deviceId: 'phone-alice',
        platform: 'ANDROID',
        fcmToken: 'fcm-alice',
      });

      expect((await repository.getPreferences(UID_A)).pushEnabled).toBe(true);
      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(1);

      // Desativa o Social
      const res = await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', `Bearer ${TOKEN_A}`);
      expect(res.status).toBe(200);

      // push desativado e dispositivos desabilitados
      expect((await repository.getPreferences(UID_A)).pushEnabled).toBe(false);
      expect(await repository.findActiveDevicesForRecipient(UID_A)).toHaveLength(0);
    });

    it('eventos expirados são marcados como EXPIRED na varredura e não chegam ao gateway (T17.5.1 Problema 6)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');

      await patchPreferences(TOKEN_A, { pushEnabled: true });
      await registerDevice(TOKEN_A, {
        deviceId: 'phone-alice',
        platform: 'ANDROID',
        fcmToken: 'fcm-alice-exp',
      });

      // Cria um evento que expira em 1 hora
      const now = clock.now();
      await repository.createEvent(
        {
          id: 'event-to-expire',
          recipientUid: UID_A,
          type: 'FRIEND_REQUEST_RECEIVED',
          entityId: 'req-dummy',
          dedupeKey: 'dedupe:exp-test',
          deliverAfter: now,
          expiresAt: now + 3600000,
        },
        now,
      );

      // O dispatcher não roda até depois de o evento expirar
      clock.advance(3600000 + 1000);

      // O ciclo do dispatcher executa
      await dispatcher.runDispatchCycle();

      // O evento deve ser transicionado para EXPIRED
      const res = await app.get(PostgresService).query<{
        status: string;
        completed_at: string | number;
      }>('SELECT status, completed_at FROM social_notification_events WHERE id = $1', ['event-to-expire']);
      const row = {
        status: res.rows[0].status,
        completed_at: Number(res.rows[0].completed_at),
      };

      expect(row.status).toBe('EXPIRED');
      expect(row.completed_at).toBe(clock.now());

      // Nenhum push deve ter sido enviado
      expect(pushGateway.sentPushes).toHaveLength(0);
    });

    it('cross-request produz eventos canônicos e entrega pushes a ambos os amigos (T17.5.1 Problema 5)', async () => {
      const a = await activate(TOKEN_A, 'Alice');
      const b = await activate(TOKEN_B, 'Bob');

      // Ambos ativam push e registram aparelhos
      await patchPreferences(TOKEN_A, { pushEnabled: true });
      await registerDevice(TOKEN_A, {
        deviceId: 'phone-alice-cross',
        platform: 'ANDROID',
        fcmToken: 'token-alice-cross',
      });

      await patchPreferences(TOKEN_B, { pushEnabled: true });
      await registerDevice(TOKEN_B, {
        deviceId: 'phone-bob-cross',
        platform: 'ANDROID',
        fcmToken: 'token-bob-cross',
      });

      // Alice envia para Bob
      const reqAtoB = await sendFriendRequest(TOKEN_A, b.body.profile.socialId);
      expect(reqAtoB.status).toBe(200);
      expect(reqAtoB.body.result).toBe('REQUEST_CREATED');

      // Limpa qualquer push enfileirado até aqui executando o dispatcher
      await dispatcher.runDispatchCycle();
      pushGateway.clear();

      // Bob envia para Alice (cross-request)
      const reqBtoA = await sendFriendRequest(TOKEN_B, a.body.profile.socialId);
      expect(reqBtoA.status).toBe(200);
      expect(reqBtoA.body.result).toBe('FRIENDSHIP_CREATED');

      // O ciclo do dispatcher deve encontrar ambos os eventos e entregar ambos os pushes
      await dispatcher.runDispatchCycle();

      expect(pushGateway.sentPushes).toHaveLength(2);

      const alicePush = pushGateway.sentPushes.find(
        (p) => p.payload.recipientSocialId === a.body.profile.socialId,
      );
      const bobPush = pushGateway.sentPushes.find(
        (p) => p.payload.recipientSocialId === b.body.profile.socialId,
      );

      expect(alicePush).toBeDefined();
      expect(bobPush).toBeDefined();
      expect(alicePush!.payload.type).toBe('FRIEND_REQUEST_ACCEPTED');
      expect(bobPush!.payload.type).toBe('FRIEND_REQUEST_ACCEPTED');

      // Ambos os pushes apontam para o requestId canônico do pedido original
      expect(alicePush!.payload.entityId).toBe(reqAtoB.body.request.requestId);
      expect(bobPush!.payload.entityId).toBe(reqAtoB.body.request.requestId);
    });
  });
});
