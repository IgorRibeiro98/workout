import Groq, { APIConnectionTimeoutError, APIError } from 'groq-sdk';
import type { ChatCompletion } from 'groq-sdk/resources/chat/completions';
import { SparkLogger } from '../../../common/logger';
import type { AiProviderSettings } from '../../../config/ai-provider-settings';
import type { AiOutputSchema } from '../ai-coach.output.schema';
import {
  AI_PROVIDER_LIMITS,
  AiProviderError,
  type AiProviderDescriptor,
  type AiProviderFailureDetail,
  type AiProviderGateway,
  type AiProviderLimit,
  type AiProviderRequest,
  type AiProviderResult,
  type AiProviderUsage,
} from './ai-provider.gateway';
import { dropStrictOnlyNulls, toGroqStrictJsonSchema } from './groq-json-schema';
import { groqModelProfile } from './groq-model-profiles';

/**
 * O **único** arquivo do Spark que conhece a Groq (T19.H4).
 *
 * O mesmo contrato do gateway do Gemini, e nada além dele: recebe prompt e schema já decididos,
 * faz **uma** chamada e devolve o texto cru ou um erro tipado. Não conhece treino, exercício,
 * capability, quota, uid nem regra de domínio. Quem o instancia é `ai-provider.factory.ts`, quando
 * `AI_PROVIDER=groq`.
 *
 * ## SDK e credencial
 *
 * O SDK oficial (`groq-sdk`), pela mesma decisão que pôs o `@google/genai` do outro lado: o
 * fornecedor mantém o protocolo, os tipos e as classes de erro, e o Spark não reimplementa HTTP.
 * Ele não tem dependência transitiva nenhuma (usa o `fetch` do Node). A chave vem de
 * `GROQ_API_KEY`, sempre do ambiente, e o endpoint é declarado aqui: uma variável `GROQ_BASE_URL`
 * esquecida no ambiente não pode mandar a chave para outro host.
 *
 * ## Sem retry
 *
 * O SDK repete 429, 408, 409 e 5xx **duas vezes por padrão**. Aqui `maxRetries: 0`: uma intenção
 * do usuário é uma inferência, e quem decide tentar de novo é o usuário (§23, §58).
 *
 * ## Structured output
 *
 * `response_format: json_schema` com `strict: true` — constrained decoding nos modelos que a Groq
 * lista para strict. O schema é o do Coach, convertido na fronteira (`groq-json-schema.ts`). Isso
 * garante forma, e só forma: `zod` e o validador semântico continuam rodando depois (§22).
 *
 * ## O que nunca sai daqui
 *
 * Nem a mensagem crua da Groq (carrega identificador de organização), nem o prompt, nem o
 * raciocínio do modelo (pedido para não voltar: `include_reasoning: false` /
 * `reasoning_format: hidden`, conforme o modelo). O log do SDK fica desligado (`logLevel: 'off'`),
 * inclusive para um `GROQ_LOG=debug` esquecido no ambiente — em `debug` ele imprimiria o corpo da
 * requisição.
 */
export class GroqAiProviderGateway implements AiProviderGateway {
  readonly descriptor: AiProviderDescriptor;
  private client?: Groq;

  constructor(
    private readonly settings: AiProviderSettings,
    private readonly logger: SparkLogger,
    /**
     * `fetch` substituto — existe só para a suíte exercitar o SDK de verdade (retry, timeout,
     * classes de erro) sem rede. A factory nunca o passa.
     */
    private readonly options: { readonly fetch?: typeof fetch } = {},
  ) {
    this.descriptor = { provider: 'groq', model: settings.groqModel };
  }

