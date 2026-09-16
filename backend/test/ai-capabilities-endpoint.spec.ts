import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AiEntitlementRepository } from '../src/modules/ai/entitlement/ai-entitlement.repository';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const TOKEN_A = 'token-a';
const UID_A = 'uid-a';
const TOKEN_B = 'token-b';
const UID_B = 'uid-b';

/**
 * `GET /v1/account/capabilities` (T19.0 §12) — o que o Android consulta para construir a UX, sem
 * nunca ser a autoridade: `ai-entitlement-enforcement.spec.ts` prova que o backend valida de novo
 * mesmo que este endpoint tenha dito "permitido".
 */
describe('GET /v1/account/capabilities', () => {
  let temp: TempDb;
  let app: INestApplication;

  const start = async () => {
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
      uid: UID_B,
    });
    app = await createTestApp(configFor(temp.path), verifier);
  };

  const get = (token?: string) => {
    const req = request(app.getHttpServer()).get('/v1/account/capabilities');
    return token ? req.set('Authorization', `Bearer ${token}`) : req;
  };

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  it('sem token, 401', async () => {
    await start();
    const response = await get();
    expect(response.status).toBe(401);
  });

  it('sem nenhuma linha gravada, as quatro capabilities vêm liberadas', async () => {
    await start();
    const response = await get(TOKEN_A);

    expect(response.status).toBe(200);
    expect(response.body.capabilities).toEqual(
      expect.arrayContaining([
        { capability: 'AI_ANALYZE_WORKOUT', allowed: true },
        { capability: 'AI_GENERATE_WORKOUT', allowed: true },
        { capability: 'AI_ADAPT_WORKOUT', allowed: true },
        { capability: 'AI_EXPLAIN', allowed: true },
      ]),
    );
    expect(response.body.capabilities).toHaveLength(4);
  });

  it('reflete uma capability revogada, e só ela', async () => {
    await start();
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_GENERATE_WORKOUT', 'REVOKED');

    const response = await get(TOKEN_A);

    const byCapability = Object.fromEntries(
      (response.body.capabilities as Array<{ capability: string; allowed: boolean }>).map((c) => [
        c.capability,
        c.allowed,
      ]),
    );
    expect(byCapability.AI_GENERATE_WORKOUT).toBe(false);
    expect(byCapability.AI_ANALYZE_WORKOUT).toBe(true);
    expect(byCapability.AI_ADAPT_WORKOUT).toBe(true);
    expect(byCapability.AI_EXPLAIN).toBe(true);
  });

  it('a conta A não vê as capabilities da conta B — isolamento', async () => {
    await start();
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_EXPLAIN', 'REVOKED');

    const responseA = await get(TOKEN_A);
    const responseB = await get(TOKEN_B);

    const explainFor = (body: { capabilities: Array<{ capability: string; allowed: boolean }> }) =>
      body.capabilities.find((c) => c.capability === 'AI_EXPLAIN')?.allowed;

    expect(explainFor(responseA.body)).toBe(false);
    expect(explainFor(responseB.body)).toBe(true);
  });

  it('o corpo não expõe uid nem qualquer identificador interno', async () => {
    await start();
    const response = await get(TOKEN_A);

    const raw = JSON.stringify(response.body);
    expect(raw).not.toContain(UID_A);
    expect(response.body).not.toHaveProperty('uid');
  });

  it('não existe parâmetro para consultar outra conta — a rota não aceita accountId', async () => {
    await start();
    const response = await request(app.getHttpServer())
      .get('/v1/account/capabilities')
      .query({ uid: UID_B, accountId: UID_B })
      .set('Authorization', `Bearer ${TOKEN_A}`);

    // O uid vem só do token: um query string tentando outra conta não muda a resposta da conta A.
    expect(response.status).toBe(200);
    expect(JSON.stringify(response.body)).not.toContain(UID_B);
  });
});
