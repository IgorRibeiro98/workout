import { join } from 'node:path';
import { Pool } from 'pg';
import {
  appliedVersions,
  loadMigrations,
  runMigrations,
} from '../database/postgres-migration-runner';
import { MIGRATIONS_DIRNAME } from '../database/database.constants';
import {
  normalizeSslMode,
  postgresUrlIdentity,
  PostgresUrlIdentityError,
} from '../database/postgres-url';

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
/**
 * Eventos estruturados em stdout, uma linha JSON por evento (T18.3 §14). O Cloud Run entrega
 * stdout ao Cloud Logging, que reconhece JSON como `jsonPayload` — é o que os alertas de
 * `migration_failed` filtram. Nunca a URL, nunca a senha: só operação, banco, contagens e erro.
 */
function emit(event: string, fields: Record<string, unknown>): void {
  process.stdout.write(`${JSON.stringify({ event, operation: 'db_migration', ...fields })}\n`);
}

export async function runDatabaseMigration(): Promise<number> {
  const startedAt = Date.now();
  const directUrl = process.env.DATABASE_URL_DIRECT?.trim();
  if (!directUrl) {
    process.stderr.write(
      'migrate:database exige DATABASE_URL_DIRECT — ele não cai no endpoint pooled.\n',
    );
    emit('migration_failed', { status: 'FAILED', errorName: 'MissingDatabaseUrlDirect' });
    return 1;
  }

  // T18.3 §4 — migration é operação administrativa: a URL precisa dizer qual database manipula.
  // `postgres://host` cairia no banco default do papel; aqui isso é recusa, nunca surpresa.
  let database: string;
  try {
    database = postgresUrlIdentity(directUrl).database;
  } catch (error) {
    if (error instanceof PostgresUrlIdentityError) {
      process.stderr.write(`migrate:database recusou DATABASE_URL_DIRECT: ${error.message}\n`);
      emit('migration_failed', { status: 'FAILED', errorName: error.name });
      return 1;
    }
    throw error;
  }

  // A mesma política de TLS da API (T18.3 §20): `sslmode=require` vira `verify-full` explícito.
  const pool = new Pool({ connectionString: normalizeSslMode(directUrl).connectionString, max: 2 });
  pool.on('error', (error) => {
    process.stderr.write(
      `erro assíncrono do pool de migration: ${error instanceof Error ? error.name : 'desconhecido'}\n`,
    );
  });

  try {
    const migrationsDirectory = join(__dirname, '..', '..', MIGRATIONS_DIRNAME);
    const migrations = loadMigrations(migrationsDirectory);
    emit('migration_started', { database, migrationsLoaded: migrations.length });

    const applied = await runMigrations(pool, migrations);

    const expected = migrations.map((migration) => migration.version);
    const current = new Set(await appliedVersions(pool));
    const pending = expected.filter((version) => !current.has(version));

    if (pending.length > 0) {
      // Não deveria acontecer depois de `runMigrations` ter retornado sem lançar — mas verificar
      // de verdade, e não só confiar no retorno, é o que "verifica schema" quer dizer no §7.
      process.stderr.write(
        `schema ainda não está no nível esperado: faltam ${pending.join(', ')}\n`,
      );
      emit('migration_failed', {
        status: 'FAILED',
        database,
        errorName: 'SchemaPending',
        pending,
        durationMs: Date.now() - startedAt,
      });
      return 1;
    }

    const schemaVersion = expected.at(-1) ?? 0;
    emit('migration_completed', {
      status: 'SUCCESS',
      database,
      migrationsApplied: applied.length,
      schemaVersion,
      durationMs: Date.now() - startedAt,
    });
    return 0;
  } catch (error) {
    process.stderr.write(
      `migration abortada: ${error instanceof Error ? error.message : String(error)}\n`,
    );
    emit('migration_failed', {
      status: 'FAILED',
      database,
      errorName: error instanceof Error ? error.name : 'UNKNOWN',
      durationMs: Date.now() - startedAt,
    });
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