  async generate(request: AiProviderRequest): Promise<AiProviderResult> {
    const client = this.clientOrThrow();
    const model = this.settings.groqModel;

    const profile = groqModelProfile(model);
    const reasoningEffort = profile?.reasoningEffort[this.settings.aiThinkingLevel];
    if (!profile || !reasoningEffort) {
      // `AppConfig` já recusa esta combinação no startup; esta é a segunda barreira, para quem
      // montar o gateway por outro caminho. Nunca "manda sem reasoning_effort e vê o que dá".
      this.logger.error('ai.provider.unconfigured', { provider: 'groq', model });
      throw new AiProviderError('NOT_CONFIGURED', 'modelo da Groq sem perfil avaliado');
    }

    let completion: ChatCompletion;
    try {
      completion = await client.chat.completions.create(
        {
          model,
          messages: [
            { role: 'system', content: request.systemInstruction },
            { role: 'user', content: request.userPrompt },
          ],
          temperature: this.settings.aiTemperature,
          max_completion_tokens: this.settings.groqMaxOutputTokens,
          reasoning_effort: reasoningEffort,
          ...profile.reasoningVisibility,
          response_format: {
            type: 'json_schema',
            json_schema: {
              name: RESPONSE_FORMAT_NAME,
              strict: true,
              schema: toGroqStrictJsonSchema(request.responseSchema),
            },
          },
          stream: false,
        },
        { timeout: this.settings.aiTimeoutMs, maxRetries: 0 },
      );
    } catch (error) {
      throw this.translate(error, request.requestId);
    }

    const usage = usageOf(completion.usage);
    const choice = Array.isArray(completion.choices) ? completion.choices[0] : undefined;
    if (!choice) {
      this.logger.error('ai.provider.failed', {
        requestId: request.requestId,
        provider: 'groq',
        model,
        errorName: 'MalformedResponse',
      });
      throw new AiProviderError('UNAVAILABLE', 'resposta do provider fora do formato', { usage });
    }

    if (choice.finish_reason === 'length') {
      // O teto de saída conta o raciocínio do modelo; um JSON cortado nele nunca é resposta.
      throw new AiProviderError('EMPTY_RESPONSE', 'resposta truncada no teto de saída', {
        finishReason: 'length',
        usage,
      });
    }

    const content = choice.message?.content;
    if (typeof content !== 'string' || content.trim().length === 0) {
      throw new AiProviderError('EMPTY_RESPONSE', 'resposta vazia do provider', {
        finishReason: safeSlug(choice.finish_reason),
        usage,
      });
    }

    return {
      text: restoreSparkShape(content, request.responseSchema),
      provider: 'groq',
      model: typeof completion.model === 'string' && completion.model ? completion.model : model,
      usage,
    };
  }

  /**
   * O cliente do SDK, criado uma vez. Sem chave, `NOT_CONFIGURED` — estável, sem rede, e o
   * núcleo do Spark segue funcionando sem Coach.
   */
  private clientOrThrow(): Groq {
    if (this.client) {
      return this.client;
    }
    const apiKey = this.settings.groqApiKey;
    if (!apiKey) {
      this.logger.error('ai.provider.unconfigured', { provider: 'groq' });
      throw new AiProviderError('NOT_CONFIGURED', 'credencial da Groq não configurada');
    }
    this.client = new Groq({
      apiKey,
      baseURL: GROQ_BASE_URL,
      maxRetries: 0,
      timeout: this.settings.aiTimeoutMs,
      logLevel: 'off',
      ...(this.options.fetch ? { fetch: this.options.fetch } : {}),
    });
    this.logger.info('ai.provider.ready', {
      provider: 'groq',
      model: this.settings.groqModel,
      maxOutputTokens: this.settings.groqMaxOutputTokens,
      thinkingLevel: this.settings.aiThinkingLevel,
    });
    return this.client;
  }

  /**
   * Erro do SDK vira falha tipada. Da resposta de erro da Groq só atravessam o status, o limite
   * que estourou (casado contra um vocabulário fechado), o `retry-after` e o código curto — nunca a
   * mensagem.
   */
  private translate(error: unknown, requestId: string): AiProviderError {
    const model = this.settings.groqModel;

    if (error instanceof APIConnectionTimeoutError) {
      this.logger.warn('ai.provider.timeout', {
        requestId,
        provider: 'groq',
        model,
        timeoutMs: this.settings.aiTimeoutMs,
      });
      return new AiProviderError('TIMEOUT', 'provider não respondeu no tempo permitido');
    }

    const status = error instanceof APIError ? error.status : undefined;
    const body = error instanceof APIError ? providerErrorBody(error.error) : {};
    const detail: AiProviderFailureDetail = {
      status,
      providerCode: safeSlug(body.code) ?? safeSlug(body.type),
    };

    // 429 é limite de uso. 413 também, na Groq: é a resposta a uma requisição que sozinha
    // passa do limite de tokens por minuto do plano ("Request too large … (TPM)"). As duas
    // declaram qual limite foi — e é essa distinção (RPM/RPD/TPM/TPD) que o operador precisa.
    if (status === HTTP_TOO_MANY_REQUESTS || status === HTTP_PAYLOAD_TOO_LARGE) {
      const limited: AiProviderFailureDetail = {
        ...detail,
        limit: limitOf(body.message),
        requestTooLarge:
          status === HTTP_PAYLOAD_TOO_LARGE ||
          (typeof body.message === 'string' && /request too large/i.test(body.message)) ||
          undefined,
        retryAfterSeconds:
          error instanceof APIError
            ? retryAfterSeconds(error.headers?.get('retry-after'))
            : undefined,
      };
      this.logger.warn('ai.provider.rate_limited', {
        requestId,
        provider: 'groq',
        model,
        ...limited,
      });
      return new AiProviderError('RATE_LIMITED', 'provider recusou por limite de uso', limited);
    }

    // Structured output em modo best-effort pode recusar a saída do próprio modelo. Em strict não
    // deveria acontecer — mas, se acontecer, é uma resposta inválida do modelo, não o provider
    // fora do ar: o usuário recebe o mesmo `INVALID_AI_RESPONSE` de uma resposta que o `zod`
    // recusaria.
    if (status === HTTP_BAD_REQUEST && detail.providerCode === 'json_validate_failed') {
      this.logger.warn('ai.provider.output_rejected', {
        requestId,
        provider: 'groq',
        model,
        ...detail,
      });
      return new AiProviderError(
        'EMPTY_RESPONSE',
        'saída do modelo recusada pelo provider',
        detail,
      );
    }

    this.logger.error('ai.provider.failed', {
      requestId,
      provider: 'groq',
      model,
      ...detail,
      errorName: error instanceof Error ? error.constructor.name : 'UnknownError',
    });
    return new AiProviderError('UNAVAILABLE', 'falha do provider', detail);
  }
}

