import { z } from 'zod';
import type { CriticalInvariant } from './coach-eval.invariants';
import type { CoachEvalRun, ScenarioOutcome } from './coach-eval.runner';
import { distribution, formatNumber, formatPercent, type Distribution } from './stats';

/**
 * O relatório do benchmark — métricas, desclassificação e capacidade (T19.H4 §36–§44).
 *
 * Só metadata: provider, modelo, id de cenário, status, etapa/regra de recusa, latência, tokens e
 * violações. Nenhum prompt e nenhuma resposta — é isso que torna o relatório commitável (§36).
 */

/** Os limites de um provider/modelo, com a data e a fonte da verificação (§8). */
export const providerLimitsSchema = z
  .object({
    tier: z.string().min(1),
    rpm: z.number().int().positive().optional(),
    rpd: z.number().int().positive().optional(),
    tpm: z.number().int().positive().optional(),
    tpd: z.number().int().positive().optional(),
    /** Só entrada (Gemini free tier mede TPM de entrada). */
    inputTpm: z.number().int().positive().optional(),
    /** Só saída — o Qwen 3.8 tem no free tier da Groq (1 000/min), fora da tabela pública. */
    otpm: z.number().int().positive().optional(),
    lifecycle: z.enum(['PRODUCTION', 'PREVIEW', 'GA', 'UNKNOWN']).default('UNKNOWN'),
    verifiedAt: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
    source: z.string().min(1),
  })
  .strict();

export type ProviderLimits = z.infer<typeof providerLimitsSchema>;

/** `ai-eval/provider-limits.json`: `{ "<provider>:<modelo>": ProviderLimits }`. */
export const providerLimitsFileSchema = z.record(z.string(), providerLimitsSchema);

export type RequestFamily = 'ANALYZE' | 'GENERATE' | 'ADAPT' | 'EXPLAIN';

export function familyOf(requestType: string): RequestFamily {
  if (requestType === 'ANALYZE_WORKOUT') return 'ANALYZE';
  if (requestType === 'GENERATE_WORKOUT') return 'GENERATE';
  if (requestType === 'ADAPT_WORKOUT') return 'ADAPT';
  return 'EXPLAIN';
}

export interface OutcomeMetrics {
  readonly requests: number;
  readonly providerErrors: number;
  readonly providerErrorsByKind: Readonly<Record<string, number>>;
  readonly responses: number;
  readonly rejectedJson: number;
  readonly rejectedStructure: number;
  readonly rejectedSemantic: number;
  readonly accepted: number;
  readonly latencyMs?: Distribution;
  readonly promptTokens?: Distribution;
  readonly outputTokens?: Distribution;
  readonly totalTokens?: Distribution;
  readonly expectationsEvaluated: number;
  readonly expectationsMet: number;
  /** 429 de minuto (RPM/TPM) absorvidos pela cadência — capacidade, não qualidade. */
  readonly pacingRetries: number;
}

export interface CapacityEstimate {
  readonly limits: ProviderLimits;
  readonly p95TotalPerCall: number;
  readonly maxTotalPerCall: number;
  /** TPD / p95 — o teto por tokens, sem margem. */
  readonly tpdCeiling?: number;
  /** 80% do TPD / p95 — a estimativa conservadora (§7). */
  readonly safeByTokens?: number;
  readonly rpdCeiling?: number;
  /** min(safeByTokens, RPD): quantas chamadas por dia cabem com margem. */
  readonly safeDaily?: number;
  /** Uma chamada típica/p95/máxima cabe no TPM do plano? (§9) */
  readonly p95FitsTpm?: boolean;
  readonly maxFitsTpm?: boolean;
  /** Quantas chamadas p95 cabem num minuto de TPM. */
  readonly callsPerMinuteAtP95?: number;
  readonly sparkGlobalQuota: number;
  readonly peakDailyDemand?: number;
  readonly verdict: 'SUFFICIENT' | 'MARGINAL' | 'INSUFFICIENT' | 'UNKNOWN';
}

export interface EvalSummary {
  readonly provider: string;
  readonly model: string;
  readonly answeredBy: readonly string[];
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly datasetFingerprint: string;
  readonly settings: Readonly<Record<string, string | number>>;
  readonly overall: OutcomeMetrics;
  readonly byFamily: Readonly<Record<RequestFamily, OutcomeMetrics>>;
  readonly criticalViolations: ReadonlyArray<{ scenarioId: string; invariant: CriticalInvariant }>;
  readonly verdict: 'ELIGIBLE' | 'DISQUALIFIED';
  readonly abortedReason?: string;
  readonly capacity?: CapacityEstimate;
  readonly outcomes: readonly ScenarioOutcome[];
}

