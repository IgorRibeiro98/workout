import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';

/**
 * O fluxo multiusuário completo, ponta a ponta (T17.2 §168).
 *
 * ```text
 * A e B viram amigos  →  B liga um campo  →  A vê  →  B desliga  →  A deixa de ver
 *                                                  C nunca vê
 * ```
 *
 * Ele existe separado dos testes por comportamento porque prova outra coisa: que a **sequência**
 * funciona. Cada passo isolado pode passar e a ordem ainda quebrar — a privacidade que só entra em
 * efeito no próximo deploy, o acesso que sobrevive ao `unfriend`, o terceiro que aprende algo
 * comparando respostas.
 */
describe('Smoke multiusuário do perfil social (A, B e C)', () => {
  let temp: TempDb;
  let app: INestApplication;

  const NOW = Date.parse('2026-09-08T18:00:00Z');
  const TZ = 'America/Sao_Paulo';

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept('token-a', { uid: 'uid-a' })
        .accept('token-b', { uid: 'uid-b' })
        .accept('token-c', { uid: 'uid-c' }),
    );
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app?.close();
    temp.cleanup();
  });

  it('A–B compartilham, C nunca vê, e revogar tem efeito imediato', async () => {
    const server = () => app.getHttpServer();
    const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

    // 1. Os três ativam o Social.
    const a = await request(server())
      .post('/v1/social/me/activate')
      .set(auth('token-a'))
      .send({ displayName: 'Ana' })
      .expect(200);
    const b = await request(server())
      .post('/v1/social/me/activate')
      .set(auth('token-b'))
      .send({ displayName: 'Igor' })
      .expect(200);
    await request(server())
      .post('/v1/social/me/activate')
      .set(auth('token-c'))
      .send({ displayName: 'Carla' })
      .expect(200);

    const socialIdB = b.body.profile.socialId as string;
    const socialIdA = a.body.profile.socialId as string;

    // 2. B sincroniza dois treinos concluídos desta semana — pelo caminho real do sync.
    for (const startedAt of [
      Date.parse('2026-09-07T09:00:00Z'),
      Date.parse('2026-09-08T22:00:00Z'),
    ]) {
      const syncId = uuid();
      await request(server())
        .post('/v1/sync/push')
        .set(auth('token-b'))
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'WORKOUT_SESSION',
              entitySyncId: syncId,
              payload: sessionPayload(syncId, { startedAt, finishedAt: startedAt + 3_600_000 }),
            },
          ]),
        )
        .expect(200);
    }

    jest.spyOn(Date, 'now').mockReturnValue(NOW);

    // 3. A e B viram amigos.
    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set(auth('token-a'))
      .send({ socialId: socialIdB })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set(auth('token-b'))
      .expect(200);

    // 4. Antes de B ligar qualquer coisa, A não vê progresso nenhum.
    const before = await request(server())
      .get(`/v1/social/friends/${socialIdB}/profile`)
      .set(auth('token-a'))
      .expect(200);
    expect(before.body.profile.sharedProgress).toEqual({});

    // 5. B liga os treinos da semana.
    await request(server())
      .patch('/v1/social/me/progress-sharing')
      .set(auth('token-b'))
      .send({ shareWeeklyWorkoutCount: true, weekTimeZone: TZ })
      .expect(200);

    const shared = await request(server())
      .get(`/v1/social/friends/${socialIdB}/profile`)
      .set(auth('token-a'))
      .expect(200);
    expect(shared.body.profile).toEqual({
      socialId: socialIdB,
      displayName: 'Igor',
      sharedProgress: { weeklyWorkoutCount: 2 },
    });

    // 6. C, que conhece o socialId de B, continua sem acesso.
    const fromC = await request(server())
      .get(`/v1/social/friends/${socialIdB}/profile`)
      .set(auth('token-c'))
      .expect(404);
    expect(fromC.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');

    // 7. B desliga: a leitura seguinte de A já não traz o campo.
    await request(server())
      .patch('/v1/social/me/progress-sharing')
      .set(auth('token-b'))
      .send({ shareWeeklyWorkoutCount: false })
      .expect(200);
    const revoked = await request(server())
      .get(`/v1/social/friends/${socialIdB}/profile`)
      .set(auth('token-a'))
      .expect(200);
    expect(revoked.body.profile.sharedProgress).toEqual({});

    // 8. B liga de novo e desfaz a amizade: o acesso morre com a relação.
    await request(server())
      .patch('/v1/social/me/progress-sharing')
      .set(auth('token-b'))
      .send({ shareWeeklyWorkoutCount: true })
      .expect(200);
    await request(server())
      .post('/v1/social/friends/remove')
      .set(auth('token-b'))
      .send({ socialId: socialIdA })
      .expect(200);

    const afterUnfriend = await request(server())
      .get(`/v1/social/friends/${socialIdB}/profile`)
      .set(auth('token-a'))
      .expect(404);
    expect(afterUnfriend.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');

    // 9. E a preferência de B sobreviveu a tudo: ela é dele, não da relação.
    const settings = await request(server())
      .get('/v1/social/me/progress-sharing')
      .set(auth('token-b'))
      .expect(200);
    expect(settings.body.settings.shareWeeklyWorkoutCount).toBe(true);
    expect(settings.body.availability.weeklyWorkoutCount).toBe('AVAILABLE');
  });
});
