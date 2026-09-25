import { isExplanation, type AiCoachRequestType } from '../ai-coach.contract';
import { distribution, formatNumber, type Distribution } from './stats';

/**
 * O consumo real do Coach, a partir do que o Spark **já** registra (T19.H4 §5/§6).
 *
 * Duas fontes, e nenhuma telemetria nova:
 *
 * - `ai_usage_daily` — a tabela da quota. Soma, por conta/dia/tipo, tentativas e tokens. Dá o
 *   volume e a média por tentativa, mas não a distribuição por chamada: a linha é um agregado.
 * - opcionalmente, o export dos eventos `ai.request.finished` do Cloud Logging (`--calls`). Cada
 *   evento é **uma** chamada, com tokens e duração — é daí que saem p95 e máximo por chamada.
 *
 * O relatório não contém uid, prompt, resposta, nome de treino nem de exercício: só tipo de
 * request, contagens, tokens, datas e agregados (§6). A contagem de contas distintas é um número,
 * nunca uma lista.
 *
 * Duas notas de leitura que o relatório repete, porque mudam a conta:
 *
 * - `request_count` conta **tentativas** — inclui as que o provider recusou (429/503) e que, por
 *   isso, não trouxeram tokens. "Tokens por tentativa" é então um piso do custo por chamada.
 * - até a T19.H4, `output_tokens` do Gemini **excluía** o raciocínio do modelo (só `total_tokens`
 *   o incluía). A saída aqui é sempre derivada como `total − prompt`, o mesmo critério nas duas
 *   épocas — e o mesmo que a Groq usa (`completion_tokens` inclui o raciocínio).
 */

export interface UsageAggregateRow {
  readonly requestType: string;
  readonly accounts: number;
  readonly activeDays: number;
  readonly requests: number;
  readonly promptTokens: number;
  readonly outputTokens: number;
  readonly totalTokens: number;
}

export interface UsageDailyRow {
  readonly utcDate: string;
  readonly requests: number;
  readonly totalTokens: number;
}

/** Uma chamada, como o evento `ai.request.finished` a descreve. */
export interface CallSample {
  readonly requestType: string;
  readonly status: string;
  readonly promptTokens: number;
  readonly totalTokens: number;
  readonly durationMs?: number;
}

export interface UsageQueryable {
  query<T extends Record<string, unknown>>(sql: string, params?: unknown[]): Promise<{ rows: T[] }>;
}

/** Os agregados do período — duas consultas de leitura, nenhuma escrita. */
export async function queryUsage(
  db: UsageQueryable,
  sinceUtcDate: string,
): Promise<{ byType: UsageAggregateRow[]; byDay: UsageDailyRow[] }> {
  const byType = await db.query<Record<string, string | number>>(
    `SELECT request_type,
            COUNT(DISTINCT uid)      AS accounts,
            COUNT(DISTINCT utc_date) AS active_days,
            SUM(request_count)       AS requests,
            SUM(prompt_tokens)       AS prompt_tokens,
            SUM(output_tokens)       AS output_tokens,
            SUM(total_tokens)        AS total_tokens
       FROM ai_usage_daily
      WHERE utc_date >= $1
      GROUP BY request_type
      ORDER BY request_type`,
    [sinceUtcDate],
  );
  const byDay = await db.query<Record<string, string | number>>(
    `SELECT utc_date, SUM(request_count) AS requests, SUM(total_tokens) AS total_tokens
       FROM ai_usage_daily
      WHERE utc_date >= $1
      GROUP BY utc_date
      ORDER BY utc_date`,
    [sinceUtcDate],
  );
  return {
    byType: byType.rows.map((row) => ({
      requestType: String(row.request_type),
      accounts: Number(row.accounts),
      activeDays: Number(row.active_days),
      requests: Number(row.requests),
      promptTokens: Number(row.prompt_tokens),
      outputTokens: Number(row.output_tokens),
      totalTokens: Number(row.total_tokens),
    })),
    byDay: byDay.rows.map((row) => ({
      utcDate: String(row.utc_date),
      requests: Number(row.requests),
      totalTokens: Number(row.total_tokens),
    })),
  };
}

