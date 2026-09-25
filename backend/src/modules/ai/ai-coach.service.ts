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
import { AiCoachPromptRegistry } from './ai-coach-prompt.registry';
import { coachProviderRequest } from './ai-coach.provider-request';
import { aiCoachRequestSchema, type AiCoachRequestBody } from './ai-coach.request.schema';
import { validateCoachOutput } from './ai-coach.response-validation';
import { AiRequestRegistry } from './ai-request.registry';
import { AiUsageRepository, utcDateOf } from './ai-usage.repository';
import { capabilityFor } from './entitlement/ai-capability';
import { AiEntitlementResolver } from './entitlement/ai-entitlement.resolver';
import {
  AI_PROVIDER_GATEWAY,
  AiProviderError,
  type AiProviderGateway,
  type AiProviderResult,
  type AiProviderUsage,
} from './provider/ai-provider.gateway';

/**
 * A orquestração do Coach no servidor — e o único lugar que decide se o provider será chamado.
 *
 * Qual provider (Gemini ou Groq) é assunto de `ai-provider.factory.ts`: este serviço não sabe, não
 * pergunta e não se comporta diferente por causa disso (T19.H4 §16). Quota, entitlement,
 * concorrência, prompt, schema e validação são os mesmos para qualquer um.
 *
 * A ordem existe por causa de custo, e não é cosmética:
 *
 * ```text
 * token verificado (guard)
 *   → contrato (schemaVersion, corpo, tetos)
 *   → entitlement da conta para a capability desta operação (T19.0)
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
 * O entitlement é verificado **antes** da vaga de concorrência e da quota (T19.0 §6.2/§10): uma
 * capability negada não reserva vaga, não consome quota e não chama o provider. A decisão em si —
 * "esta conta pode usar esta capability?" — não mora aqui; ela vem de `AiEntitlementResolver`, o
 * único lugar do backend que responde essa pergunta.
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
    private readonly entitlements: AiEntitlementResolver,
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

    await this.assertEntitled(uid, requestType, requestId, clientRequestId);

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

      // Um registro por chamada que chegou ao provider, com o mesmo evento para sucesso e falha:
      // é ele que responde "qual provider, qual modelo, quanto custou, quanto demorou e por que
      // falhou" — e nunca "o que o usuário treinou" nem "o que o modelo escreveu" (T19.H4 §27).
      const finished = (fields: Record<string, unknown>, usage?: AiProviderUsage) => ({
        requestId,
        clientRequestId,
        uidPrefix: uidPrefix(uid),
        requestType,
        promptVersion: AiCoachPromptRegistry.promptVersion,
        schemaVersion: request.schemaVersion,
        durationMs: Date.now() - startedAt,
        promptTokens: usage?.promptTokens,
        outputTokens: usage?.outputTokens,
        totalTokens: usage?.totalTokens,
        ...fields,
      });

      let result: AiProviderResult;
      try {
        result = await this.callProvider(request, requestId);
      } catch (error) {
        const failure = error instanceof AiProviderError ? error : undefined;
        // Uma resposta truncada ou recusada pelo provider também consumiu tokens dele.
        if (failure?.detail.usage) {
          await this.recordTokens(uid, utcDate, requestType, failure.detail.usage, requestId);
        }
        this.logger.warn(
          'ai.request.finished',
          finished(
            {
              status: 'PROVIDER_FAILED',
              provider: this.provider.descriptor.provider,
              model: this.provider.descriptor.model,
              failureKind: failure?.kind ?? 'UNAVAILABLE',
              failureReason: failure?.reason,
              providerStatus: failure?.detail.status,
              providerCode: failure?.detail.providerCode,
              finishReason: failure?.detail.finishReason,
              // §29 — "limite estourado" precisa dizer de quem: aqui é sempre o do provider
              // (o do Spark é `ai.quota.exceeded`, com `limitSource: 'SPARK'`).
              limitSource: failure?.kind === 'RATE_LIMITED' ? 'PROVIDER' : undefined,
              limit: failure?.detail.limit,
              requestTooLarge: failure?.detail.requestTooLarge,
              retryAfterSeconds: failure?.detail.retryAfterSeconds,
            },
            failure?.detail.usage,
          ),
        );
        throw this.translateProviderError(error);
      }

      // Antes da validação, de propósito: uma resposta que a validação recusa custou igual, e o
      // consumo medido (`ai:usage-report`) precisa enxergá-la — é justamente o modo de falha que
      // mais gasta sem entregar nada.
      if (result.usage) {
        await this.recordTokens(uid, utcDate, requestType, result.usage, requestId);
      }

      const verdict = validateCoachOutput(request, result.text);
      if (!verdict.ok) {
        // A razão é técnica (campo + regra violada) e nunca o texto do modelo. Ainda assim ela
        // pode conter um fragmento escrito pelo modelo — o `exerciseId` inventado, por exemplo,
        // que é justamente o que torna a rejeição diagnosticável. Por isso passa por `safeReason`:
        // uma linha, sem caractere de controle, com tamanho fechado.
        this.logger.warn('ai.response.rejected', {
          requestId,
          requestType,
          provider: result.provider,
          model: result.model,
          stage: verdict.stage,
          reason: safeReason(verdict.reason),
        });
        this.logger.warn(
          'ai.request.finished',
          finished(
            {
              status: 'INVALID_RESPONSE',
              provider: result.provider,
              model: result.model,
              rejectionStage: verdict.stage,
            },
            result.usage,
          ),
        );
        throw AiCoachErrors.invalidResponse();
      }

      this.logger.info(
        'ai.request.finished',
        finished(
          { status: 'SUCCESS', provider: result.provider, model: result.model },
          result.usage,
        ),
      );

      return {
        requestId,
        clientRequestId,
        schemaVersion: AI_SCHEMA_VERSION,
        promptVersion: AiCoachPromptRegistry.promptVersion,
        model: result.model,
        result: verdict.result,
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
   * A capability desta operação, autorizada para esta conta — antes de qualquer custo (T19.0).
   *
   * `RESOLUTION_FAILED` vira `503`, nunca `403`: o servidor não decidiu negar, não conseguiu
   * decidir. As duas recusam a chamada — é só isso que fail-closed exige —, mas a distinção
   * importa para quem lê o log e para quem opera o servidor (§24).
   */
  private async assertEntitled(
    uid: string,
    requestType: AiCoachRequestType,
    requestId: string,
    clientRequestId: string,
  ): Promise<void> {
    const capability = capabilityFor(requestType);
    const decision = await this.entitlements.resolve(uid, capability);
    if (decision.allowed) {
      return;
    }

    this.logger.warn('ai.entitlement.denied', {
      requestId,
      clientRequestId,
      uidPrefix: uidPrefix(uid),
      requestType,
      capability,
      reason: decision.reason,
    });

    throw decision.reason === 'RESOLUTION_FAILED'
      ? AiCoachErrors.entitlementUnavailable()
      : AiCoachErrors.capabilityDenied();
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
      // §29 — o limite é do Spark, não do provider (esse sai em `ai.request.finished` com
      // `limitSource: 'PROVIDER'`). Para o app os dois são 429; para quem opera, não.
      limitSource: 'SPARK',
    });
    throw overGlobal ? AiCoachErrors.globalQuotaExceeded() : AiCoachErrors.userQuotaExceeded();
  }

  /**
   * Uma chamada. Sem retry, sem segunda passada, sem provider alternativo.
   *
   * O erro do provider sobe **cru** (`AiProviderError`): quem chama registra os tokens que ele
   * consumiu e o loga com a metadata dele antes de traduzi-lo para o contrato HTTP.
   */
  private callProvider(request: AiCoachRequestBody, requestId: string): Promise<AiProviderResult> {
    return this.provider.generate(coachProviderRequest(request, requestId));
  }

  /**
   * Tokens são metadata de custo, gravados depois que o provider já respondeu — best-effort de
   * propósito. Uma falha de banco aqui não pode trocar o erro real do provider por um 500, nem
   * jogar fora uma resposta válida que o usuário já pagou para receber; o que se perde é a contagem
   * desta chamada, e o log diz qual.
   */
  private async recordTokens(
    uid: string,
    utcDate: string,
    requestType: AiCoachRequestType,
    usage: AiProviderUsage,
    requestId: string,
  ): Promise<void> {
    try {
      await this.usage.recordTokens(uid, utcDate, requestType, usage);
    } catch (error) {
      this.logger.error('ai.usage.tokens_not_recorded', {
        requestId,
        requestType,
        totalTokens: usage.totalTokens,
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
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
