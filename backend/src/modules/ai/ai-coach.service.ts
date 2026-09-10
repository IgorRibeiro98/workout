import { Inject, Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import {
  AI_SCHEMA_VERSION,
  isSupportedSchemaVersion,
  type AiCoachHttpResponse,
  type AiCoachRequestType,
} from './ai-coach.contract';
import { AiCoachErrors } from './ai-coach.errors';
import {
  rawAdaptationOutputSchema,
  rawAnalysisOutputSchema,
  rawExplanationOutputSchema,
  rawGenerationOutputSchema,
} from './ai-coach.output.schema';
import { AiCoachPromptRegistry } from './ai-coach-prompt.registry';
import { aiCoachRequestSchema, type AiCoachRequestBody } from './ai-coach.request.schema';
import {
  validateAdaptation,
  validateAnalysis,
  validateExplanation,
  validateGeneration,
  type AiCoachValidation,
} from './ai-coach.validator';
import { AiRequestRegistry } from './ai-request.registry';
import { AiUsageRepository, utcDateOf } from './ai-usage.repository';
import {
  AI_PROVIDER_GATEWAY,
  AiProviderError,
  type AiProviderGateway,
  type AiProviderResult,
} from './provider/ai-provider.gateway';

/**
 * A orquestração do Coach no servidor — e o único lugar que decide se o Gemini será chamado.
 *
 * A ordem existe por causa de custo, e não é cosmética:
 *
 * ```text
 * token verificado (guard)
 *   → contrato (schemaVersion, corpo, tetos)
 *   → vaga de concorrência / deduplicação
 *   → quota (registrada antes da chamada)
 *   → UMA chamada ao provider
 *   → parse + validação estrutural
 *   → validação semântica contra o contexto recebido
 *   → resposta crua e validada para o Android validar de novo
 * ```
 *
 * Tudo o que pode recusar de graça recusa antes da quota; tudo o que chega ao provider conta,
 * mesmo se o provider falhar (§35). Uma ação explícita do usuário produz **no máximo uma**
 * invocação do modelo: não há crítica, reescrita, segunda opinião ou retry automático (§29, §30).
 *
 * O serviço não escreve nada de domínio. Ele não conhece treino, sessão, XP ou PR — e não existe
 * tabela desses no servidor para conhecer.
 */
@Injectable()
export class AiCoachService {
  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(AI_PROVIDER_GATEWAY) private readonly provider: AiProviderGateway,
    private readonly usage: AiUsageRepository,
    private readonly registry: AiRequestRegistry,
    private readonly logger: SparkLogger,
  ) {}

  async handle(
    principal: AuthenticatedPrincipal,
    requestId: string,
    body: unknown,
  ): Promise<AiCoachHttpResponse> {
    // Interruptor de custo (T16.8 §120): antes do parse, antes da concorrência, antes da quota e
    // muito antes do provider. Desligar o Coach num servidor no ar não pode custar nada — e não
    // toca backup nem sync, que são o que protege dado do usuário.
    //
    // O código é o mesmo `AI_PROVIDER_UNAVAILABLE` de sempre, de propósito: o Android já o trata
    // como `AiCoachErrorKind.UNAVAILABLE` desde a T16.2, então desligar o Coach no servidor não
    // exige publicar um APK novo para o app entender a resposta.
    if (!this.config.aiEnabled) {
      this.logger.warn('ai.disabled', { requestId });
      throw AiCoachErrors.providerUnavailable();
    }

    const startedAt = Date.now();
    const request = this.parseRequest(body);
    const { clientRequestId, requestType } = request;
    const uid = principal.uid;

    const slot = this.registry.tryAcquire(uid, clientRequestId);
    if (!slot.acquired) {
      // Toque duplo ou chamada paralela: recusa barata, antes de qualquer custo.
      this.logger.info('ai.request.rejected', {
        requestId,
        clientRequestId,
        uidPrefix: uidPrefix(uid),
        requestType,
        reason: slot.reason,
      });
      throw AiCoachErrors.requestConflict();
    }

    const utcDate = utcDateOf();
    try {
      await this.reserveQuota(uid, utcDate, requestType, requestId, clientRequestId);

      const result = await this.callProvider(request, requestId);
      const validated = this.validate(request, result.text);

      if (result.usage) {
        await this.usage.recordTokens(uid, utcDate, requestType, result.usage);
      }

      this.logger.info('ai.request.finished', {
        requestId,
        clientRequestId,
        uidPrefix: uidPrefix(uid),
        requestType,
        status: 'SUCCESS',
        model: result.model,
        promptVersion: AiCoachPromptRegistry.promptVersion,
        schemaVersion: request.schemaVersion,
        durationMs: Date.now() - startedAt,
        promptTokens: result.usage?.promptTokens,
        outputTokens: result.usage?.outputTokens,
        totalTokens: result.usage?.totalTokens,
      });

      return {
        requestId,
        clientRequestId,
        schemaVersion: AI_SCHEMA_VERSION,
        promptVersion: AiCoachPromptRegistry.promptVersion,
        model: result.model,
        result: validated,
      };
    } finally {
      this.registry.release(uid, clientRequestId);
    }
  }

  /**
   * O contrato de entrada, antes de qualquer coisa custar.
   *
   * A versão de schema é conferida primeiro: um contrato que este servidor não sabe interpretar é
   * recusado explicitamente, e não "interpretado no melhor esforço" (§8).
   */
  private parseRequest(body: unknown): AiCoachRequestBody {
    const declaredVersion =
      typeof body === 'object' && body !== null && 'schemaVersion' in body
        ? (body as { schemaVersion: unknown }).schemaVersion
        : undefined;

    if (typeof declaredVersion === 'number' && !isSupportedSchemaVersion(declaredVersion)) {
      throw AiCoachErrors.unsupportedSchemaVersion(declaredVersion);
    }

    const parsed = aiCoachRequestSchema.safeParse(body);
    if (!parsed.success) {
      const issue = parsed.error.issues[0];
      const message = issue ? `${issue.path.join('.')}: ${issue.message}` : 'requisição inválida';
      throw AiCoachErrors.invalidRequest(message);
    }

    if (!isSupportedSchemaVersion(parsed.data.schemaVersion)) {
      throw AiCoachErrors.unsupportedSchemaVersion(parsed.data.schemaVersion);
    }

    return parsed.data;
  }

  /**
   * Registra a tentativa e confere os tetos — na mesma transação.
   *
   * Incrementar **antes** da chamada é deliberado: uma chamada que falhou no provider pode já ter
   * custado. Se a tentativa não chega ao provider (quota estourada aqui), o incremento é desfeito.
   */
  private async reserveQuota(
    uid: string,
    utcDate: string,
    requestType: AiCoachRequestType,
    requestId: string,
    clientRequestId: string,
  ): Promise<void> {
    const usage = await this.usage.recordAttempt(uid, utcDate, requestType);

    const overUser = usage.userRequests > this.config.aiMaxRequestsPerUserDay;
    const overGlobal = usage.globalRequests > this.config.aiMaxRequestsGlobalDay;
    if (!overUser && !overGlobal) {
      return;
    }

    await this.usage.releaseAttempt(uid, utcDate, requestType);
    this.logger.warn('ai.quota.exceeded', {
      requestId,
      clientRequestId,
      uidPrefix: uidPrefix(uid),
      requestType,
      scope: overGlobal ? 'GLOBAL' : 'USER',
    });
    throw overGlobal ? AiCoachErrors.globalQuotaExceeded() : AiCoachErrors.userQuotaExceeded();
  }

  /** Uma chamada. Sem retry, sem segunda passada, sem provider alternativo. */
  private async callProvider(
    request: AiCoachRequestBody,
    requestId: string,
  ): Promise<AiProviderResult> {
    const entry = AiCoachPromptRegistry.entryFor(request.requestType);
    try {
      return await this.provider.generate({
        requestId,
        systemInstruction: entry.systemInstruction,
        userPrompt: AiCoachPromptRegistry.userPrompt({
          type: request.requestType,
          requestId,
          schemaVersion: request.schemaVersion,
          context: request.context,
        }),
        responseSchema: entry.responseSchema,
      });
    } catch (error) {
      throw this.translateProviderError(error);
    }
  }

  private translateProviderError(error: unknown): Error {
    if (!(error instanceof AiProviderError)) {
      return AiCoachErrors.providerUnavailable();
    }
    switch (error.kind) {
      case 'TIMEOUT':
        return AiCoachErrors.providerTimeout();
      case 'RATE_LIMITED':
        // Limite do provider, não do Spark: para o cliente é a mesma decisão — tente mais tarde.
        return AiCoachErrors.globalQuotaExceeded();
      case 'EMPTY_RESPONSE':
        return AiCoachErrors.invalidResponse();
      default:
        return AiCoachErrors.providerUnavailable();
    }
  }

  /**
   * Parse + validação estrutural + validação semântica.
   *
   * Uma violação invalida a resposta inteira: nada é corrigido por aproximação e nenhum
   * `exerciseId` desconhecido é resolvido por nome. O motivo detalhado fica no log do servidor —
   * a resposta HTTP diz apenas que a validação recusou.
   */
  private validate(request: AiCoachRequestBody, text: string): unknown {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      throw AiCoachErrors.invalidResponse();
    }

    switch (request.requestType) {
      case 'ANALYZE_WORKOUT': {
        const output = rawAnalysisOutputSchema.safeParse(parsed);
        if (!output.success) throw AiCoachErrors.invalidResponse();
        this.assertValid(validateAnalysis(request.context, output.data), request.requestType);
        return output.data;
      }
      case 'GENERATE_WORKOUT': {
        const output = rawGenerationOutputSchema.safeParse(parsed);
        if (!output.success) throw AiCoachErrors.invalidResponse();
        this.assertValid(validateGeneration(request.context, output.data), request.requestType);
        return output.data;
      }
      case 'ADAPT_WORKOUT': {
        const output = rawAdaptationOutputSchema.safeParse(parsed);
        if (!output.success) throw AiCoachErrors.invalidResponse();
        this.assertValid(validateAdaptation(request.context, output.data), request.requestType);
        return output.data;
      }
      default: {
        const output = rawExplanationOutputSchema.safeParse(parsed);
        if (!output.success) throw AiCoachErrors.invalidResponse();
        this.assertValid(validateExplanation(request.context, output.data), request.requestType);
        return output.data;
      }
    }
  }

  private assertValid(validation: AiCoachValidation, requestType: AiCoachRequestType): void {
    if (validation.ok) return;
    // A razão é técnica (campo + regra violada) e nunca o texto do modelo. Ainda assim ela pode
    // conter um fragmento escrito pelo modelo — o `exerciseId` inventado, por exemplo, que é
    // justamente o que torna a rejeição diagnosticável. Por isso passa por `safeReason`: uma
    // linha, sem caractere de controle, com tamanho fechado.
    this.logger.warn('ai.response.rejected', {
      requestType,
      reason: safeReason(validation.reason),
    });
    throw AiCoachErrors.invalidResponse();
  }
}

/** Razão de rejeição pronta para log: uma linha, sem caractere de controle, com teto. */
function safeReason(reason: string): string {
  // Caractere de controle é exatamente o alvo: quebra de linha em log estruturado vira evento
  // falso, e é isso que se remove aqui.
  // eslint-disable-next-line no-control-regex
  const flattened = reason.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim();
  return flattened.length > MAX_LOGGED_REASON_LENGTH
    ? `${flattened.slice(0, MAX_LOGGED_REASON_LENGTH)}\u2026`
    : flattened;
}

const MAX_LOGGED_REASON_LENGTH = 160;
