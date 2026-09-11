import { join } from 'node:path';
import { Pool } from 'pg';
import {
  appliedVersions,
  loadMigrations,
  runMigrations,
} from '../database/postgres-migration-runner';
import { MIGRATIONS_DIRNAME } from '../database/database.constants';

/**
 * `migrate:database` — o comando dedicado de migration do PostgreSQL (T18.2 §7).
 *
 * ## Por que ele não usa `AppConfig`
 *
 * Todo outro CLI do backend (`reconcile-account-deletions`, `object-storage-smoke`, ...) lê
 * configuração por `AppConfig.fromEnv()`, que exige `DATABASE_URL` — a pooled, da API. Este
 * comando é o oposto: ele **só** existe para separar quem aplica migration (este processo, com
 * `DATABASE_URL_DIRECT`) de quem serve tráfego (a API, com `DATABASE_MIGRATION_MODE=verify`,
 * nunca com o secret direto). Passar pela validação de `AppConfig` reintroduziria exatamente a
 * dependência que a separação de Service Account da T18.2 (`spark-backend-migrator`) existe para
 * eliminar: este comando não precisa, e não deve precisar, de `GEMINI_API_KEY`,
 * `ACCOUNT_DELETION_HMAC_KEY` ou qualquer outro segredo da API.
 *
 * `DATABASE_URL_DIRECT` é lido diretamente do ambiente, sem fallback para `DATABASE_URL` — ao
 * contrário de `AppConfig.databaseUrlDirect`. Um deploy que esqueceu de configurar o secret direto
 * no Job de migration precisa falhar aqui, alto e claro, e nunca migrar silenciosamente pelo
 * endpoint pooled (que em topologias como o pooler do Neon pode nem suportar advisory lock/DDL de
 * forma confiável).
 *
 * ```bash
 * DATABASE_URL_DIRECT=postgres://... node dist/cli/migrate-database.js
 * ```
 */
export async function runDatabaseMigration(): Promise<number> {
  const directUrl = process.env.DATABASE_URL_DIRECT?.trim();
  if (!directUrl) {
    process.stderr.write(
      'migrate:database exige DATABASE_URL_DIRECT — ele não cai no endpoint pooled.\n',
    );
    return 1;
  }

  const pool = new Pool({ connectionString: directUrl, max: 2 });
  pool.on('error', (error) => {
    process.stderr.write(
      `erro assíncrono do pool de migration: ${error instanceof Error ? error.name : 'desconhecido'}\n`,
    );
  });

  try {
    const migrationsDirectory = join(__dirname, '..', '..', MIGRATIONS_DIRNAME);
    const migrations = loadMigrations(migrationsDirectory);
    process.stdout.write(`migrations carregadas: ${migrations.length}\n`);

    const applied = await runMigrations(pool, migrations);
    process.stdout.write(`migrations aplicadas nesta execução: ${applied.length}\n`);

    const expected = migrations.map((migration) => migration.version);
    const current = new Set(await appliedVersions(pool));
    const pending = expected.filter((version) => !current.has(version));

    if (pending.length > 0) {
      // Não deveria acontecer depois de `runMigrations` ter retornado sem lançar — mas verificar
      // de verdade, e não só confiar no retorno, é o que "verifica schema" quer dizer no §7.
      process.stderr.write(
        `schema ainda não está no nível esperado: faltam ${pending.join(', ')}\n`,
      );
      return 1;
    }

    const schemaVersion = expected.at(-1) ?? 0;
    process.stdout.write(`schema atual: v${schemaVersion}\n`);
    return 0;
  } catch (error) {
    process.stderr.write(
      `migration abortada: ${error instanceof Error ? error.message : String(error)}\n`,
    );
    return 1;
  } finally {
    await pool.end().catch(() => undefined);
  }
}

if (require.main === module) {
  runDatabaseMigration()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `migration abortada: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
