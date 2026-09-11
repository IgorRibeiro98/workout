import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AiRequestRegistry } from '../src/modules/ai/ai-request.registry';
import { AiProviderError } from '../src/modules/ai/provider/ai-provider.gateway';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { BlockingFakeAiProviderGateway, FakeAiProviderGateway } from './support/fake-ai-provider';
import * as fixtures from './support/ai-fixtures';

const TOKEN_A = 'token-a';
const UID_A = 'uid-a';
const TOKEN_B = 'token-b';
const UID_B = 'uid-b';

/**
 * Proteção de custo: quota, concorrência e toque duplo.
 *
 * O que estes testes protegem não é uma regra de produto — é a conta do fim do mês. Um bug em
 * vários aparelhos, um toque repetido ou uma recomposição não podem virar chamadas ao Gemini.
 */
describe('Quota e concorrência do Coach', () => {
  let temp: TempDb;
  let app: INestApplication;

  const start = async (
    provider: FakeAiProviderGateway | BlockingFakeAiProviderGateway,
    overrides: Record<string, string> = {},
  ) => {
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
      uid: UID_B,
    });
    app = await createTestApp(configFor(temp.path, overrides), verifier, provider);
  };

  const call = (token: string, clientRequestId: string, requestType = 'ANALYZE_WORKOUT') =>
    request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${token}`)
      .send(fixtures.requestBody(requestType, fixtures.analysisContext(), { clientRequestId }));

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  // ------------------------------------------------------------------- quota por usuário

  it('a quota diária por conta recusa a chamada seguinte com 429', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '2' });

    expect((await call(TOKEN_A, 'cli-000000000001')).status).toBe(201);
    expect((await call(TOKEN_A, 'cli-000000000002')).status).toBe(201);

    const blocked = await call(TOKEN_A, 'cli-000000000003');
    expect(blocked.status).toBe(429);
    expect(blocked.body.error.code).toBe('AI_USER_QUOTA_EXCEEDED');
    // A terceira não chegou ao provider.
    expect(provider.callCount).toBe(2);
  });

  it('a quota de uma conta não afeta a outra', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '1' });

    expect((await call(TOKEN_A, 'cli-000000000001')).status).toBe(201);
    expect((await call(TOKEN_A, 'cli-000000000002')).status).toBe(429);
    expect((await call(TOKEN_B, 'cli-000000000003')).status).toBe(201);
  });

  // ---------------------------------------------------------------------- quota global

  it('a quota global do servidor recusa mesmo uma conta que ainda tem saldo', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider, {
      AI_MAX_REQUESTS_PER_USER_DAY: '100',
      AI_MAX_REQUESTS_GLOBAL_DAY: '2',
    });

    expect((await call(TOKEN_A, 'cli-000000000001')).status).toBe(201);
    expect((await call(TOKEN_B, 'cli-000000000002')).status).toBe(201);

    const blocked = await call(TOKEN_A, 'cli-000000000003');
    expect(blocked.status).toBe(429);
    expect(blocked.body.error.code).toBe('AI_GLOBAL_QUOTA_EXCEEDED');
    expect(provider.callCount).toBe(2);
  });

  it('quota estourada não deixa a contagem inflada — a recusa não consome saldo', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '1' });

    await call(TOKEN_A, 'cli-000000000001');
    await call(TOKEN_A, 'cli-000000000002');
    await call(TOKEN_A, 'cli-000000000003');

    expect(totalRequests(temp.path, UID_A)).toBe(1);
  });

  // ------------------------------------------------------------ o que consome e o que não

  it('requisição inválida não consome quota: ela não chega ao provider', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '5' });

    await request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${TOKEN_A}`)
      .send(
        fixtures.requestBody(
          'ANALYZE_WORKOUT',
          { athlete: { completedSessionsInWindow: 'muitas' } },
          { clientRequestId: 'cli-invalida-01' },
        ),
      );

    expect(totalRequests(temp.path, UID_A)).toBe(0);
    expect(provider.callCount).toBe(0);
  });

  it('sem token não existe consumo de quota', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider);

    await request(app.getHttpServer())
      .post('/v1/ai/coach')
      .send(fixtures.requestBody('ANALYZE_WORKOUT', fixtures.analysisContext()));

    expect(totalRequests(temp.path, UID_A)).toBe(0);
  });

  it('tentativa que chegou ao provider consome quota mesmo quando o provider falha', async () => {
    const provider = FakeAiProviderGateway.failingWith(
      new AiProviderError('UNAVAILABLE', 'falha do provider'),
    );
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '5' });

    const response = await call(TOKEN_A, 'cli-000000000001');

    expect(response.status).toBe(503);
    // Falhar depois de chamar já pode ter custado: spam de erro não é spam grátis.
    expect(totalRequests(temp.path, UID_A)).toBe(1);
  });

  it('resposta recusada na validação também consome quota', async () => {
    const output = fixtures.analysisOutput();
    output.recommendations[0].exerciseId = fixtures.INVENTADO;
    const provider = FakeAiProviderGateway.respondingWith(output);
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '5' });

    expect((await call(TOKEN_A, 'cli-000000000001')).status).toBe(422);
    expect(totalRequests(temp.path, UID_A)).toBe(1);
  });

  // ------------------------------------------------------------------------ concorrência

  it('chamadas simultâneas da mesma conta viram uma só no provider', async () => {
    const provider = new BlockingFakeAiProviderGateway(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '50' });

    const first = call(TOKEN_A, 'cli-000000000001').then((response) => response);
    // Espera o primeiro chegar ao provider (onde ele fica preso) antes de disparar o segundo.
    await waitFor(() => provider.callCount === 1);

    const second = await call(TOKEN_A, 'cli-000000000002');
    expect(second.status).toBe(409);
    expect(second.body.error.code).toBe('AI_REQUEST_CONFLICT');
    expect(provider.callCount).toBe(1);

    provider.releaseAll();
    expect((await first).status).toBe(201);

    // Terminada a primeira, a vaga volta a existir.
    const third = call(TOKEN_A, 'cli-000000000003').then((response) => response);
    await waitFor(() => provider.callCount === 2);
    provider.releaseAll();
    expect((await third).status).toBe(201);
  });

  /**
   * T18.3.1 — a causa raiz do 409 observado em produção: o Android desistia (transporte, 20 s)
   * antes de o backend terminar de esperar o Gemini (30 s), e a vaga da primeira chamada
   * continuava reservada até o timeout do provider realmente estourar. O `finally` de
   * `AiCoachService.handle()` já liberava a vaga também neste caminho — mas nada testava
   * exatamente isso, distinto do caminho de sucesso já coberto acima.
   */
  it('timeout do provider libera a vaga, e a próxima chamada não encontra lock preso', async () => {
    const provider = new BlockingFakeAiProviderGateway(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '50' });

    const first = call(TOKEN_A, 'cli-000000000001').then((response) => response);
    await waitFor(() => provider.callCount === 1);

    // Enquanto a primeira ainda está presa, uma segunda tentativa real vê a vaga ocupada.
    const second = await call(TOKEN_A, 'cli-000000000002');
    expect(second.status).toBe(409);
    expect(second.body.error.code).toBe('AI_REQUEST_CONFLICT');

    const registry = app.get(AiRequestRegistry);
    expect(registry.activeFor(UID_A)).toBe(1);

    // A primeira estoura exatamente como o AbortController real do GeminiAiProviderGateway faria.
    provider.failAll(new AiProviderError('TIMEOUT', 'provider não respondeu no tempo permitido'));
    const firstResponse = await first;
    expect(firstResponse.status).toBe(504);
    expect(firstResponse.body.error.code).toBe('AI_PROVIDER_TIMEOUT');
    expect(registry.activeFor(UID_A)).toBe(0);

    // A vaga está livre: a próxima chamada válida não encontra lock preso. O provider dublê ainda
    // bloqueia por chamada, então esta também precisa do padrão fire → espera → libera → aguarda.
    const third = call(TOKEN_A, 'cli-000000000003').then((response) => response);
    await waitFor(() => provider.callCount === 2);
    expect(registry.activeFor(UID_A)).toBe(1);
    provider.releaseAll();

    const thirdResponse = await third;
    expect(thirdResponse.status).toBe(201);
    expect(registry.activeFor(UID_A)).toBe(0);
  });

  it('toque duplo com o mesmo clientRequestId não vira duas chamadas', async () => {
    const provider = new BlockingFakeAiProviderGateway(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '50' });

    const first = call(TOKEN_A, 'cli-mesmo-request-01').then((response) => response);
    await waitFor(() => provider.callCount === 1);

    const duplicate = await call(TOKEN_A, 'cli-mesmo-request-01');
    expect(duplicate.status).toBe(409);
    expect(provider.callCount).toBe(1);

    provider.releaseAll();
    await first;
    expect(totalRequests(temp.path, UID_A)).toBe(1);
  });

  it('contas diferentes não disputam a mesma vaga', async () => {
    const provider = new BlockingFakeAiProviderGateway(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '50' });

    const first = call(TOKEN_A, 'cli-000000000001').then((response) => response);
    await waitFor(() => provider.callCount === 1);

    const other = call(TOKEN_B, 'cli-000000000002').then((response) => response);
    await waitFor(() => provider.callCount === 2);

    provider.releaseAll();
    expect((await first).status).toBe(201);
    expect((await other).status).toBe(201);
  });
});

async function waitFor(condition: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error('condição não aconteceu no tempo esperado');
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

/* eslint-disable @typescript-eslint/no-require-imports */
function totalRequests(path: string, uid: string): number {
  const Database = require('better-sqlite3') as typeof import('better-sqlite3');
  const db = new Database(path, { readonly: true });
  try {
    const row = db
      .prepare('SELECT COALESCE(SUM(request_count), 0) AS total FROM ai_usage_daily WHERE uid = ?')
      .get(uid) as { total: number };
    return row.total;
  } finally {
    db.close();
  }
}
