import 'reflect-metadata';
import { readFileSync } from 'node:fs';
import { SparkLogger } from '../common/logger';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { PostgresService } from '../database/postgres.service';
import { utcDateOf } from '../modules/ai/ai-usage.repository';
import {
  buildUsageReport,
  parseCallSamples,
  queryUsage,
  renderUsageReport,
  type CallSample,
} from '../modules/ai/eval/usage-report';

/**
 * `ai:usage-report` — o consumo real do Coach, sem conteúdo (T19.H4 §5).
 *
 * ```bash
 * npm run ai:usage-report                       # últimos 7 dias, a partir de ai_usage_daily
 * npm run ai:usage-report -- --days 30
 * npm run ai:usage-report -- --days 30 --calls ./calls.json   # + distribuição por chamada
 * npm run ai:usage-report -- --json             # o mesmo relatório, em JSON
 * ```
 *
 * `--calls` recebe o export dos eventos `ai.request.finished` do Cloud Logging — o único lugar em
 * que existe uma linha **por chamada** (a tabela guarda agregados por conta/dia/tipo):
 *
 * ```bash
 * gcloud logging read 'resource.type="cloud_run_revision"
 *   AND resource.labels.service_name="spark-backend"
 *   AND jsonPayload.event="ai.request.finished"' \
 *   --project <projeto> --freshness=30d --format=json > calls.json
 * ```
 *
 * **Somente leitura**: duas consultas `SELECT`, e `DATABASE_MIGRATION_MODE=verify` forçado — um
 * relatório nunca aplica migration, mesmo apontado para produção. `missingRequirements()` não é
 * consultado, pelo mesmo motivo de `storage-audit`: ler agregados não exige HMAC, Firebase nem
 * credencial de provider. A saída não contém uid, prompt, resposta nem nome de treino/exercício.
 */
export async function runAiUsageReport(argv: readonly string[]): Promise<number> {
  const args = parseArgs(argv);
  if (typeof args === 'string') {
    process.stderr.write(`${args}\n${USAGE}`);
    return 2;
  }

  let calls: CallSample[] | undefined;
  if (args.callsPath) {
    try {
      calls = parseCallSamples(readFileSync(args.callsPath, 'utf8'));
    } catch (error) {
      process.stderr.write(
        `não foi possível ler --calls: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      return 2;
    }
  }

  let config: AppConfig;
  try {
    config = AppConfig.fromEnv({
      ...process.env,
      DATABASE_MIGRATION_MODE: 'verify',
      LOG_LEVEL: process.env.LOG_LEVEL ?? 'warn',
    });
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const since = new Date(Date.now() - (args.days - 1) * 24 * 60 * 60 * 1000);
  const sinceUtcDate = utcDateOf(since);

  const postgres = new PostgresService(config, new SparkLogger(config));
  try {
    await postgres.initialize();
    const { byType, byDay } = await queryUsage(postgres, sinceUtcDate);
    const report = buildUsageReport({ sinceUtcDate, days: args.days, byType, byDay, calls });
    process.stdout.write(
      args.json ? `${JSON.stringify(report, null, 2)}\n` : `${renderUsageReport(report)}\n`,
    );
    return 0;
  } finally {
    await postgres.onApplicationShutdown();
  }
}

const USAGE = 'uso: node dist/cli/ai-usage-report.js [--days N] [--calls <export.json>] [--json]\n';

function parseArgs(
  argv: readonly string[],
): { days: number; callsPath?: string; json: boolean } | string {
  let days = 7;
  let callsPath: string | undefined;
  let json = false;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--json') {
      json = true;
    } else if (arg === '--days') {
      const value = argv[++index];
      if (!value || !/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 400) {
        return '--days precisa ser um inteiro entre 1 e 400';
      }
      days = Number(value);
    } else if (arg === '--calls') {
      callsPath = argv[++index];
      if (!callsPath) return '--calls precisa de um caminho';
    } else {
      return `argumento desconhecido: ${arg}`;
    }
  }
  return { days, callsPath, json };
}

if (require.main === module) {
  runAiUsageReport(process.argv.slice(2))
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `relatório abortado: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
