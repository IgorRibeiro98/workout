import 'reflect-metadata';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { SparkLogger } from '../common/logger';
import { PostgresService } from '../database/postgres.service';
import { AI_CAPABILITIES, isAiCapability } from '../modules/ai/entitlement/ai-capability';
import { AiEntitlementRepository } from '../modules/ai/entitlement/ai-entitlement.repository';

/**
 * `spark-manage-ai-capabilities` — o mecanismo operacional mínimo para conceder, revogar e listar
 * entitlements de IA por conta (T19.0 §22/§23).
 *
 * Não existe painel administrativo nem endpoint HTTP de admin para isto — o mesmo padrão de
 * `reconcile-account-deletions.ts` e dos outros comandos de `src/cli/`: um script que fala
 * diretamente com o banco, com a mesma validação de configuração da API, e sem servidor HTTP.
 *
 * ```bash
 * npm run capabilities:ai -- grant  <uid> <capability>
 * npm run capabilities:ai -- revoke <uid> <capability>
 * npm run capabilities:ai -- list   <uid>
 * ```
 *
 * `grant`/`revoke` são idempotentes: `PRIMARY KEY (uid, capability)` garante que a segunda chamada
 * com o mesmo argumento só atualiza `updated_at`, sem duplicar linha (§9).
 */
export async function runManageAiCapabilities(argv: readonly string[]): Promise<number> {
  const [action, ...rest] = argv;

  if (action !== 'grant' && action !== 'revoke' && action !== 'list') {
    process.stderr.write(
      'uso:\n' +
        '  node dist/cli/manage-ai-capabilities.js grant  <uid> <capability>\n' +
        '  node dist/cli/manage-ai-capabilities.js revoke <uid> <capability>\n' +
        '  node dist/cli/manage-ai-capabilities.js list   <uid>\n' +
        `capabilities conhecidas: ${AI_CAPABILITIES.join(', ')}\n`,
    );
    return 2;
  }

  const uid = rest[0];
  if (!uid) {
    process.stderr.write('uid é obrigatório\n');
    return 2;
  }

  let capability: string | undefined;
  if (action !== 'list') {
    capability = rest[1];
    if (!capability) {
      process.stderr.write('capability é obrigatória\n');
      return 2;
    }
    if (!isAiCapability(capability)) {
      process.stderr.write(
        `capability desconhecida: ${capability}\ncapabilities conhecidas: ${AI_CAPABILITIES.join(', ')}\n`,
      );
      return 2;
    }
  }

  let config: AppConfig;
  try {
    config = AppConfig.fromEnv();
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const logger = new SparkLogger(config);
  const postgres = new PostgresService(config, logger);
  try {
    await postgres.initialize();
    const repository = new AiEntitlementRepository(postgres);

    if (action === 'list') {
      const rows = await repository.listFor(uid);
      if (rows.length === 0) {
        process.stdout.write(
          `${uid}: nenhuma linha gravada — as quatro capabilities conhecidas usam o default (liberado).\n`,
        );
        return 0;
      }
      for (const row of rows) {
        process.stdout.write(
          `${uid}\t${row.capability}\t${row.state}\t${new Date(row.updatedAt).toISOString()}\n`,
        );
      }
      return 0;
    }

    const state = action === 'grant' ? 'GRANTED' : 'REVOKED';
    // `isAiCapability` já confirmou o tipo acima; o `as` só declara isso ao compilador.
    await repository.setState(uid, capability as (typeof AI_CAPABILITIES)[number], state);
    process.stdout.write(`${uid}\t${capability}\t${state}\n`);
    return 0;
  } finally {
    await postgres.onApplicationShutdown();
  }
}

if (require.main === module) {
  runManageAiCapabilities(process.argv.slice(2))
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `comando abortado: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
