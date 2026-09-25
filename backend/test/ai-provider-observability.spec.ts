import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AiUsageRepository } from '../src/modules/ai/ai-usage.repository';
import { AiProviderError } from '../src/modules/ai/provider/ai-provider.gateway';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeAiProviderGateway } from './support/fake-ai-provider';
import * as fixtures from './support/ai-fixtures';

const TOKEN = 'token-da-conta-observada';
const UID = 'uid-da-conta-observada';

/**
 * T19.H4 §27/§29 — o que o Coach registra por chamada, qualquer que seja o provider.
 *
 * "429" para o app pode ser a quota do Spark ou o limite do provider; para quem opera, precisa
 * dizer qual (e qual limite do provider: RPM, RPD, TPM, TPD). E uma resposta que a validação
 * recusa custou tokens — o consumo medido tem que enxergá-la. Tudo isso sem uma linha de conteúdo.
 */
describe('Observabilidade multi-provider do Coach', () => {
  let temp: TempDb;
  let app: INestApplication;
  let written: string[];
  let restore: () => void;

  beforeEach(() => {
    temp = createTempDb();
    written = [];
    const original = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]): boolean => {
      written.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
      return original(chunk as never, ...(rest as []));
    }) as typeof process.stdout.write;
    restore = () => {
      process.stdout.write = original;
    };
  });

  afterEach(async () => {
    restore();
    await app?.close();
    temp.cleanup();
  });

  const events = (name: string): Array<Record<string, unknown>> =>
    written
      .join('')
      .split('\n')
      .filter((line) => line.startsWith('{'))
      .map((line) => JSON.parse(line) as Record<string, unknown>)
      .filter((event) => event.event === name);

  const start = async (
    provider?: FakeAiProviderGateway,
    overrides: Record<string, string> = {},
  ) => {
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID });
    app = await createTestApp(
      configFor(temp.path, { LOG_LEVEL: 'info', ...overrides }),
      verifier,
      provider,
    );
  };

  const coach = (clientRequestId = 'cli-observabilidade-01') =>
    request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(
        fixtures.requestBody('ANALYZE_WORKOUT', fixtures.analysisContext(), { clientRequestId }),
      );

  it('limite do provider: 429 para o app, e no log o provider, o modelo e QUAL limite estourou', async () => {
    const provider = FakeAiProviderGateway.failingWith(
      new AiProviderError('RATE_LIMITED', 'provider recusou por limite de uso', {
        status: 429,
        limit: 'TPD',
        retryAfterSeconds: 420,
      }),
    );
    await start(provider);

    const response = await coach();

    expect(response.status).toBe(429);
    expect(response.body.error.code).toBe('AI_GLOBAL_QUOTA_EXCEEDED');
    const [finished] = events('ai.request.finished');
    expect(finished).toMatchObject({
      status: 'PROVIDER_FAILED',
      provider: 'fake',
      model: 'fake-model',
      requestType: 'ANALYZE_WORKOUT',
      failureKind: 'RATE_LIMITED',
      limitSource: 'PROVIDER',
      limit: 'TPD',
      providerStatus: 429,
      retryAfterSeconds: 420,
    });
    expect(typeof finished.durationMs).toBe('number');
    expect(events('ai.quota.exceeded')).toHaveLength(0);
  });

  it('quota do Spark: o mesmo 429 para o app, mas limitSource SPARK e nenhuma chamada ao provider', async () => {
    const provider = FakeAiProviderGateway.respondingWith(fixtures.analysisOutput());
    await start(provider, { AI_MAX_REQUESTS_PER_USER_DAY: '1' });

    expect((await coach('cli-observabilidade-01')).status).toBe(201);
    const blocked = await coach('cli-observabilidade-02');

    expect(blocked.status).toBe(429);
    expect(provider.callCount).toBe(1);
    expect(events('ai.quota.exceeded')[0]).toMatchObject({ scope: 'USER', limitSource: 'SPARK' });
  });

  it('timeout e indisponibilidade saem com failureKind e o status do provider', async () => {
    const provider = FakeAiProviderGateway.failingWith(
      new AiProviderError('UNAVAILABLE', 'falha do provider', { status: 503 }),
    );
    await start(provider);

    expect((await coach()).status).toBe(503);
    expect(events('ai.request.finished')[0]).toMatchObject({
      status: 'PROVIDER_FAILED',
      failureKind: 'UNAVAILABLE',
      providerStatus: 503,
    });
    expect(events('ai.request.finished')[0]).not.toHaveProperty('limitSource');
  });

  it('resposta recusada pela validação também registra os tokens que custou', async () => {
    const output = fixtures.analysisOutput();
    output.recommendations[0].exerciseId = fixtures.INVENTADO;
    await start(FakeAiProviderGateway.respondingWith(output));

    const response = await coach();

    expect(response.status).toBe(422);
    const rows = usageRows(temp.path);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ request_count: 1, prompt_tokens: 100, total_tokens: 150 });
    expect(events('ai.request.finished')[0]).toMatchObject({
      status: 'INVALID_RESPONSE',
      rejectionStage: 'SEMANTIC',
      totalTokens: 150,
    });
  });

  it('resposta truncada (EMPTY_RESPONSE com uso) registra os tokens e vira 422', async () => {
    const provider = FakeAiProviderGateway.failingWith(
      new AiProviderError('EMPTY_RESPONSE', 'resposta truncada no teto de saída', {
        finishReason: 'length',
        usage: { promptTokens: 1800, outputTokens: 2048, totalTokens: 3848 },
      }),
    );
    await start(provider);

    const response = await coach();

    expect(response.status).toBe(422);
    expect(usageRows(temp.path)[0]).toMatchObject({ total_tokens: 3848 });
    expect(events('ai.request.finished')[0]).toMatchObject({
      failureKind: 'EMPTY_RESPONSE',
      finishReason: 'length',
      totalTokens: 3848,
    });
  });

  it('falha ao gravar tokens não troca o erro do provider por 500', async () => {
    const provider = FakeAiProviderGateway.failingWith(
      new AiProviderError('EMPTY_RESPONSE', 'resposta truncada no teto de saída', {
        finishReason: 'length',
        usage: { promptTokens: 10, outputTokens: 20, totalTokens: 30 },
      }),
    );
    await start(provider);
    const usage = app.get(AiUsageRepository);
    jest.spyOn(usage, 'recordTokens').mockRejectedValueOnce(new Error('banco indisponível'));

    const response = await coach();

    expect(response.status).toBe(422);
    expect(response.body.error.code).toBe('INVALID_AI_RESPONSE');
    expect(events('ai.usage.tokens_not_recorded')[0]).toMatchObject({ totalTokens: 30 });
  });

  it('nenhum dos eventos novos carrega conteúdo do usuário ou do modelo', async () => {
    const output = fixtures.analysisOutput();
    output.summary = 'Resumo que o modelo escreveu e que não pode ir para log.';
    output.recommendations[0].exerciseId = fixtures.INVENTADO;
    await start(FakeAiProviderGateway.respondingWith(output));

    await coach();

    const log = written.join('');
    expect(log).not.toContain('Resumo que o modelo escreveu');
    expect(log).not.toContain('Supino reto com barra');
    expect(log).not.toContain('Treino A');
    expect(log).not.toContain(UID);
  });

  it('AI_PROVIDER=groq sem chave, no módulo real: indisponível, sem rede e sem derrubar o resto', async () => {
    // Sem dublê: a factory monta o GroqAiProviderGateway de verdade, que falha como
    // NOT_CONFIGURED antes de criar o cliente do SDK.
    await start(undefined, { AI_PROVIDER: 'groq' });

    const response = await coach();

    expect(response.status).toBe(503);
    expect(response.body.error.code).toBe('AI_PROVIDER_UNAVAILABLE');
    expect(events('ai.provider.unconfigured')[0]).toMatchObject({ provider: 'groq' });
    expect(events('ai.request.finished')[0]).toMatchObject({
      provider: 'groq',
      model: 'openai/gpt-oss-120b',
      failureKind: 'NOT_CONFIGURED',
    });
    const health = await request(app.getHttpServer()).get('/health/ready');
    expect(health.status).toBe(200);
  });
});

/* eslint-disable @typescript-eslint/no-require-imports */
function usageRows(path: string): Array<Record<string, string | number>> {
  const Database = require('better-sqlite3') as typeof import('better-sqlite3');
  const db = new Database(path, { readonly: true });
  try {
    return db.prepare('SELECT * FROM ai_usage_daily').all() as Array<
      Record<string, string | number>
    >;
  } finally {
    db.close();
  }
}