const GROQ_BASE_URL = 'https://api.groq.com';

/** Nome do response format. A API exige um; o conteúdo é o schema, não o nome. */
const RESPONSE_FORMAT_NAME = 'spark_coach_response';

const HTTP_BAD_REQUEST = 400;
const HTTP_PAYLOAD_TOO_LARGE = 413;
const HTTP_TOO_MANY_REQUESTS = 429;

/** O corpo de erro da Groq: `{ error: { message, type, code } }`. Tudo opcional, tudo `unknown`. */
function providerErrorBody(raw: unknown): { message?: unknown; type?: unknown; code?: unknown } {
  if (typeof raw !== 'object' || raw === null) return {};
  const inner = (raw as { error?: unknown }).error;
  if (typeof inner !== 'object' || inner === null) return {};
  return inner as { message?: unknown; type?: unknown; code?: unknown };
}

/**
 * O limite que a Groq declarou — "(TPM)", "(RPD)" ou por extenso ("tokens per minute"…) —, casado
 * contra a lista fechada. A mensagem em si nunca sai daqui: ela carrega o identificador da
 * organização na Groq. (No benchmark da T19.H4 um 429 do Qwen veio sem a sigla; por isso as duas
 * formas.)
 */
function limitOf(message: unknown): AiProviderLimit | undefined {
  if (typeof message !== 'string') return undefined;
  const abbreviation = /\((RPM|RPD|TPM|TPD|OTPM)\)/.exec(message)?.[1];
  const found =
    abbreviation ?? LONG_FORM_LIMITS.find(([pattern]) => pattern.test(message))?.[1] ?? undefined;
  return AI_PROVIDER_LIMITS.find((limit) => limit === found);
}

const LONG_FORM_LIMITS: ReadonlyArray<readonly [RegExp, AiProviderLimit]> = [
  // Antes de "tokens per minute": "output tokens per minute" também casaria com ele.
  [/output tokens per minute/i, 'OTPM'],
  [/requests per minute/i, 'RPM'],
  [/requests per day/i, 'RPD'],
  [/tokens per minute/i, 'TPM'],
  [/tokens per day/i, 'TPD'],
];

function retryAfterSeconds(header: string | null | undefined): number | undefined {
  if (!header) return undefined;
  const seconds = Number(header);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

/** Só um identificador curto (`rate_limit_exceeded`, `length`); qualquer outra forma é descartada. */
function safeSlug(value: unknown): string | undefined {
  return typeof value === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(value) ? value : undefined;
}

function usageOf(usage: ChatCompletion['usage']): AiProviderUsage | undefined {
  if (!usage) return undefined;
  const promptTokens = usage.prompt_tokens ?? 0;
  // `completion_tokens` já inclui o raciocínio (`completion_tokens_details.reasoning_tokens`).
  const outputTokens = usage.completion_tokens ?? 0;
  return {
    promptTokens,
    outputTokens,
    totalTokens: usage.total_tokens ?? promptTokens + outputTokens,
  };
}

/**
 * O texto no formato do contrato do Spark: os `null` que só existem por exigência do strict saem
 * (`dropStrictOnlyNulls`). Texto que não é JSON segue como veio — o serviço o recusa como recusaria
 * de qualquer provider.
 */
function restoreSparkShape(content: string, schema: AiOutputSchema): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(content);
  } catch {
    return content;
  }
  return JSON.stringify(dropStrictOnlyNulls(parsed, schema));
}