/**
 * As chamadas de um export do Cloud Logging (`gcloud logging read … --format=json`): um array de
 * entradas, ou JSON Lines. Só o que interessa sai daqui — tipo, status, tokens e duração —, e só
 * de eventos `ai.request.finished` que trazem tokens. Correlação, prefixo de uid e o resto do
 * evento são descartados na leitura.
 */
export function parseCallSamples(raw: string): CallSample[] {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return [];
  const entries: unknown[] = trimmed.startsWith('[')
    ? (JSON.parse(trimmed) as unknown[])
    : trimmed
        .split('\n')
        .filter((line) => line.trim().length > 0)
        .map((line) => JSON.parse(line));

  const samples: CallSample[] = [];
  for (const entry of entries) {
    const payload =
      typeof entry === 'object' && entry !== null && 'jsonPayload' in entry
        ? (entry as { jsonPayload: unknown }).jsonPayload
        : entry;
    if (typeof payload !== 'object' || payload === null) continue;
    const event = payload as Record<string, unknown>;
    if (event.event !== 'ai.request.finished') continue;
    if (typeof event.totalTokens !== 'number' || typeof event.promptTokens !== 'number') continue;
    if (typeof event.requestType !== 'string') continue;
    samples.push({
      requestType: event.requestType,
      status: typeof event.status === 'string' ? event.status : 'UNKNOWN',
      promptTokens: event.promptTokens,
      totalTokens: event.totalTokens,
      durationMs: typeof event.durationMs === 'number' ? event.durationMs : undefined,
    });
  }
  return samples;
}

/** O agrupamento do relatório: os quatro `EXPLAIN_*` também aparecem somados, como "EXPLAIN_*". */
export const EXPLAIN_GROUP = 'EXPLAIN_*';

export interface UsageTypeReport {
  readonly requestType: string;
  readonly accounts?: number;
  readonly requests: number;
  readonly avgPromptPerRequest?: number;
  readonly avgOutputPerRequest?: number;
  readonly avgTotalPerRequest?: number;
  /** Por chamada, a partir do export de log — `undefined` sem `--calls`. */
  readonly perCall?: {
    readonly prompt: Distribution;
    readonly output: Distribution;
    readonly total: Distribution;
    readonly durationMs?: Distribution;
  };
}

export interface UsageReport {
  readonly sinceUtcDate: string;
  readonly days: number;
  readonly types: readonly UsageTypeReport[];
  readonly totals: { readonly requests: number; readonly totalTokens: number };
  readonly peakDay?: UsageDailyRow;
  readonly callSamples: number;
  /** Toda a amostra por chamada, sem separar tipo — é ela que dimensiona um limite por chamada. */
  readonly allCalls?: { readonly total: Distribution; readonly prompt: Distribution };
}

export function buildUsageReport(input: {
  readonly sinceUtcDate: string;
  readonly days: number;
  readonly byType: readonly UsageAggregateRow[];
  readonly byDay: readonly UsageDailyRow[];
  readonly calls?: readonly CallSample[];
}): UsageReport {
  const calls = input.calls ?? [];
  const explainRows = input.byType.filter((row) => isExplainType(row.requestType));
  const explainGroup: UsageAggregateRow | undefined =
    explainRows.length > 0
      ? {
          requestType: EXPLAIN_GROUP,
          accounts: Number.NaN, // contas distintas não somam entre tipos
          activeDays: Number.NaN,
          requests: sum(explainRows.map((row) => row.requests)),
          promptTokens: sum(explainRows.map((row) => row.promptTokens)),
          outputTokens: sum(explainRows.map((row) => row.outputTokens)),
          totalTokens: sum(explainRows.map((row) => row.totalTokens)),
        }
      : undefined;

  const rows = explainGroup ? [...input.byType, explainGroup] : [...input.byType];
  const types = rows.map((row): UsageTypeReport => {
    const typeCalls = calls.filter((call) =>
      row.requestType === EXPLAIN_GROUP
        ? isExplainType(call.requestType)
        : call.requestType === row.requestType,
    );
    return {
      requestType: row.requestType,
      accounts: Number.isFinite(row.accounts) ? row.accounts : undefined,
      requests: row.requests,
      avgPromptPerRequest: perRequest(row.promptTokens, row.requests),
      // Saída = total − prompt: o critério que vale para as duas épocas (ver o cabeçalho).
      avgOutputPerRequest: perRequest(row.totalTokens - row.promptTokens, row.requests),
      avgTotalPerRequest: perRequest(row.totalTokens, row.requests),
      perCall: perCallOf(typeCalls),
    };
  });

  const peakDay = [...input.byDay].sort((a, b) => b.requests - a.requests)[0];
  const allTotals = distribution(calls.map((call) => call.totalTokens));
  const allPrompts = distribution(calls.map((call) => call.promptTokens));

  return {
    sinceUtcDate: input.sinceUtcDate,
    days: input.days,
    types,
    totals: {
      requests: sum(input.byType.map((row) => row.requests)),
      totalTokens: sum(input.byType.map((row) => row.totalTokens)),
    },
    peakDay,
    callSamples: calls.length,
    allCalls: allTotals && allPrompts ? { total: allTotals, prompt: allPrompts } : undefined,
  };
}