export function metricsOf(outcomes: readonly ScenarioOutcome[]): OutcomeMetrics {
  const providerErrorsByKind: Record<string, number> = {};
  for (const outcome of outcomes) {
    if (outcome.status === 'PROVIDER_ERROR') {
      const key =
        outcome.failureKind +
        (outcome.providerStatus !== undefined ? `/${outcome.providerStatus}` : '') +
        (outcome.limit ? `/${outcome.limit}` : '') +
        (outcome.requestTooLarge ? '/REQUEST_TOO_LARGE' : '') +
        (outcome.finishReason ? `/${outcome.finishReason}` : '');
      providerErrorsByKind[key] = (providerErrorsByKind[key] ?? 0) + 1;
    }
  }
  const withUsage = outcomes.filter((outcome) => outcome.usage);
  const evaluated = outcomes.filter((outcome) => outcome.expectationMet !== undefined);
  const count = (predicate: (outcome: ScenarioOutcome) => boolean) =>
    outcomes.filter(predicate).length;
  const providerErrors = count((outcome) => outcome.status === 'PROVIDER_ERROR');
  return {
    requests: outcomes.length,
    providerErrors,
    providerErrorsByKind,
    responses: outcomes.length - providerErrors,
    rejectedJson: count((outcome) => outcome.rejectionStage === 'JSON'),
    rejectedStructure: count((outcome) => outcome.rejectionStage === 'STRUCTURE'),
    rejectedSemantic: count((outcome) => outcome.rejectionStage === 'SEMANTIC'),
    accepted: count((outcome) => outcome.status === 'ACCEPTED'),
    latencyMs: distribution(
      outcomes
        .filter((outcome) => outcome.status !== 'PROVIDER_ERROR')
        .map((outcome) => outcome.latencyMs),
    ),
    promptTokens: distribution(withUsage.map((outcome) => outcome.usage!.promptTokens)),
    outputTokens: distribution(withUsage.map((outcome) => outcome.usage!.outputTokens)),
    totalTokens: distribution(withUsage.map((outcome) => outcome.usage!.totalTokens)),
    expectationsEvaluated: evaluated.length,
    expectationsMet: evaluated.filter((outcome) => outcome.expectationMet === true).length,
    pacingRetries: outcomes.reduce((sum, outcome) => sum + (outcome.pacingRetries ?? 0), 0),
  };
}

/** Respostas estruturalmente válidas (JSON + forma do contrato), entre as que chegaram. */
export function schemaValid(metrics: OutcomeMetrics): number {
  return metrics.responses - metrics.rejectedJson - metrics.rejectedStructure;
}

export function summarize(input: {
  readonly provider: string;
  readonly model: string;
  readonly run: CoachEvalRun;
  readonly startedAt: Date;
  readonly finishedAt: Date;
  readonly datasetFingerprint: string;
  readonly settings: Readonly<Record<string, string | number>>;
  readonly limits?: ProviderLimits;
  readonly sparkGlobalQuota: number;
  readonly peakDailyDemand?: number;
}): EvalSummary {
  const outcomes = input.run.outcomes;
  const families: RequestFamily[] = ['ANALYZE', 'GENERATE', 'ADAPT', 'EXPLAIN'];
  const byFamily = Object.fromEntries(
    families.map((family) => [
      family,
      metricsOf(outcomes.filter((outcome) => familyOf(outcome.requestType) === family)),
    ]),
  ) as Record<RequestFamily, OutcomeMetrics>;
  const criticalViolations = outcomes.flatMap((outcome) =>
    outcome.criticalViolations.map((invariant) => ({ scenarioId: outcome.scenarioId, invariant })),
  );
  const overall = metricsOf(outcomes);
  return {
    provider: input.provider,
    model: input.model,
    answeredBy: [...new Set(outcomes.map((outcome) => outcome.model).filter(isString))],
    startedAt: input.startedAt.toISOString(),
    finishedAt: input.finishedAt.toISOString(),
    datasetFingerprint: input.datasetFingerprint,
    settings: input.settings,
    overall,
    byFamily,
    criticalViolations,
    verdict: criticalViolations.length > 0 ? 'DISQUALIFIED' : 'ELIGIBLE',
    abortedReason: input.run.abortedReason,
    capacity: input.limits
      ? capacityOf(overall, input.limits, input.sparkGlobalQuota, input.peakDailyDemand)
      : undefined,
    outcomes,
  };
}

