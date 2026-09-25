import { randomUUID } from 'node:crypto';
import { coachProviderRequest } from '../ai-coach.provider-request';
import {
  validateCoachOutput,
  type CoachOutputRejectionStage,
} from '../ai-coach.response-validation';
import {
  AiProviderError,
  type AiProviderFailureKind,
  type AiProviderGateway,
  type AiProviderLimit,
  type AiProviderRequest,
  type AiProviderUsage,
} from '../provider/ai-provider.gateway';
import type { CoachEvalScenario } from './coach-eval.dataset';
import {
  criticalViolations,
  expectationMet,
  type CriticalInvariant,
} from './coach-eval.invariants';

/**
 * O executor do benchmark do Coach (T19.H4 §30/§31).
 *
 * Para cada cenário, o caminho de produção inteiro menos o HTTP:
 *
 * ```text
 * contexto (já validado pelo contrato do endpoint)
 *   → coachProviderRequest (o mesmo prompt/schema do AiCoachService)
 *   → UMA chamada ao gateway (o mesmo que a produção monta)
 *   → validateCoachOutput (o mesmo JSON → zod → validador semântico)
 *   → oráculo das invariantes críticas, sobre o que foi aceito
 * ```
 *
 * Um limite **diário** do provider encerra a execução — cada chamada seguinte seria outro 429. Um
 * limite **de minuto** (RPM/TPM) é cadência, não qualidade do modelo: com `minuteLimitRetries`, o
 * executor espera o `retry-after` e repete o mesmo cenário, e conta a repetição à parte
 * (`pacingRetries`) em vez de lançá-la como erro do provider. Isso vale só para as ferramentas de
 * avaliação — o produto continua com uma chamada por intenção e nenhum retry.
 *
 * O resultado de cada cenário é só metadata (status, etapa, regra, latência, tokens). O texto do
 * modelo só sai daqui pelo `onResponse`, que o CLI grava apenas em `backend/.private/`.
 */

export type ScenarioStatus = 'ACCEPTED' | 'REJECTED' | 'PROVIDER_ERROR';

export interface ScenarioOutcome {
  readonly scenarioId: string;
  readonly requestType: string;
  readonly tags: readonly string[];
  readonly status: ScenarioStatus;
  readonly rejectionStage?: CoachOutputRejectionStage;
  /** A regra que recusou, sem os trechos citados do modelo (`'…'`). */
  readonly rejectionRule?: string;
  readonly failureKind?: AiProviderFailureKind;
  readonly providerStatus?: number;
  readonly limit?: AiProviderLimit;
  /** O provider disse que esta requisição sozinha passa do limite — repetir não adianta. */
  readonly requestTooLarge?: boolean;
  readonly finishReason?: string;
  readonly latencyMs: number;
  readonly usage?: AiProviderUsage;
  /** Só em respostas aceitas: o que o oráculo encontrou. Qualquer item desclassifica. */
  readonly criticalViolations: readonly CriticalInvariant[];
  /** Aderência ao pedido, quando o cenário declara expectativa. */
  readonly expectationMet?: boolean;
  /** O modelo que efetivamente respondeu. */
  readonly model?: string;
  /** Quantos 429 de minuto (RPM/TPM) foram absorvidos esperando o `retry-after` neste cenário. */
  readonly pacingRetries?: number;
}

export interface CoachEvalRun {
  readonly outcomes: readonly ScenarioOutcome[];
  /** Por que a execução parou antes do fim, quando parou. */
  readonly abortedReason?: string;
}

export interface CoachEvalRunOptions {
  readonly gateway: AiProviderGateway;
  readonly scenarios: readonly CoachEvalScenario[];
  readonly pacer?: RatePacer;
  readonly onOutcome?: (outcome: ScenarioOutcome, index: number, total: number) => void;
  /** O texto cru do modelo — só para o CLI gravar em `.private/`. Nunca entra no relatório. */
  readonly onResponse?: (
    scenario: CoachEvalScenario,
    request: AiProviderRequest,
    text: string | undefined,
    outcome: ScenarioOutcome,
  ) => Promise<void> | void;
  readonly now?: () => number;
  /** Repetições por cenário para 429 de minuto (RPM/TPM). Default 0: registra e segue. */
  readonly minuteLimitRetries?: number;
  /** Saída estimada por chamada na cadência, até existir a média real observada. */
  readonly assumedOutputTokens?: number;
  readonly sleep?: (ms: number) => Promise<void>;
}

