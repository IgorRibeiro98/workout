import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AiRequestRegistry } from '../src/modules/ai/ai-request.registry';
import { AiUsageRepository, utcDateOf } from '../src/modules/ai/ai-usage.repository';
import { AiEntitlementRepository } from '../src/modules/ai/entitlement/ai-entitlement.repository';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeAiProviderGateway } from './support/fake-ai-provider';
import * as fixtures from './support/ai-fixtures';

const TOKEN_A = 'token-a';
const UID_A = 'uid-a';
const TOKEN_B = 'token-b';
const UID_B = 'uid-b';

/**
 * A ACL granular do Coach (T19.0): entitlement por conta/capability, verificado antes de
 * concorrência, quota e provider. `ai-quota.spec.ts` continua provando a proteção de custo em si;
 * esta suíte prova que uma negação por entitlement não chega a acionar nenhuma delas — os
 * blockers automáticos da tarefa (§16) são exatamente isto.
 */
describe('Entitlement de capabilities do Coach', () => {
  let temp: TempDb;
  let app: INestApplication;
  let provider: FakeAiProviderGateway;

  const start = async (overrides: Record<string, string> = {}) => {
    provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
      uid: UID_B,
    });
    app = await createTestApp(configFor(temp.path, overrides), verifier, provider);
  };

  const call = (token: string, requestType: string, context: unknown, clientRequestId: string) =>
    request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${token}`)
      .send(fixtures.requestBody(requestType, context, { clientRequestId }));

  const analyze = (token: string, clientRequestId: string) =>
    call(token, 'ANALYZE_WORKOUT', fixtures.analysisContext(), clientRequestId);

  const userTotal = async (uid: string) => app.get(AiUsageRepository).userTotal(utcDateOf(), uid);

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  it('sem nenhuma linha gravada, a capability continua liberada — compatibilidade dos usuários atuais', async () => {
    await start();
    const response = await analyze(TOKEN_A, 'cli-000000000001');
    expect(response.status).toBe(201);
    expect(provider.callCount).toBe(1);
  });

  it('capability revogada nega antes do provider, sem reservar concorrência nem consumir quota', async () => {
    await start();
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_ANALYZE_WORKOUT', 'REVOKED');

    const response = await analyze(TOKEN_A, 'cli-000000000001');

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('AI_CAPABILITY_DENIED');
    expect(provider.callCount).toBe(0);
    expect(app.get(AiRequestRegistry).activeFor(UID_A)).toBe(0);
    expect(await userTotal(UID_A)).toBe(0);
  });

  it('revogar uma capability não afeta as outras operações da mesma conta', async () => {
    await start();
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_ADAPT_WORKOUT', 'REVOKED');

    const analyzeResponse = await analyze(TOKEN_A, 'cli-000000000001');
    expect(analyzeResponse.status).toBe(201);

    // ADAPT_WORKOUT é negada antes do provider: o payload que o dublê devolveria não importa.
    const adaptResponse = await call(
      TOKEN_A,
      'ADAPT_WORKOUT',
      fixtures.adaptationContext(),
      'cli-000000000002',
    );
    expect(adaptResponse.status).toBe(403);
    expect(adaptResponse.body.error.code).toBe('AI_CAPABILITY_DENIED');
  });

  it('os quatro EXPLAIN_* compartilham a capability AI_EXPLAIN', async () => {
    await start();
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_EXPLAIN', 'REVOKED');

    const recommendation = await call(
      TOKEN_A,
      'EXPLAIN_RECOMMENDATION',
      fixtures.explanationContext(),
      'cli-000000000001',
    );
    const progress = await call(
      TOKEN_A,
      'EXPLAIN_PROGRESS',
      fixtures.explanationContext(),
      'cli-000000000002',
    );

    expect(recommendation.status).toBe(403);
    expect(progress.status).toBe(403);
    expect(provider.callCount).toBe(0);
  });

  it('revogar a capability de uma conta não afeta outra conta — isolamento', async () => {
    await start();
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_ANALYZE_WORKOUT', 'REVOKED');

    const denied = await analyze(TOKEN_A, 'cli-000000000001');
    expect(denied.status).toBe(403);

    const allowed = await analyze(TOKEN_B, 'cli-000000000002');
    expect(allowed.status).toBe(201);
  });

  it('grant depois de revoke restaura o acesso, e repetir o grant não altera o resultado', async () => {
    await start();
    const repo = app.get(AiEntitlementRepository);
    await repo.setState(UID_A, 'AI_ANALYZE_WORKOUT', 'REVOKED');
    await repo.setState(UID_A, 'AI_ANALYZE_WORKOUT', 'GRANTED');
    await repo.setState(UID_A, 'AI_ANALYZE_WORKOUT', 'GRANTED');

    const response = await analyze(TOKEN_A, 'cli-000000000001');
    expect(response.status).toBe(201);
  });

  it('AI_ENABLED=false vence mesmo com entitlement concedido explicitamente', async () => {
    await start({ AI_ENABLED: 'false' });
    await app.get(AiEntitlementRepository).setState(UID_A, 'AI_ANALYZE_WORKOUT', 'GRANTED');

    const response = await analyze(TOKEN_A, 'cli-000000000001');

    expect(response.status).toBe(503);
    expect(response.body.error.code).toBe('AI_PROVIDER_UNAVAILABLE');
    expect(provider.callCount).toBe(0);
  });
});
