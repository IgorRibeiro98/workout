import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import type { WorkoutTemplateShareSnapshotV1 } from '../src/modules/social/workout-share.contract';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
  C: { token: 'token-c', uid: 'uid-c', email: 'c@example.com', name: 'Charlie' },
} as const;

const VALID_SNAPSHOT_V1: WorkoutTemplateShareSnapshotV1 = {
  snapshotVersion: 1,
  name: 'Upper A',
  shortIdentifier: 'A',
  exercises: [
    {
      canonicalExerciseId: 'canonical:supino-reto-barra',
      sortOrder: 0,
      targetSets: 4,
      minReps: 8,
      maxReps: 10,
      restDurationSeconds: 120,
    },
    {
      canonicalExerciseId: 'canonical:remada-curvada-barra',
      sortOrder: 1,
      targetSets: 4,
      minReps: 8,
      maxReps: 10,
      restDurationSeconds: 120,
    },
  ],
};

describe('Workout Shares: Compartilhamento de Treinos entre Amigos (T17.7)', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  async function setupProfile(token: string, displayName: string): Promise<string> {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId;
  }

  async function establishFriendship(
    tokenA: string,
    tokenB: string,
    socialIdB: string,
  ): Promise<void> {
    const sendRes = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenA))
      .send({ socialId: socialIdB })
      .expect(200);

    const requestId = sendRes.body.request.requestId;
    await request(server())
      .post(`/v1/social/friend-requests/${requestId}/accept`)
      .set('Authorization', auth(tokenB))
      .send()
      .expect(200);
  }

  it('permite que amigos compartilhem um treino com snapshot imutável', async () => {
    const socialA = await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);

    // A compartilha treino com B
    const shareRes = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-share-1',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    expect(shareRes.body.shareId).toBeDefined();
    expect(shareRes.body.status).toBe('PENDING');
    expect(shareRes.body.sender.socialId).toBe(socialA);
    expect(shareRes.body.recipient.socialId).toBe(socialB);
    const shareId = shareRes.body.shareId;

    // B lista recebidos
    const receivedRes = await request(server())
      .get('/v1/social/workout-shares/received')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    expect(receivedRes.body).toHaveLength(1);
    expect(receivedRes.body[0].shareId).toBe(shareId);
    expect(receivedRes.body[0].templateName).toBe('Upper A');
    expect(receivedRes.body[0].exerciseCount).toBe(2);

    // A lista enviados
    const sentRes = await request(server())
      .get('/v1/social/workout-shares/sent')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(sentRes.body).toHaveLength(1);
    expect(sentRes.body[0].shareId).toBe(shareId);

    // B aceita a oferta
    const acceptRes = await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    expect(acceptRes.body.snapshotVersion).toBe(1);
    expect(acceptRes.body.name).toBe('Upper A');
    expect(acceptRes.body.exercises).toHaveLength(2);

    // B conclui importação
    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/complete-import`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    // Detalhe mostra status IMPORTED
    const detailRes = await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    expect(detailRes.body.status).toBe('IMPORTED');
  });

  it('impede compartilhamento se não forem amigos ou consigo mesmo', async () => {
    const socialA = await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');

    // Auto-compartilhamento
    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialA,
        clientRequestId: 'req-self',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(400);

    // Sem amizade
    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-non-friend',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(403);
  });

  it('anti-enumeração: usuário C não consegue ver nem aceitar share de A e B', async () => {
    await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await setupProfile(ACCOUNTS.C.token, 'Charlie');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);

    const shareRes = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-anti-enum',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    const shareId = shareRes.body.shareId;

    // C tenta ler detalhe -> 404
    await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.C.token))
      .expect(404);

    // C tenta aceitar -> 404
    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.C.token))
      .expect(404);
  });

  it('idempotência: repetição com mesmo clientRequestId retorna mesmo share; divergência dá conflito', async () => {
    await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);

    const res1 = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-idem-1',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    // Repetição idêntica -> retorna o mesmo
    const res2 = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-idem-1',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    expect(res2.body.shareId).toBe(res1.body.shareId);

    // Repetição com payload diferente -> 409 Conflict
    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-idem-1',
        snapshot: { ...VALID_SNAPSHOT_V1, name: 'Different Name' },
      })
      .expect(409);
  });

  it('cancelamento pelo remetente e recusa pelo destinatário', async () => {
    await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);

    // 1. Cancelamento pelo remetente
    const share1 = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-cancel-1',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    await request(server())
      .post(`/v1/social/workout-shares/${share1.body.shareId}/cancel`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // Destinatário tenta aceitar cancelado -> 400
    await request(server())
      .post(`/v1/social/workout-shares/${share1.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(400);

    // 2. Recusa pelo destinatário
    const share2 = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-decline-1',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    await request(server())
      .post(`/v1/social/workout-shares/${share2.body.shareId}/decline`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    // Não pode aceitar recusado -> 400
    await request(server())
      .post(`/v1/social/workout-shares/${share2.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(400);
  });

  it('bloqueio entre usuários invalida e cancela shares pendentes', async () => {
    const socialA = await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);

    const share = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-block-1',
        snapshot: VALID_SNAPSHOT_V1,
      })
      .expect(201);

    const shareId = share.body.shareId;

    // B bloqueia A
    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ blockedSocialId: socialA })
      .expect(200);

    // Oferta agora dá 404 para ambos
    await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(404);

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(404);
  });
});