export function capacityOf(
  metrics: OutcomeMetrics,
  limits: ProviderLimits,
  sparkGlobalQuota: number,
  peakDailyDemand?: number,
): CapacityEstimate | undefined {
  const total = metrics.totalTokens;
  if (!total) return undefined;
  const p95 = total.p95;
  const tpdCeiling = limits.tpd !== undefined ? Math.floor(limits.tpd / p95) : undefined;
  const safeByTokens = limits.tpd !== undefined ? Math.floor((limits.tpd * 0.8) / p95) : undefined;
  const candidates = [safeByTokens, limits.rpd].filter((value): value is number =>
    Number.isFinite(value),
  );
  const safeDaily = candidates.length > 0 ? Math.min(...candidates) : undefined;
  const p95FitsTpm = limits.tpm !== undefined ? p95 <= limits.tpm : undefined;
  const maxFitsTpm = limits.tpm !== undefined ? total.max <= limits.tpm : undefined;

  let verdict: CapacityEstimate['verdict'] = 'UNKNOWN';
  if (safeDaily !== undefined) {
    if (maxFitsTpm === false || (peakDailyDemand !== undefined && safeDaily < peakDailyDemand)) {
      verdict = 'INSUFFICIENT';
    } else if (safeDaily >= sparkGlobalQuota) {
      verdict = 'SUFFICIENT';
    } else {
      // A capacidade cobre a demanda real, mas não a quota global do Spark: com a quota como
      // está, os usuários bateriam no limite do provider antes da proteção interna (§45).
      verdict = 'MARGINAL';
    }
  }

  return {
    limits,
    p95TotalPerCall: p95,
    maxTotalPerCall: total.max,
    tpdCeiling,
    safeByTokens,
    rpdCeiling: limits.rpd,
    safeDaily,
    p95FitsTpm,
    maxFitsTpm,
    callsPerMinuteAtP95: limits.tpm !== undefined ? Math.floor(limits.tpm / p95) : undefined,
    sparkGlobalQuota,
    peakDailyDemand,
    verdict,
  };
}

