import { SparkLogger } from '../src/common/logger';
import { AiProviderConfig } from '../src/config/ai-provider-settings';
import { responseSchemaFor } from '../src/modules/ai/ai-coach.output.schema';
import {
  AiProviderError,
  type AiProviderRequest,
} from '../src/modules/ai/provider/ai-provider.gateway';
import { GroqAiProviderGateway } from '../src/modules/ai/provider/groq-ai-provider.gateway';

/**
 * Valor de teste deliberadamente fora do formato real de uma chave da Groq (`gsk_…`): o que se
 * prova é "o valor configurado nunca aparece no log", e qualquer string distinta serve.
 */
const GROQ_KEY = 'chave-de-teste-da-groq-nao-e-uma-credencial';

/** A mensagem que a Groq devolve num 429 — com o id de organização que nunca pode ir para log. */
const RATE_LIMIT_MESSAGE =
  'Rate limit reached for model `openai/gpt-oss-120b` in organization `org_01segredo` service tier `on_demand` on tokens per day (TPD): Limit 200000, Used 199000, Requested 2400. Please try again in 7m.';

interface CapturedCall {
  readonly url: string;
  readonly method: string;
  readonly authorization: string | null;
  readonly body: Record<string, unknown>;
}

/**
 * Um `fetch` falso na frente do **SDK real**: retry, timeout, classes de erro e parse da resposta
 * são os do `groq-sdk`, e só a rede é substituída. É isso que torna "zero retry" e "timeout vira
 * TIMEOUT" afirmações sobre o comportamento real, e não sobre um dublê do cliente.
 */
class FakeGroqFetch {
  readonly calls: CapturedCall[] = [];

  constructor(
    private readonly respond: (call: CapturedCall, init?: RequestInit) => Promise<Response>,
  ) {}

  static json(status: number, body: unknown, headers: Record<string, string> = {}): FakeGroqFetch {
    return new FakeGroqFetch(() =>
      Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { 'content-type': 'application/json', ...headers },
        }),
      ),
    );
  }

  static completion(
    content: string | null,
    overrides: Record<string, unknown> = {},
  ): FakeGroqFetch {
    return FakeGroqFetch.json(200, {
      id: 'chatcmpl-teste',
      object: 'chat.completion',
      created: 1_780_000_000,
      model: 'openai/gpt-oss-120b',
      choices: [
        {
          index: 0,
          message: { role: 'assistant', content },
          finish_reason: 'stop',
          logprobs: null,
        },
      ],
      usage: {
        prompt_tokens: 1200,
        completion_tokens: 340,
        total_tokens: 1540,
        completion_tokens_details: { reasoning_tokens: 120 },
      },
      ...overrides,
    });
  }

  /** Nunca responde: só rejeita quando o SDK aborta (timeout). */
  static hanging(): FakeGroqFetch {
    return new FakeGroqFetch(
      (_call, init) =>
        new Promise<Response>((_, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' }));
          });
        }),
    );
  }

  readonly fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const headers = new Headers(init?.headers);
    const call: CapturedCall = {
      url: input instanceof Request ? input.url : input.toString(),
      method: init?.method ?? 'GET',
      authorization: headers.get('authorization'),
      body:
        typeof init?.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : {},
    };
    this.calls.push(call);
    return this.respond(call, init);
  };
}

function settingsWith(overrides: Record<string, string> = {}): AiProviderConfig {
  return AiProviderConfig.fromEnv({
    LOG_LEVEL: 'silent',
    AI_PROVIDER: 'groq',
    GROQ_API_KEY: GROQ_KEY,
    ...overrides,
  });
}

function request(
  type: Parameters<typeof responseSchemaFor>[0] = 'ADAPT_WORKOUT',
): AiProviderRequest {
  return {
    requestId: 'req-teste-0001',
    systemInstruction: 'Instrução de sistema do teste.',
    userPrompt: 'Prompt do usuário do teste.',
    responseSchema: responseSchemaFor(type),
  };
}

