import { Inject, Injectable } from '@nestjs/common';
import {
  GoogleGenAI,
  ThinkingLevel,
  type GenerateContentResponse,
  type Schema,
} from '@google/genai';
import { SparkLogger } from '../../../common/logger';
import { APP_CONFIG, AppConfig } from '../../../config/app-config';
import {
  AiProviderError,
  type AiProviderGateway,
  type AiProviderRequest,
  type AiProviderResult,
} from './ai-provider.gateway';

/**
 * O **único** arquivo do Spark que conhece o Gemini.
 *
 * Ele recebe prompt e schema já decididos, faz **uma** chamada e devolve o texto cru ou um erro
 * tipado. Não valida conteúdo, não conhece tipo de request, não toca banco e não sabe o que é um
 * treino.
 *
 * ## SDK e credencial
 *
 * Usa o SDK server-side oficial (`@google/genai`) falando direto com a Gemini API. Não há
 * Firebase AI Logic aqui: aquele SDK é de cliente, e usá-lo no servidor seria manter o desenho
 * que a T16.2 veio desfazer. A chave vem de `GEMINI_API_KEY`, sempre do ambiente.
 *
 * ## Sem retry
 *
 * Uma tentativa por chamada, deliberadamente (§30). Repetir automaticamente depois de timeout
 * incerto, conexão cortada ou 5xx pode cobrar duas vezes pela mesma intenção do usuário. Quem
 * decide tentar de novo é o usuário, com um toque novo — que passa de novo por quota.
 */
@Injectable()
export class GeminiAiProviderGateway implements AiProviderGateway {
  private client?: GoogleGenAI;

  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
  ) {}

  async generate(request: AiProviderRequest): Promise<AiProviderResult> {
    const client = this.clientOrThrow();
    const model = this.config.geminiModel;

    // O SDK aceita `abortSignal`, mas ele cancela apenas do lado do cliente: o provider pode já
    // ter começado a cobrar. Por isso o timeout é teto de espera, nunca promessa de não-cobrança.
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.aiTimeoutMs);

    let response: GenerateContentResponse;
    try {
      response = await client.models.generateContent({
        model,
        contents: request.userPrompt,
        config: {
          systemInstruction: request.systemInstruction,
          temperature: this.config.aiTemperature,
          maxOutputTokens: this.config.aiMaxOutputTokens,
          responseMimeType: 'application/json',
          // O único ponto de contato entre o schema do Spark e o tipo do SDK. O `Type` do
          // `@google/genai` é um enum de strings com exatamente estes valores, então a conversão
          // é nominal: nenhum campo é traduzido, renomeado ou perdido.
          responseSchema: request.responseSchema as Schema,
          ...this.thinkingConfig(),
          abortSignal: controller.signal,
        },
      });
    } catch (error) {
      throw this.translate(error, request.requestId, controller.signal.aborted);
    } finally {
      clearTimeout(timer);
    }

    const text = response.text;
    if (typeof text !== 'string' || text.trim().length === 0) {
      // Resposta vazia acontece quando o modelo é bloqueado ou para antes de escrever. Nem o
      // motivo detalhado nem o corpo vão para o log: só o fato.
      throw new AiProviderError('EMPTY_RESPONSE', 'resposta vazia do provider');
    }

    return {
      text,
      model: response.modelVersion ?? model,
      usage: usageOf(response),
    };
  }

  /**
   * O cliente do SDK, criado uma vez.
   *
   * Sem chave, a falha é `NOT_CONFIGURED` e é estável: o servidor não tenta de novo a cada
   * requisição só para falhar igual, e o núcleo do Spark segue funcionando sem Coach.
   */
  private clientOrThrow(): GoogleGenAI {
    if (this.client) {
      return this.client;
    }
    const apiKey = this.config.geminiApiKey;
    if (!apiKey) {
      this.logger.error('ai.provider.unconfigured');
      throw new AiProviderError('NOT_CONFIGURED', 'credencial do Gemini não configurada');
    }
    this.client = new GoogleGenAI({ apiKey });
    this.logger.info('ai.provider.ready', { model: this.config.geminiModel });
    return this.client;
  }

  private thinkingConfig(): { thinkingConfig?: { thinkingLevel: ThinkingLevel } } {
    const level = this.config.aiThinkingLevel;
    if (level === 'OFF') {
      return {};
    }
    return { thinkingConfig: { thinkingLevel: ThinkingLevel[level] } };
  }

  /**
   * Erro do SDK vira falha tipada, sem que nada do SDK atravesse.
   *
   * A mensagem original pode carregar endpoint, corpo da requisição ou parte da chave — ela não
   * entra no log nem na resposta. O que sobra é a classe do erro e, quando existe, o status HTTP.
   */
  private translate(error: unknown, requestId: string, aborted: boolean): AiProviderError {
    if (aborted) {
      this.logger.warn('ai.provider.timeout', { requestId, timeoutMs: this.config.aiTimeoutMs });
      return new AiProviderError('TIMEOUT', 'provider não respondeu no tempo permitido');
    }

    const status =
      typeof error === 'object' && error !== null && 'status' in error
        ? Number((error as { status: unknown }).status)
        : undefined;

    if (status === HTTP_TOO_MANY_REQUESTS) {
      this.logger.warn('ai.provider.rate_limited', { requestId });
      return new AiProviderError('RATE_LIMITED', 'provider recusou por limite de uso');
    }

    this.logger.error('ai.provider.failed', {
      requestId,
      status: Number.isFinite(status) ? status : undefined,
      errorName: error instanceof Error ? error.name : 'UnknownError',
    });
    return new AiProviderError('UNAVAILABLE', 'falha do provider');
  }
}

const HTTP_TOO_MANY_REQUESTS = 429;

/** Contagem de tokens, quando o provider a informa. Metadata técnica — nunca conteúdo. */
function usageOf(response: GenerateContentResponse): AiProviderResult['usage'] {
  const usage = response.usageMetadata;
  if (!usage) {
    return undefined;
  }
  const promptTokens = usage.promptTokenCount ?? 0;
  const outputTokens = usage.candidatesTokenCount ?? 0;
  return {
    promptTokens,
    outputTokens,
    totalTokens: usage.totalTokenCount ?? promptTokens + outputTokens,
  };
}