export function renderUsageReport(report: UsageReport): string {
  const lines: string[] = [];
  lines.push(`AI usage — últimos ${report.days} dias (desde ${report.sinceUtcDate}, UTC)`);
  lines.push('');
  lines.push(
    '| Request type | Contas | Tentativas | Média entrada/tent. | Média saída/tent. | Média total/tent. | n chamadas | Média total/chamada | P95 total | Máx total | P95 duração (ms) |',
  );
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |');
  for (const type of report.types) {
    lines.push(
      `| ${type.requestType} | ${formatNumber(type.accounts)} | ${type.requests} | ${formatNumber(type.avgPromptPerRequest)} | ${formatNumber(type.avgOutputPerRequest)} | ${formatNumber(type.avgTotalPerRequest)} | ${type.perCall?.total.n ?? 0} | ${formatNumber(type.perCall?.total.avg)} | ${formatNumber(type.perCall?.total.p95)} | ${formatNumber(type.perCall?.total.max)} | ${formatNumber(type.perCall?.durationMs?.p95)} |`,
    );
  }
  lines.push('');
  lines.push(
    `Total: ${report.totals.requests} tentativas, ${report.totals.totalTokens} tokens.` +
      (report.peakDay
        ? ` Pico diário: ${report.peakDay.requests} tentativas / ${report.peakDay.totalTokens} tokens em ${report.peakDay.utcDate}.`
        : ''),
  );
  if (report.allCalls) {
    lines.push(
      `Por chamada (todas, n=${report.allCalls.total.n}): total médio ${formatNumber(report.allCalls.total.avg)}, p95 ${formatNumber(report.allCalls.total.p95)}, máx ${formatNumber(report.allCalls.total.max)}; entrada p95 ${formatNumber(report.allCalls.prompt.p95)}.`,
    );
  } else {
    lines.push(
      'Distribuição por chamada: sem amostra (passe --calls com o export de ai.request.finished).',
    );
  }
  lines.push('');
  lines.push(
    'Leitura: "tentativa" inclui chamadas que o provider recusou sem devolver tokens — a média por tentativa é um piso. Saída = total − entrada (inclui o raciocínio do modelo).',
  );
  return lines.join('\n');
}

function perCallOf(calls: readonly CallSample[]): UsageTypeReport['perCall'] {
  const total = distribution(calls.map((call) => call.totalTokens));
  const prompt = distribution(calls.map((call) => call.promptTokens));
  const output = distribution(calls.map((call) => call.totalTokens - call.promptTokens));
  if (!total || !prompt || !output) return undefined;
  const durations = calls
    .map((call) => call.durationMs)
    .filter((value): value is number => value !== undefined);
  return { total, prompt, output, durationMs: distribution(durations) };
}

function isExplainType(requestType: string): boolean {
  return requestType.startsWith('EXPLAIN_') && isExplanation(requestType as AiCoachRequestType);
}

function perRequest(tokens: number, requests: number): number | undefined {
  return requests > 0 ? tokens / requests : undefined;
}

function sum(values: readonly number[]): number {
  return values.reduce((total, value) => total + value, 0);
}