const VALID_ADAPTATION = JSON.stringify({
  summary: 'Nada a ajustar.',
  changes: [],
  dataQuality: { level: 'GOOD', description: 'Três sessões.' },
});

describe('GroqAiProviderGateway', () => {
  let logs: string[];
  let restore: () => void;

  beforeEach(() => {
    logs = [];
    const original = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]): boolean => {
      logs.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
      return original(chunk as never, ...(rest as []));
    }) as typeof process.stdout.write;
    restore = () => {
      process.stdout.write = original;
    };
  });

  afterEach(() => restore());

  const gatewayWith = (fake: FakeGroqFetch, overrides: Record<string, string> = {}) => {
    const settings = settingsWith({ LOG_LEVEL: 'info', ...overrides });
    return new GroqAiProviderGateway(settings, new SparkLogger(settings), { fetch: fake.fetch });
  };

  const failureOf = async (promise: Promise<unknown>): Promise<AiProviderError> => {
    try {
      await promise;
    } catch (error) {
      expect(error).toBeInstanceOf(AiProviderError);
      return error as AiProviderError;
    }
    throw new Error('esperava falha do provider');
  };

  // ------------------------------------------------------------------------ configuração

  it('sem GROQ_API_KEY a falha é NOT_CONFIGURED, sem nenhuma chamada de rede', async () => {
    const fake = FakeGroqFetch.completion(VALID_ADAPTATION);
    const settings = AiProviderConfig.fromEnv({ LOG_LEVEL: 'silent', AI_PROVIDER: 'groq' });
    const gateway = new GroqAiProviderGateway(settings, new SparkLogger(settings), {
      fetch: fake.fetch,
    });

    const failure = await failureOf(gateway.generate(request()));

    expect(failure.kind).toBe('NOT_CONFIGURED');
    expect(fake.calls).toHaveLength(0);
  });

  it('o descriptor identifica provider e modelo configurado', () => {
    const gateway = gatewayWith(FakeGroqFetch.completion(VALID_ADAPTATION), {
      GROQ_MODEL: 'qwen/qwen3.8-27b',
    });
    expect(gateway.descriptor).toEqual({ provider: 'groq', model: 'qwen/qwen3.8-27b' });
  });

  // ------------------------------------------------------------------------ requisição

  it('monta a requisição de chat completion com mensagens, modelo e parâmetros do Spark', async () => {
    const fake = FakeGroqFetch.completion(VALID_ADAPTATION);
    const gateway = gatewayWith(fake, {
      AI_TEMPERATURE: '0.3',
      AI_MAX_OUTPUT_TOKENS: '4096',
      AI_THINKING_LEVEL: 'LOW',
    });

    await gateway.generate(request());

    expect(fake.calls).toHaveLength(1);
    const [call] = fake.calls;
    expect(call.method).toBe('POST');
    expect(call.url).toBe('https://api.groq.com/openai/v1/chat/completions');
    expect(call.authorization).toBe(`Bearer ${GROQ_KEY}`);
    expect(call.body).toMatchObject({
      model: 'openai/gpt-oss-120b',
      messages: [
        { role: 'system', content: 'Instrução de sistema do teste.' },
        { role: 'user', content: 'Prompt do usuário do teste.' },
      ],
      temperature: 0.3,
      max_completion_tokens: 4096,
      reasoning_effort: 'low',
      include_reasoning: false,
      stream: false,
    });
    // O raciocínio não volta, e os dois parâmetros de visibilidade nunca vão juntos.
    expect(call.body).not.toHaveProperty('reasoning_format');
  });

  it('GROQ_MAX_OUTPUT_TOKENS vale no lugar do teto compartilhado', async () => {
    const fake = FakeGroqFetch.completion(VALID_ADAPTATION);
    await gatewayWith(fake, {
      AI_MAX_OUTPUT_TOKENS: '2048',
      GROQ_MAX_OUTPUT_TOKENS: '3000',
    }).generate(request());
    expect(fake.calls[0].body.max_completion_tokens).toBe(3000);
  });

  it('pede structured output strict com o schema do Coach convertido para JSON Schema', async () => {
    const fake = FakeGroqFetch.completion(VALID_ADAPTATION);
    await gatewayWith(fake).generate(request('ADAPT_WORKOUT'));

    const format = fake.calls[0].body.response_format as {
      type: string;
      json_schema: { name: string; strict: boolean; schema: Record<string, unknown> };
    };
    expect(format.type).toBe('json_schema');
    expect(format.json_schema.strict).toBe(true);
    expect(format.json_schema.name).toMatch(/^[a-zA-Z0-9_-]{1,64}$/);
    const schema = format.json_schema.schema;
    expect(schema.type).toBe('object');
    expect(schema.additionalProperties).toBe(false);
    expect(schema.required).toEqual(['summary', 'changes', 'dataQuality']);
    // Nada de "responda apenas JSON" no prompt: o formato vem do response_format.
    expect(JSON.stringify(fake.calls[0].body.messages)).not.toMatch(/apenas JSON/i);
  });

  it('o mapeamento de raciocínio é por modelo: Qwen usa reasoning_format hidden e aceita OFF', async () => {
    const fake = FakeGroqFetch.completion(VALID_ADAPTATION);
    await gatewayWith(fake, {
      GROQ_MODEL: 'qwen/qwen3.8-27b',
      AI_THINKING_LEVEL: 'OFF',
    }).generate(request());

    expect(fake.calls[0].body).toMatchObject({
      model: 'qwen/qwen3.8-27b',
      reasoning_effort: 'none',
      reasoning_format: 'hidden',
    });
    expect(fake.calls[0].body).not.toHaveProperty('include_reasoning');
  });

  // ------------------------------------------------------------------------ resposta

  it('devolve o texto, o provider, o modelo que respondeu e os tokens cobrados', async () => {
    const fake = FakeGroqFetch.completion(VALID_ADAPTATION);
    const result = await gatewayWith(fake).generate(request());

    expect(JSON.parse(result.text)).toEqual(JSON.parse(VALID_ADAPTATION));
    expect(result.provider).toBe('groq');
    expect(result.model).toBe('openai/gpt-oss-120b');
    // completion_tokens já inclui o raciocínio: é o que a Groq conta no limite diário.
    expect(result.usage).toEqual({ promptTokens: 1200, outputTokens: 340, totalTokens: 1540 });
  });

  it('os null que só o strict obriga somem; null em campo anulável do Spark fica', async () => {
    const withNulls = JSON.stringify({
      summary: 'Subir a carga.',
      changes: [
        {
          type: 'ADJUST_LOAD',
          exerciseId: 'supino-reto-barra',
          currentWeightKg: 60,
          suggestedWeightKg: 62.5,
          currentSets: null,
          suggestedSets: null,
          currentMinReps: null,
          currentMaxReps: null,
          suggestedMinReps: null,
          suggestedMaxReps: null,
          currentRestSeconds: null,
          suggestedRestSeconds: null,
          replacementExerciseId: null,
          reason: 'Todas as séries no topo da faixa.',
          evidence: '48 repetições em 4 séries.',
          confidence: 0.7,
        },
      ],
      dataQuality: { level: 'GOOD', description: 'Três sessões.' },
    });
    const result = await gatewayWith(FakeGroqFetch.completion(withNulls)).generate(request());

    // Os campos de adaptação são anuláveis no próprio Spark: o null é legítimo e atravessa.
    expect(JSON.parse(result.text)).toEqual(JSON.parse(withNulls));
  });

  it('resposta vazia vira EMPTY_RESPONSE, com os tokens que ela custou', async () => {
    const failure = await failureOf(
      gatewayWith(FakeGroqFetch.completion('   ')).generate(request()),
    );

    expect(failure.kind).toBe('EMPTY_RESPONSE');
    expect(failure.detail.usage?.totalTokens).toBe(1540);
  });

  it('resposta truncada no teto de saída vira EMPTY_RESPONSE com finishReason, nunca JSON pela metade', async () => {
    const fake = FakeGroqFetch.completion('{"summary": "Subir a ca', {
      choices: [
        {
          index: 0,
          message: { role: 'assistant', content: '{"summary": "Subir a ca' },
          finish_reason: 'length',
          logprobs: null,
        },
      ],
    });

    const failure = await failureOf(gatewayWith(fake).generate(request()));

    expect(failure.kind).toBe('EMPTY_RESPONSE');
    expect(failure.detail.finishReason).toBe('length');
    expect(failure.detail.usage?.promptTokens).toBe(1200);
  });

  it('resposta do provider fora do formato (sem choices) vira UNAVAILABLE', async () => {
    const fake = FakeGroqFetch.json(200, { id: 'x', object: 'chat.completion', choices: [] });
    const failure = await failureOf(gatewayWith(fake).generate(request()));
    expect(failure.kind).toBe('UNAVAILABLE');
  });

  it('corpo que não é JSON vira UNAVAILABLE sem vazar o conteúdo', async () => {
    const fake = new FakeGroqFetch(() =>
      Promise.resolve(
        new Response('<html>gateway quebrado</html>', {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    );
    const failure = await failureOf(gatewayWith(fake).generate(request()));
    expect(failure.kind).toBe('UNAVAILABLE');
    expect(failure.message).not.toContain('gateway quebrado');
  });

  // ------------------------------------------------------------------------ erros

  it('429 vira RATE_LIMITED com o limite declarado — e uma tentativa só, sem retry do SDK', async () => {
    const fake = FakeGroqFetch.json(
      429,
      { error: { message: RATE_LIMIT_MESSAGE, type: 'tokens', code: 'rate_limit_exceeded' } },
      { 'retry-after': '420' },
    );

    const failure = await failureOf(gatewayWith(fake).generate(request()));

    expect(failure.kind).toBe('RATE_LIMITED');
    expect(failure.detail).toMatchObject({
      status: 429,
      limit: 'TPD',
      retryAfterSeconds: 420,
      providerCode: 'rate_limit_exceeded',
    });
    // O SDK repetiria 429 duas vezes por padrão; aqui uma intenção é uma chamada.
    expect(fake.calls).toHaveLength(1);
  });

  it('413 (requisição maior que o TPM do plano) também é limite do provider, com o limite declarado', async () => {
    const fake = FakeGroqFetch.json(413, {
      error: {
        message:
          'Request too large for model `openai/gpt-oss-120b` in organization `org_01segredo` service tier `on_demand` on tokens per minute (TPM): Limit 8000, Requested 9100, please reduce your message size and try again.',
        type: 'tokens',
        code: 'rate_limit_exceeded',
      },
    });

    const failure = await failureOf(gatewayWith(fake).generate(request()));

    expect(failure.kind).toBe('RATE_LIMITED');
    expect(failure.detail).toMatchObject({ status: 413, limit: 'TPM' });
    expect(fake.calls).toHaveLength(1);
  });

  it('o limite também é reconhecido por extenso, e um 429 sem limite reconhecível continua RATE_LIMITED', async () => {
    const longForm = FakeGroqFetch.json(429, {
      error: {
        message: 'Rate limit reached on tokens per minute. Limit 8000, Used 7990, Requested 900.',
        type: 'tokens',
        code: 'rate_limit_exceeded',
      },
    });
    const byWords = await failureOf(gatewayWith(longForm).generate(request()));
    expect(byWords.detail).toMatchObject({ status: 429, limit: 'TPM' });

    const unknown = FakeGroqFetch.json(429, {
      error: { message: 'Slow down.', code: 'rate_limit_exceeded' },
    });
    const opaque = await failureOf(gatewayWith(unknown).generate(request()));
    expect(opaque.kind).toBe('RATE_LIMITED');
    expect(opaque.detail.limit).toBeUndefined();
  });

  it('OTPM (tokens de saída por minuto) é um limite próprio, e "request too large" é marcado', async () => {
    const fake = FakeGroqFetch.json(429, {
      error: {
        message:
          "Request too large for model `qwen/qwen3.8-27b` in organization `org_01segredo` service tier `on_demand` on output tokens per minute (OTPM): Limit 1000, Requested 2249. The request's expected output tokens exceed the enforced limit.",
        type: 'tokens',
        code: 'rate_limit_exceeded',
      },
    });
    const failure = await failureOf(gatewayWith(fake).generate(request()));
    expect(failure.kind).toBe('RATE_LIMITED');
    expect(failure.detail).toMatchObject({ status: 429, limit: 'OTPM', requestTooLarge: true });
    expect(logs.join('')).not.toContain('org_01segredo');
  });

  it('5xx vira UNAVAILABLE, sem retry', async () => {
    const fake = FakeGroqFetch.json(503, {
      error: { message: 'Service Unavailable', type: 'internal_server_error' },
    });

    const failure = await failureOf(gatewayWith(fake).generate(request()));

    expect(failure.kind).toBe('UNAVAILABLE');
    expect(failure.detail.status).toBe(503);
    expect(fake.calls).toHaveLength(1);
  });

  it('400 de schema recusado é UNAVAILABLE (configuração), não resposta inválida do modelo', async () => {
    const fake = FakeGroqFetch.json(400, {
      error: { message: 'response_format: unsupported keyword', type: 'invalid_request_error' },
    });
    const failure = await failureOf(gatewayWith(fake).generate(request()));
    expect(failure.kind).toBe('UNAVAILABLE');
    expect(failure.detail).toMatchObject({ status: 400, providerCode: 'invalid_request_error' });
  });

  it('400 json_validate_failed é resposta inválida do modelo (EMPTY_RESPONSE)', async () => {
    const fake = FakeGroqFetch.json(400, {
      error: {
        message: 'Failed to validate JSON',
        type: 'invalid_request_error',
        code: 'json_validate_failed',
      },
    });
    const failure = await failureOf(gatewayWith(fake).generate(request()));
    expect(failure.kind).toBe('EMPTY_RESPONSE');
  });

  it('timeout vira TIMEOUT dentro do teto configurado, com uma tentativa só', async () => {
    const fake = FakeGroqFetch.hanging();
    const started = Date.now();

    const failure = await failureOf(
      gatewayWith(fake, { AI_TIMEOUT_MS: '1000' }).generate(request()),
    );

    expect(failure.kind).toBe('TIMEOUT');
    expect(Date.now() - started).toBeLessThan(5_000);
    expect(fake.calls).toHaveLength(1);
  });

  it('nada do conteúdo, da chave ou da mensagem crua da Groq chega ao log', async () => {
    const fake = FakeGroqFetch.json(429, {
      error: { message: RATE_LIMIT_MESSAGE, type: 'tokens', code: 'rate_limit_exceeded' },
    });
    await failureOf(gatewayWith(fake).generate(request()));
    await gatewayWith(FakeGroqFetch.completion(VALID_ADAPTATION)).generate(request());

    const output = logs.join('\n');
    expect(output).toContain('ai.provider.rate_limited');
    expect(output).toContain('"limit":"TPD"');
    for (const forbidden of [
      GROQ_KEY,
      'org_01segredo',
      'Rate limit reached',
      'Instrução de sistema do teste',
      'Prompt do usuário do teste',
      'Nada a ajustar',
    ]) {
      expect(output).not.toContain(forbidden);
    }
  });
});
