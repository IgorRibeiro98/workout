import type { PostgresService } from '../src/database/postgres.service';
import { AiUsageRepository } from '../src/modules/ai/ai-usage.repository';
import {
  buildUsageReport,
  EXPLAIN_GROUP,
  parseCallSamples,
  queryUsage,
  renderUsageReport,
} from '../src/modules/ai/eval/usage-report';
import {
  configFor,
  createTempDb,
  MIGRATIONS_DIR,
  postgresFor,
  type TempDb,
} from './support/temp-db';

const UID_A = 'uid-relatorio-conta-a';
const UID_B = 'uid-relatorio-conta-b';

/**
 * T19.H4 §5/§6 — o consumo do Coach sai do que o Spark já registra, e sem conteúdo.
 *
 * O relatório lê `ai_usage_daily` (a tabela da quota) e, opcionalmente, o export dos eventos
 * `ai.request.finished`. Os testes provam as duas contas que importam para decidir provider —
 * média por tentativa e distribuição por chamada — e que nenhum uid atravessa.
 */
describe('Relatório de uso do Coach (ai:usage-report)', () => {
  let temp: TempDb;
  let postgres: PostgresService;

  beforeEach(async () => {
    temp = createTempDb();
    postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);
  });

  afterEach(async () => {
    await postgres.close();
    temp.cleanup();
  });

  it('agrega por tipo e por dia a partir de ai_usage_daily, sem uid na saída', async () => {
    const usage = new AiUsageRepository(postgres);
    const tokens = (prompt: number, total: number) => ({
      promptTokens: prompt,
      outputTokens: total - prompt,
      totalTokens: total,
    });

    // Conta A: duas análises com tokens e uma tentativa que o provider recusou (sem tokens).
    await usage.recordAttempt(UID_A, '2026-09-20', 'ANALYZE_WORKOUT');
    await usage.recordTokens(UID_A, '2026-09-20', 'ANALYZE_WORKOUT', tokens(1500, 2500));
    await usage.recordAttempt(UID_A, '2026-09-20', 'ANALYZE_WORKOUT');
    await usage.recordTokens(UID_A, '2026-09-20', 'ANALYZE_WORKOUT', tokens(1700, 2900));
    await usage.recordAttempt(UID_A, '2026-09-21', 'ANALYZE_WORKOUT');
    // Conta B: geração e duas explicações de tipos diferentes.
    await usage.recordAttempt(UID_B, '2026-09-21', 'GENERATE_WORKOUT');
    await usage.recordTokens(UID_B, '2026-09-21', 'GENERATE_WORKOUT', tokens(2000, 3000));
    await usage.recordAttempt(UID_B, '2026-09-21', 'EXPLAIN_WORKOUT');
    await usage.recordTokens(UID_B, '2026-09-21', 'EXPLAIN_WORKOUT', tokens(1200, 1500));
    await usage.recordAttempt(UID_B, '2026-09-21', 'EXPLAIN_PROGRESS');
    await usage.recordTokens(UID_B, '2026-09-21', 'EXPLAIN_PROGRESS', tokens(1000, 1300));
    // Fora da janela: não entra.
    await usage.recordAttempt(UID_B, '2026-08-01', 'ADAPT_WORKOUT');

    const { byType, byDay } = await queryUsage(postgres, '2026-09-18');
    const report = buildUsageReport({ sinceUtcDate: '2026-09-18', days: 7, byType, byDay });

    const analyze = report.types.find((type) => type.requestType === 'ANALYZE_WORKOUT')!;
    expect(analyze.requests).toBe(3);
    expect(analyze.accounts).toBe(1);
    // (2500 + 2900) / 3 tentativas: a recusada entra no denominador — é um piso por chamada.
    expect(analyze.avgTotalPerRequest).toBeCloseTo(1800);
    expect(analyze.avgPromptPerRequest).toBeCloseTo(1066.67, 1);
    expect(analyze.avgOutputPerRequest).toBeCloseTo(733.33, 1);

    const explain = report.types.find((type) => type.requestType === EXPLAIN_GROUP)!;
    expect(explain.requests).toBe(2);
    expect(explain.avgTotalPerRequest).toBeCloseTo(1400);

    expect(report.types.some((type) => type.requestType === 'ADAPT_WORKOUT')).toBe(false);
    expect(report.totals).toEqual({ requests: 6, totalTokens: 11200 });
    expect(report.peakDay).toMatchObject({ utcDate: '2026-09-21', requests: 4 });

    const rendered = renderUsageReport(report) + JSON.stringify(report);
    expect(rendered).not.toContain(UID_A);
    expect(rendered).not.toContain(UID_B);
    expect(rendered).not.toContain('uid');
  });

  it('com o export de log, calcula a distribuição por chamada (p95, máximo, duração)', () => {
    const entry = (requestType: string, prompt: number, total: number, durationMs: number) => ({
      timestamp: '2026-09-21T10:00:00Z',
      jsonPayload: {
        event: 'ai.request.finished',
        requestType,
        status: 'SUCCESS',
        uidPrefix: 'abc123',
        clientRequestId: 'cli-000000000001',
        promptTokens: prompt,
        totalTokens: total,
        durationMs,
      },
    });
    const entries = [
      entry('ANALYZE_WORKOUT', 1500, 2500, 7000),
      entry('ANALYZE_WORKOUT', 1700, 2900, 9000),
      entry('GENERATE_WORKOUT', 2000, 3000, 12000),
      // Evento sem tokens (falha antes do provider) e evento de outro tipo: ignorados.
      {
        jsonPayload: {
          event: 'ai.request.finished',
          requestType: 'ANALYZE_WORKOUT',
          status: 'PROVIDER_FAILED',
        },
      },
      { jsonPayload: { event: 'http.request', status: 201 } },
    ];

    // `gcloud logging read --format=json` (array) e JSON Lines dão o mesmo resultado.
    const calls = parseCallSamples(JSON.stringify(entries));
    expect(calls).toHaveLength(3);
    expect(parseCallSamples(entries.map((item) => JSON.stringify(item)).join('\n'))).toEqual(calls);

    const report = buildUsageReport({
      sinceUtcDate: '2026-09-18',
      days: 7,
      byType: [
        {
          requestType: 'ANALYZE_WORKOUT',
          accounts: 1,
          activeDays: 1,
          requests: 2,
          promptTokens: 3200,
          outputTokens: 2200,
          totalTokens: 5400,
        },
      ],
      byDay: [],
      calls,
    });
    const analyze = report.types.find((type) => type.requestType === 'ANALYZE_WORKOUT')!;
    expect(analyze.perCall?.total).toMatchObject({ n: 2, avg: 2700, p95: 2900, max: 2900 });
    expect(analyze.perCall?.output.avg).toBe(1100);
    expect(analyze.perCall?.durationMs?.p95).toBe(9000);
    expect(report.allCalls?.total).toMatchObject({ n: 3, max: 3000 });

    const rendered = renderUsageReport(report);
    expect(rendered).not.toContain('abc123');
    expect(rendered).not.toContain('cli-000000000001');
  });
});