export async function runCoachEval(options: CoachEvalRunOptions): Promise<CoachEvalRun> {
  const now = options.now ?? (() => performance.now());
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const maxRetries = options.minuteLimitRetries ?? 0;
  const outcomes: ScenarioOutcome[] = [];
  const observedOutputs: number[] = [];

  for (const [index, scenario] of options.scenarios.entries()) {
    const request = coachProviderRequest(
      scenario.request,
      `bench-${scenario.id}-${randomUUID().slice(0, 8)}`,
    );

    let pacingRetries = 0;
    let attempt: { outcome: ScenarioOutcome; text?: string; retryAfterSeconds?: number };
    for (;;) {
      // A cadência reserva entrada **e** saída: o TPM do plano conta as duas.
      const expectedOutput =
        observedOutputs.length > 0
          ? observedOutputs.reduce((sum, value) => sum + value, 0) / observedOutputs.length
          : (options.assumedOutputTokens ?? 1000);
      const ticket = await options.pacer?.acquire(
        estimatePromptTokens(request) + Math.round(expectedOutput),
      );
      attempt = await callOnce(options.gateway, scenario, request, now);
      // Só um limite **diário declarado** (RPD/TPD) não se resolve esperando. Um 429 sem limite
      // reconhecível é tratado como de minuto: no benchmark da T19.H4 o Qwen devolveu 429 sem a
      // sigla nem `retry-after`, no ritmo exato do TPM do plano.
      const minuteLimited =
        attempt.outcome.failureKind === 'RATE_LIMITED' &&
        attempt.outcome.limit !== 'RPD' &&
        attempt.outcome.limit !== 'TPD' &&
        attempt.outcome.requestTooLarge !== true;
      // Um 429 não consumiu tokens; uma resposta, sim (inclusive truncada ou recusada).
      ticket?.settle(minuteLimited ? 0 : attempt.outcome.usage?.totalTokens);
      if (minuteLimited && pacingRetries < maxRetries) {
        pacingRetries += 1;
        // Sem `retry-after` (o 429 de TPM do Qwen não traz), meia janela: tempo para o balde de
        // tokens do plano reabastecer uma chamada grande — 10 s não bastava, e o cenário esgotava
        // as repetições.
        await sleep(((attempt.retryAfterSeconds ?? MINUTE_LIMIT_DEFAULT_WAIT_SECONDS) + 1) * 1000);
        continue;
      }
      break;
    }
    const outcome: ScenarioOutcome = { ...attempt.outcome, pacingRetries };
    const text = attempt.text;
    if (outcome.usage) observedOutputs.push(outcome.usage.outputTokens);

    outcomes.push(outcome);
    options.onOutcome?.(outcome, index, options.scenarios.length);
    await options.onResponse?.(scenario, request, text, outcome);

    if (
      outcome.failureKind === 'RATE_LIMITED' &&
      (outcome.limit === 'RPD' || outcome.limit === 'TPD')
    ) {
      return {
        outcomes,
        abortedReason: `limite diário do provider (${outcome.limit}) atingido no cenário ${scenario.id}`,
      };
    }
  }

  return { outcomes };
}

async function callOnce(
  gateway: AiProviderGateway,
  scenario: CoachEvalScenario,
  request: AiProviderRequest,
  now: () => number,
): Promise<{ outcome: ScenarioOutcome; text?: string; retryAfterSeconds?: number }> {
  const started = now();
  try {
    const result = await gateway.generate(request);
    return {
      outcome: judge(scenario, result.text, now() - started, result.usage, result.model),
      text: result.text,
    };
  } catch (error) {
    const failure = error instanceof AiProviderError ? error : undefined;
    return {
      outcome: {
        scenarioId: scenario.id,
        requestType: scenario.requestType,
        tags: scenario.tags,
        status: 'PROVIDER_ERROR',
        failureKind: failure?.kind ?? 'UNAVAILABLE',
        providerStatus: failure?.detail.status,
        limit: failure?.detail.limit,
        requestTooLarge: failure?.detail.requestTooLarge,
        finishReason: failure?.detail.finishReason,
        latencyMs: now() - started,
        usage: failure?.detail.usage,
        criticalViolations: [],
      },
      retryAfterSeconds: failure?.detail.retryAfterSeconds,
    };
  }
}

