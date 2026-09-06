import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeAiProviderGateway } from './support/fake-ai-provider';
import * as fixtures from './support/ai-fixtures';

const TOKEN = 'token-secreto-do-usuario-abc123';
const UID = 'uid-do-usuario-a';
/**
 * Valor de teste, deliberadamente **fora** do formato real de uma chave do Google.
 *
 * O que este teste prova é "o valor configurado nunca aparece no log" — e para isso qualquer
 * string distinta serve. Escrever algo com a cara de uma chave real faria o varredor de segredos
 * do repositório apontar este arquivo, que é exatamente o alarme que não se deve treinar a ignorar.
 */
const GEMINI_KEY = 'chave-de-teste-do-gemini-nao-e-uma-credencial';

/**
 * O que o Coach registra — e o que ele nunca registra.
 *
 * O log precisa responder "qual chamada falhou, quanto demorou, com qual modelo e que erro deu".
 * Ele não pode responder "o que o usuário treinou" nem "o que o modelo escreveu". Este teste
 * captura a saída real do `pino` e procura o que não pode estar lá.
 */
describe('Observabilidade do Coach: metadata sim, conteúdo não', () => {
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

  const logs = () => written.join('\n');

  it('uma chamada completa registra correlação e custo, e nada de conteúdo', async () => {
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID });
    const provider = FakeAiProviderGateway.respondingWith(
      fixtures.analysisOutput(),
      'gemini-teste',
    );
    app = await createTestApp(
      configFor(temp.path, { LOG_LEVEL: 'info', GEMINI_API_KEY: GEMINI_KEY }),
      verifier,
      provider,
    );

    const context = fixtures.analysisContext();
    const response = await request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(fixtures.requestBody('ANALYZE_WORKOUT', context));

    expect(response.status).toBe(201);
    const output = logs();

    // O que precisa estar: correlação, tipo, versões, modelo e custo.
    expect(output).toContain('ai.request.finished');
    expect(output).toContain('cli-11111111-2222-3333');
    expect(output).toContain('ANALYZE_WORKOUT');
    expect(output).toContain('gemini-teste');
    expect(output).toContain('totalTokens');

    // O que não pode estar, em nenhuma hipótese.
    expect(output).not.toContain(TOKEN);
    expect(output).not.toContain('Bearer');
    expect(output).not.toContain(GEMINI_KEY);
    // Contexto: nome de treino, nome de exercício e o id que o app enviou.
    expect(output).not.toContain('Treino A');
    expect(output).not.toContain('Supino reto com barra');
    // Resposta do modelo: nem o resumo, nem a evidência, nem a instrução de sistema.
    expect(output).not.toContain(fixtures.analysisOutput().summary);
    expect(output).not.toContain('Você é o Coach do Spark');

    // O uid inteiro não aparece; no máximo um prefixo curto para correlacionar com suporte.
    expect(output).not.toContain(UID);
    expect(output).toContain(UID.slice(0, 6));
  });

  it('resposta recusada registra a razão técnica, não o texto do modelo', async () => {
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID });
    const output = fixtures.analysisOutput();
    output.summary = 'Texto que o modelo escreveu e que não pode aparecer em log.';
    output.recommendations[0].exerciseId = fixtures.INVENTADO;
    const provider = FakeAiProviderGateway.respondingWith(output);
    app = await createTestApp(configFor(temp.path, { LOG_LEVEL: 'info' }), verifier, provider);

    const response = await request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(fixtures.requestBody('ANALYZE_WORKOUT', fixtures.analysisContext()));

    expect(response.status).toBe(422);
    expect(logs()).toContain('ai.response.rejected');
    expect(logs()).not.toContain('Texto que o modelo escreveu');
  });

  it('a resposta de erro não carrega stack, prompt nem internals do provider', async () => {
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID });
    const provider = FakeAiProviderGateway.respondingWithText('{"summary": ');
    app = await createTestApp(configFor(temp.path, { LOG_LEVEL: 'info' }), verifier, provider);

    const response = await request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send(fixtures.requestBody('ANALYZE_WORKOUT', fixtures.analysisContext()));

    expect(response.status).toBe(422);
    const body = JSON.stringify(response.body);
    expect(response.body.error).toEqual({
      code: 'INVALID_AI_RESPONSE',
      message: expect.any(String),
      requestId: expect.any(String),
    });
    for (const forbidden of [
      'stack',
      'node_modules',
      'generativelanguage',
      'Coach do Spark',
      'Supino',
    ]) {
      expect(body).not.toContain(forbidden);
    }
  });
});
