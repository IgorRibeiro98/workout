import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { SparkLogger } from '../src/common/logger';
import { extractBearerToken } from '../src/modules/auth/bearer-auth.guard';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import {
  EXPLODING_TOKEN,
  FakeAuthTokenVerifier,
  UNAVAILABLE_TOKEN,
} from './support/fake-auth-token-verifier';

const VALID_TOKEN = 'token-do-usuario-a';
const UID_A = 'uid-do-usuario-a';
const UID_VICTIM = 'uid-da-vitima';

describe('Autenticação (/v1/auth/me, Firebase ID Token)', () => {
  let temp: TempDb;
  let app: INestApplication;
  let verifier: FakeAuthTokenVerifier;

  beforeEach(async () => {
    temp = createTempDb();
    verifier = FakeAuthTokenVerifier.withPrincipal(VALID_TOKEN, {
      uid: UID_A,
      email: 'atleta@example.com',
      provider: 'google.com',
    });
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  const me = () => request(app.getHttpServer()).get('/v1/auth/me');

  // ------------------------------------------------------------------ rotas públicas

  it('health continua público — autenticação não é global', async () => {
    expect((await request(app.getHttpServer()).get('/health/live')).status).toBe(200);
    expect((await request(app.getHttpServer()).get('/health/ready')).status).toBe(200);
    expect(verifier.seen).toEqual([]);
  });

  // ------------------------------------------------------------------------ 401

  it('sem header Authorization responde 401', async () => {
    const response = await me();

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('UNAUTHENTICATED');
    // O verificador nem chega a ser chamado: não há token para verificar.
    expect(verifier.seen).toEqual([]);
  });

  it('Bearer vazio responde 401', async () => {
    for (const header of ['Bearer', 'Bearer ', 'Bearer    ']) {
      const response = await me().set('Authorization', header);
      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('UNAUTHENTICATED');
    }
    expect(verifier.seen).toEqual([]);
  });

  it('Authorization malformado responde 401', async () => {
    const malformed = [
      VALID_TOKEN,
      `Basic ${VALID_TOKEN}`,
      `Token ${VALID_TOKEN}`,
      `Bearer=${VALID_TOKEN}`,
      `Bearer ${VALID_TOKEN} extra`,
    ];

    for (const header of malformed) {
      const response = await me().set('Authorization', header);
      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('UNAUTHENTICATED');
    }
    expect(verifier.seen).toEqual([]);
  });

  it('token recusado pelo verificador responde 401', async () => {
    const response = await me().set('Authorization', 'Bearer token-que-nao-vale');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('UNAUTHENTICATED');
    expect(verifier.seen).toEqual(['token-que-nao-vale']);
  });

  it('um JWT bem formado mas não verificado não autentica ninguém', async () => {
    // Payload legível com `sub` — exatamente o que um `base64 decode` ingênuo aceitaria.
    const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
    const payload = Buffer.from(JSON.stringify({ sub: UID_VICTIM, uid: UID_VICTIM })).toString(
      'base64url',
    );
    const forged = `${header}.${payload}.`;

    const response = await me().set('Authorization', `Bearer ${forged}`);

    expect(response.status).toBe(401);
    expect(JSON.stringify(response.body)).not.toContain(UID_VICTIM);
  });

  // ------------------------------------------------------------------------ 200

  it('token válido responde 200 com o uid do token', async () => {
    const response = await me().set('Authorization', `Bearer ${VALID_TOKEN}`);

    expect(response.status).toBe(200);
    expect(response.body).toEqual({ uid: UID_A });
  });

  it('o esquema Bearer é aceito em qualquer caixa, como manda o RFC 6750', async () => {
    for (const scheme of ['Bearer', 'bearer', 'BEARER', 'BeArEr']) {
      const response = await me().set('Authorization', `${scheme} ${VALID_TOKEN}`);
      expect(response.status).toBe(200);
      expect(response.body).toEqual({ uid: UID_A });
    }
  });

  it('a resposta não carrega claim nenhuma além do uid', async () => {
    const response = await me().set('Authorization', `Bearer ${VALID_TOKEN}`);

    expect(Object.keys(response.body)).toEqual(['uid']);
    const raw = JSON.stringify(response.body);
    expect(raw).not.toContain('atleta@example.com');
    expect(raw).not.toContain('google.com');
    expect(raw).not.toContain(VALID_TOKEN);
  });

  // -------------------------------------------------------- uid não vem do cliente

  it('query, header e corpo não conseguem trocar o uid de um token válido', async () => {
    const attempts = [
      () => me().query({ uid: UID_VICTIM }).set('Authorization', `Bearer ${VALID_TOKEN}`),
      () => me().query({ ownerUid: UID_VICTIM }).set('Authorization', `Bearer ${VALID_TOKEN}`),
      () => me().set('X-User-Id', UID_VICTIM).set('Authorization', `Bearer ${VALID_TOKEN}`),
      () => me().set('X-Owner-Uid', UID_VICTIM).set('Authorization', `Bearer ${VALID_TOKEN}`),
      () =>
        me()
          .set('Authorization', `Bearer ${VALID_TOKEN}`)
          .send({ uid: UID_VICTIM, ownerUid: UID_VICTIM }),
    ];

    for (const attempt of attempts) {
      const response = await attempt();
      expect(response.status).toBe(200);
      expect(response.body).toEqual({ uid: UID_A });
    }
  });

  it('sem token, nenhum uid forjado no cliente vira identidade', async () => {
    const attempts = [
      () => me().query({ uid: UID_VICTIM }),
      () => me().set('X-User-Id', UID_VICTIM),
      () => me().send({ ownerUid: UID_VICTIM }),
    ];

    for (const attempt of attempts) {
      const response = await attempt();
      expect(response.status).toBe(401);
      expect(JSON.stringify(response.body)).not.toContain(UID_VICTIM);
    }
  });

  // ------------------------------------------------------- verificador indisponível

  it('verificador indisponível responde 503, e não 401', async () => {
    const response = await me().set('Authorization', `Bearer ${UNAVAILABLE_TOKEN}`);

    // 401 faria o Android concluir que a sessão morreu por um problema que era do servidor.
    expect(response.status).toBe(503);
    expect(response.body.error.code).toBe('AUTH_UNAVAILABLE');
  });

  it('erro inesperado dentro do verificador não autentica e não vaza detalhe', async () => {
    const response = await me().set('Authorization', `Bearer ${EXPLODING_TOKEN}`);

    expect(response.status).toBe(503);
    expect(response.body.error.code).toBe('AUTH_UNAVAILABLE');
    const raw = JSON.stringify(response.body);
    expect(raw).not.toContain('falha inesperada');
    expect(raw).not.toMatch(/\bat \w+.*\(.*:\d+:\d+\)/);
    expect(response.body.error).not.toHaveProperty('stack');
  });

  it('nenhuma resposta de erro expõe token, credencial ou internals do Firebase', async () => {
    const responses = [
      await me(),
      await me().set('Authorization', `Bearer ${VALID_TOKEN}-invalido`),
      await me().set('Authorization', `Bearer ${UNAVAILABLE_TOKEN}`),
      await me().set('Authorization', `Bearer ${EXPLODING_TOKEN}`),
    ];

    for (const response of responses) {
      const raw = JSON.stringify(response.body);
      expect(raw).not.toContain(VALID_TOKEN);
      expect(raw).not.toContain(UNAVAILABLE_TOKEN);
      expect(raw).not.toContain('service-account');
      expect(raw).not.toContain('private_key');
      expect(raw).not.toContain('GOOGLE_APPLICATION_CREDENTIALS');
      expect(raw).not.toContain('firebase-admin');
      expect(response.body.error).toEqual({
        code: expect.any(String),
        message: expect.any(String),
        requestId: expect.any(String),
      });
    }
  });

  // ------------------------------------------------------------------------- log

  it('nem o header Authorization nem o token aparecem no log', async () => {
    const captured: Array<Record<string, unknown>> = [];
    const logger = app.get(SparkLogger);
    for (const level of ['info', 'warn', 'error'] as const) {
      jest
        .spyOn(logger, level)
        .mockImplementation((event: string, fields: Record<string, unknown> = {}) => {
          captured.push({ level, event, ...fields });
        });
    }

    await me().set('Authorization', `Bearer ${VALID_TOKEN}`);
    await me().set('Authorization', 'Bearer token-que-nao-vale');
    await me();

    const serialized = JSON.stringify(captured);
    expect(captured.length).toBeGreaterThan(0);
    expect(serialized).not.toContain(VALID_TOKEN);
    expect(serialized).not.toContain('token-que-nao-vale');
    expect(serialized.toLowerCase()).not.toContain('authorization');
    expect(serialized.toLowerCase()).not.toContain('bearer');
  });

  it('o log registra que autenticou sem registrar o uid inteiro', async () => {
    const captured: Array<Record<string, unknown>> = [];
    const logger = app.get(SparkLogger);
    jest.spyOn(logger, 'info').mockImplementation((event, fields) => {
      captured.push({ event, ...fields });
    });

    await me().set('Authorization', `Bearer ${VALID_TOKEN}`);

    const accepted = captured.find((entry) => entry.event === 'auth.accepted');
    expect(accepted).toMatchObject({ requestId: expect.any(String), uidPrefix: 'uid-do' });
    expect(JSON.stringify(captured)).not.toContain(UID_A);
  });
});

describe('extractBearerToken', () => {
  it('extrai o token de um header bem formado', () => {
    expect(extractBearerToken('Bearer abc.def.ghi')).toBe('abc.def.ghi');
    expect(extractBearerToken('  bearer   abc  ')).toBe('abc');
  });

  it('recusa ausência, formato inválido e header duplicado', () => {
    expect(extractBearerToken(undefined)).toBeNull();
    expect(extractBearerToken('')).toBeNull();
    expect(extractBearerToken('Bearer')).toBeNull();
    expect(extractBearerToken('Bearer ')).toBeNull();
    expect(extractBearerToken('Basic abc')).toBeNull();
    expect(extractBearerToken('Bearer a b')).toBeNull();
    // Express entrega array quando o cliente manda o header duas vezes: escolher um seria
    // arbitrar entre credenciais conflitantes.
    expect(extractBearerToken(['Bearer a', 'Bearer b'])).toBeNull();
  });
});