function judge(
  scenario: CoachEvalScenario,
  text: string,
  latencyMs: number,
  usage: AiProviderUsage | undefined,
  model: string,
): ScenarioOutcome {
  const base = {
    scenarioId: scenario.id,
    requestType: scenario.requestType,
    tags: scenario.tags,
    latencyMs,
    usage,
    model,
  };
  const verdict = validateCoachOutput(scenario.request, text);
  if (!verdict.ok) {
    return {
      ...base,
      status: 'REJECTED',
      rejectionStage: verdict.stage,
      rejectionRule: redactQuoted(verdict.reason),
      criticalViolations: [],
    };
  }
  return {
    ...base,
    status: 'ACCEPTED',
    criticalViolations: criticalViolations(scenario.request, verdict.result),
    expectationMet: expectationMet(scenario.expect, verdict.result),
  };
}

/** A razão do validador sem os trechos que o modelo escreveu (ids citados entre aspas). */
export function redactQuoted(reason: string): string {
  return reason.replace(/'[^']*'/g, "'…'").slice(0, 160);
}

/**
 * Estimativa grosseira de tokens de entrada — só para cadência e para o `--dry-run`.
 *
 * ~3,2 caracteres por token é conservador para o que o Coach manda (português + JSON com ids
 * `canonical:*`, que tokenizam mal): superestima, e é o lado certo para errar ao planejar cota.
 * O número real vem do provider, e é ele que o relatório usa.
 */
export function estimatePromptTokens(request: AiProviderRequest): number {
  const chars =
    request.systemInstruction.length +
    request.userPrompt.length +
    JSON.stringify(request.responseSchema).length;
  return Math.ceil(chars / 3.2);
}

/**
 * Cadência por janela deslizante de 60 s: no máximo `rpm` chamadas e, com `tpm`, no máximo `tpm`
 * tokens (estimados na partida, corrigidos pelo real na chegada). A Groq free tier dá 8 000 TPM —
 * duas ou três chamadas do Coach por minuto —, e o benchmark precisa caber nisso sem virar uma
 * coleção de 429.
 */
export class RatePacer {
  private readonly window: Array<{ at: number; tokens: number }> = [];

  constructor(
    private readonly budget: { readonly rpm: number; readonly tpm?: number },
    private readonly clock: {
      now(): number;
      sleep(ms: number): Promise<void>;
    } = {
      now: () => Date.now(),
      sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    },
  ) {}

  async acquire(estimatedTokens: number): Promise<{ settle(actualTokens?: number): void }> {
    for (;;) {
      const now = this.clock.now();
      while (this.window.length > 0 && now - this.window[0].at >= WINDOW_MS) {
        this.window.shift();
      }
      const used = this.window.reduce((total, entry) => total + entry.tokens, 0);
      const fitsRequests = this.window.length < this.budget.rpm;
      const fitsTokens = this.budget.tpm === undefined || used + estimatedTokens <= this.budget.tpm;
      // Janela vazia sempre libera: uma chamada maior que o orçamento inteiro esperaria para
      // sempre — e o 413/429 que ela receber é justamente o dado que o benchmark precisa mostrar.
      if ((fitsRequests && fitsTokens) || this.window.length === 0) {
        const entry = { at: now, tokens: estimatedTokens };
        this.window.push(entry);
        return {
          settle: (actualTokens?: number) => {
            if (actualTokens !== undefined) entry.tokens = actualTokens;
          },
        };
      }
      const waitMs = WINDOW_MS - (now - this.window[0].at) + 50;
      await this.clock.sleep(Math.max(waitMs, 50));
    }
  }
}

const WINDOW_MS = 60_000;

/** Espera para um 429 de minuto que não declarou `retry-after`. */
const MINUTE_LIMIT_DEFAULT_WAIT_SECONDS = 30;
