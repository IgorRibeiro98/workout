import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
  C: { token: 'token-c', uid: 'uid-c', email: 'c@example.com', name: 'Charlie' },
} as const;

describe('Social Hardening: Bloqueio de Usuários (T17.6)', () => {
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

  async function setupProfile(
    token: string,
    displayName: string,
  ): Promise<{ socialId: string; friendCode: string }> {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return {
      socialId: res.body.profile.socialId,
      friendCode: res.body.profile.friendCode,
    };
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

  it('bloqueia usuário com sucesso, encerra amizade bilateralmente e lista na lista de bloqueados', async () => {
    await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const profileB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    // Cria amizade entre A e B
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, profileB.socialId);

    // Verifica que A e B são amigos
    const friendsBefore = await request(server())
      .get('/v1/social/friends')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(friendsBefore.body.friends).toHaveLength(1);

    // A bloqueia B
    const blockRes = await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: profileB.socialId })
      .expect(200);

    expect(blockRes.body).toEqual({
      result: 'BLOCKED',
      blockedSocialId: profileB.socialId,
    });

    // Amizade desfeita para ambos
    const friendsA = await request(server())
      .get('/v1/social/friends')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(friendsA.body.friends).toHaveLength(0);

    const friendsB = await request(server())
      .get('/v1/social/friends')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(friendsB.body.friends).toHaveLength(0);

    // A lista B como bloqueado
    const listRes = await request(server())
      .get('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(listRes.body.blockedUsers).toHaveLength(1);
    expect(listRes.body.blockedUsers[0].socialId).toBe(profileB.socialId);
    expect(listRes.body.blockedUsers[0].displayName).toBe(ACCOUNTS.B.name);

    // B NÃO vê A na sua lista de bloqueados (bloqueio foi iniciado por A)
    const listB = await request(server())
      .get('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(listB.body.blockedUsers).toHaveLength(0);
  });

  it('impede descoberta de friendCode e novos pedidos quando bloqueado', async () => {
    const profileA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const profileB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    // A bloqueia B
    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: profileB.socialId })
      .expect(200);

    // B tenta lookup do friendCode de A -> NOT_FOUND (indistinguível de inexistente)
    const lookupRes = await request(server())
      .post('/v1/social/friends/lookup')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ friendCode: profileA.friendCode })
      .expect(200);
    expect(lookupRes.body).toEqual({ result: 'NOT_FOUND' });

    // B tenta enviar friend request para A -> 404 SOCIAL_PROFILE_NOT_FOUND
    await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ socialId: profileA.socialId })
      .expect(404);

    // A tenta lookup de B -> NOT_FOUND
    const lookupA = await request(server())
      .post('/v1/social/friends/lookup')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ friendCode: profileB.friendCode })
      .expect(200);
    expect(lookupA.body).toEqual({ result: 'NOT_FOUND' });
  });

  it('cancela pedidos pendentes existentes no momento do bloqueio', async () => {
    const profileA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const profileB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    // B envia friend request para A
    await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ socialId: profileA.socialId })
      .expect(200);

    // A vê pedido recebido pendente
    const reqsBefore = await request(server())
      .get('/v1/social/friend-requests/incoming')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(reqsBefore.body.requests).toHaveLength(1);

    // A bloqueia B
    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: profileB.socialId })
      .expect(200);

    // Pedido sumiu para ambos
    const reqsAfterA = await request(server())
      .get('/v1/social/friend-requests/incoming')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(reqsAfterA.body.requests).toHaveLength(0);

    const reqsAfterB = await request(server())
      .get('/v1/social/friend-requests/outgoing')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(reqsAfterB.body.requests).toHaveLength(0);
  });

  it('desbloqueio é idempotente e NÃO restaura amizades anteriores', async () => {
    const profileA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const profileB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, profileB.socialId);

    // Bloqueia
    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: profileB.socialId })
      .expect(200);

    // Desbloqueia
    const unblockRes = await request(server())
      .delete(`/v1/social/blocks/${profileB.socialId}`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(unblockRes.body).toEqual({
      result: 'UNBLOCKED',
      unblockedSocialId: profileB.socialId,
    });

    // Desbloquear de novo é idempotente
    await request(server())
      .delete(`/v1/social/blocks/${profileB.socialId}`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // Amizade NÃO foi restaurada
    const friendsA = await request(server())
      .get('/v1/social/friends')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(friendsA.body.friends).toHaveLength(0);

    // Porém agora a descoberta por friendCode volta a funcionar
    const lookupB = await request(server())
      .post('/v1/social/friends/lookup')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ friendCode: profileA.friendCode })
      .expect(200);
    expect(lookupB.body.result).toBe('FOUND');
  });

  it('rejeita auto-bloqueio', async () => {
    const profileA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);

    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: profileA.socialId })
      .expect(400);
  });
});
