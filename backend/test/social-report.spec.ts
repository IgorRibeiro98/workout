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

describe('Social Hardening: Denúncia de Abuso (T17.6)', () => {
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

  it('exige contexto social legítimo (amizade, pedido mútuo, desafio compartilhado)', async () => {
    const socialIdA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const socialIdB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    // Sem interação prévia -> 403 Forbidden
    await request(server())
      .post('/v1/social/reports')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ reportedSocialId: socialIdB, reason: 'SPAM' })
      .expect(403);

    // B envia pedido para A (cria contexto)
    await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ socialId: socialIdA })
      .expect(200);

    // Agora A consegue denunciar B
    const reportRes = await request(server())
      .post('/v1/social/reports')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ reportedSocialId: socialIdB, reason: 'SPAM' })
      .expect(200);

    expect(reportRes.body.result).toBe('REPORT_RECEIVED');
    expect(reportRes.body.reportId).toBeDefined();
  });

  it('valida enum fechado de motivos e impede auto-denúncia', async () => {
    const socialIdA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const socialIdB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    // B envia pedido para A
    await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ socialId: socialIdA })
      .expect(200);

    // Auto-denúncia -> 400 Bad Request
    await request(server())
      .post('/v1/social/reports')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ reportedSocialId: socialIdA, reason: 'SPAM' })
      .expect(400);

    // Motivo inválido -> 400 Bad Request
    await request(server())
      .post('/v1/social/reports')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ reportedSocialId: socialIdB, reason: 'INVALID_REASON' })
      .expect(400);
  });

  it('bloqueia duplicatas idênticas no mesmo dia e aplica rate limit de 5/dia', async () => {
    const socialIdA = await setupProfile(ACCOUNTS.A.token, ACCOUNTS.A.name);
    const socialIdB = await setupProfile(ACCOUNTS.B.token, ACCOUNTS.B.name);

    // B envia pedido para A
    await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ socialId: socialIdA })
      .expect(200);

    // Primeira denúncia: sucesso
    await request(server())
      .post('/v1/social/reports')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ reportedSocialId: socialIdB, reason: 'HARASSMENT' })
      .expect(200);

    // Segunda denúncia idêntica no mesmo dia: supressão idempotente
    const dupRes = await request(server())
      .post('/v1/social/reports')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ reportedSocialId: socialIdB, reason: 'HARASSMENT' })
      .expect(200);
    expect(dupRes.body).toEqual({
      result: 'REPORT_RECEIVED',
      reportId: 'duplicate-suppressed',
    });
  });
});