export function renderSummary(summary: EvalSummary): string {
  const o = summary.overall;
  const lines: string[] = [];
  lines.push(`# Benchmark do Coach — ${summary.provider} / ${summary.model}`);
  lines.push('');
  lines.push(
    `- Execução: ${summary.startedAt} → ${summary.finishedAt}; dataset \`${summary.datasetFingerprint}\`.`,
  );
  lines.push(`- Respondido por: ${summary.answeredBy.join(', ') || '—'}.`);
  lines.push(
    `- Parâmetros: ${Object.entries(summary.settings)
      .map(([key, value]) => `${key}=${value}`)
      .join(', ')}.`,
  );
  if (summary.abortedReason) lines.push(`- **Interrompido:** ${summary.abortedReason}.`);
  lines.push(
    `- **Veredito:** ${summary.verdict}` +
      (summary.criticalViolations.length > 0
        ? ` (${summary.criticalViolations.length} violação(ões) crítica(s) aceitas pelo validador)`
        : ' (nenhuma violação crítica aceita)'),
  );
  lines.push('');
  lines.push(
    '| Família | Req. | Erros provider | Estrutural válido | Semântico válido | Fim a fim | p50 ms | p95 ms | Tokens méd. | Tokens p95 | Aderência |',
  );
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |');
  const row = (label: string, m: OutcomeMetrics) =>
    `| ${label} | ${m.requests} | ${m.providerErrors} | ${formatPercent(schemaValid(m), m.responses)} | ${formatPercent(m.accepted, m.responses)} | ${formatPercent(m.accepted, m.requests)} | ${formatNumber(m.latencyMs?.p50)} | ${formatNumber(m.latencyMs?.p95)} | ${formatNumber(m.totalTokens?.avg)} | ${formatNumber(m.totalTokens?.p95)} | ${m.expectationsEvaluated === 0 ? '—' : `${m.expectationsMet}/${m.expectationsEvaluated}`} |`;
  for (const [family, metrics] of Object.entries(summary.byFamily)) {
    if (metrics.requests > 0) lines.push(row(family, metrics));
  }
  lines.push(row('**Total**', o));
  lines.push('');
  lines.push(
    `Tokens por chamada — entrada: méd. ${formatNumber(o.promptTokens?.avg)}, p95 ${formatNumber(o.promptTokens?.p95)}; saída (inclui raciocínio): méd. ${formatNumber(o.outputTokens?.avg)}, p95 ${formatNumber(o.outputTokens?.p95)}; total: méd. ${formatNumber(o.totalTokens?.avg)}, p95 ${formatNumber(o.totalTokens?.p95)}, máx. ${formatNumber(o.totalTokens?.max)}.`,
  );
  const errors = Object.entries(o.providerErrorsByKind);
  lines.push(
    `Erros do provider: ${errors.length === 0 ? 'nenhum' : errors.map(([kind, n]) => `${kind} ×${n}`).join(', ')}.`,
  );
  lines.push(
    `Recusas do validador: JSON ${o.rejectedJson}, estrutura ${o.rejectedStructure}, semântica ${o.rejectedSemantic}.`,
  );
  lines.push(
    `429 de minuto (RPM/TPM) absorvidos pela cadência, esperando o retry-after: ${o.pacingRetries}.`,
  );
  if (summary.criticalViolations.length > 0) {
    lines.push('');
    lines.push('## Violações críticas aceitas');
    for (const violation of summary.criticalViolations) {
      lines.push(`- \`${violation.scenarioId}\`: ${violation.invariant}`);
    }
  }
  if (summary.capacity) {
    const c = summary.capacity;
    lines.push('');
    lines.push('## Capacidade estimada');
    lines.push(
      `- Limites (${c.limits.tier}, ${c.limits.lifecycle}; verificado em ${c.limits.verifiedAt}, ${c.limits.source}): RPM ${c.limits.rpm ?? '—'}, RPD ${c.limits.rpd ?? '—'}, TPM ${c.limits.tpm ?? '—'}, TPD ${c.limits.tpd ?? '—'}${c.limits.otpm !== undefined ? `, OTPM ${c.limits.otpm}` : ''}.`,
    );
    lines.push(
      `- Por chamada: p95 ${formatNumber(c.p95TotalPerCall)} tokens, máx. ${formatNumber(c.maxTotalPerCall)}. Cabe no TPM: p95 ${yesNo(c.p95FitsTpm)}, máx. ${yesNo(c.maxFitsTpm)}; ${c.callsPerMinuteAtP95 ?? '—'} chamadas p95 por minuto.`,
    );
    lines.push(
      `- Diário: teto por TPD ${c.tpdCeiling ?? '—'}, seguro (80% TPD) ${c.safeByTokens ?? '—'}, RPD ${c.rpdCeiling ?? '—'} → **${c.safeDaily ?? '—'} chamadas/dia**.`,
    );
    lines.push(
      `- Quota global do Spark: ${c.sparkGlobalQuota}` +
        (c.peakDailyDemand !== undefined ? `; pico real observado: ${c.peakDailyDemand}/dia` : '') +
        `. Resultado: **${c.verdict}**.`,
    );
  }
  lines.push('');
  lines.push('## Cenários');
  lines.push('');
  lines.push(
    '| Cenário | Tipo | Status | Etapa / regra | ms | Entrada | Saída | Total | Violações | Aderência |',
  );
  lines.push('| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |');
  for (const outcome of summary.outcomes) {
    const detail =
      outcome.status === 'PROVIDER_ERROR'
        ? [outcome.failureKind, outcome.providerStatus, outcome.limit, outcome.finishReason]
            .filter((value) => value !== undefined)
            .join(' ')
        : outcome.status === 'REJECTED'
          ? `${outcome.rejectionStage}: ${escapePipes(outcome.rejectionRule ?? '')}`
          : '—';
    lines.push(
      `| ${outcome.scenarioId} | ${familyOf(outcome.requestType)} | ${outcome.status} | ${detail} | ${formatNumber(outcome.latencyMs)} | ${formatNumber(outcome.usage?.promptTokens)} | ${formatNumber(outcome.usage?.outputTokens)} | ${formatNumber(outcome.usage?.totalTokens)} | ${outcome.criticalViolations.join(', ') || '—'} | ${outcome.expectationMet === undefined ? '—' : outcome.expectationMet ? 'sim' : 'não'} |`,
    );
  }
  return lines.join('\n');
}

function yesNo(value: boolean | undefined): string {
  return value === undefined ? '—' : value ? 'sim' : 'NÃO';
}

function escapePipes(value: string): string {
  return value.replace(/\|/g, '\\|');
}

function isString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}
