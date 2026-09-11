import { createHash } from 'node:crypto';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { Client, Pool } from 'pg';
import type { Clock } from '../common/clock';
import type { SparkLogger } from '../common/logger';
import {
  appliedVersions,
  loadMigrations,
  runMigrations,
} from '../database/postgres-migration-runner';
import { normalizeSslMode, parsePostgresUrl } from '../database/postgres-url';
import type { DrBackupStore, DrValidBackup } from './dr-backup.store';
import { isDrBackupId } from './dr-manifest';
import type { PgTools } from './pg-tools';

/** A forma obrigatória do nome de um banco de ensaio. Nada fora dela é destino aceitável. */
export const DRILL_DATABASE_PATTERN = /^spark_drill_[a-z0-9_]{1,40}$/;

export interface DrRestoreDrillDependencies {
  readonly store: DrBackupStore;
  readonly tools: PgTools;
  readonly clock: Clock;
  readonly logger: SparkLogger;
  readonly workDir: string;
  /** O backup a restaurar, ou `null` para o válido mais recente. */
  readonly backupId: string | null;
  /** Uma conexão administrativa ao servidor do ensaio (com `CREATEDB`), a um banco de manutenção. */
  readonly adminUrl: string;
  /** O banco descartável que o ensaio cria. Obrigatório, e só na forma [DRILL_DATABASE_PATTERN]. */
  readonly drillDatabase: string;
  /** `true` mantém o banco no fim (para inspeção); o default é descartá-lo sempre. */
  readonly keepDatabase: boolean;
  /** `true` permite `DROP DATABASE` de um banco de ensaio anterior com o mesmo nome. */
  readonly replaceExisting: boolean;
  /** As migrations do repositório — a "aplicação" que precisa subir sobre o restaurado. */
  readonly migrationsDirectory: string;
}

export interface DrRestoreDrillResult {
  readonly verdict: 'RESTORE_DRILL_PASS';
  readonly backupId: string;
  readonly drillDatabase: string;
  readonly restoredSchemaVersion: number;
  readonly migrationsAppliedDuringDrill: number;
  readonly finalSchemaVersion: number;
  readonly tables: number;
  readonly essentialCounts: Readonly<Record<string, number>>;
  readonly durationMs: number;
}

export class DrRestoreDrillError extends Error {
  constructor(
    readonly step: string,
    message: string,
  ) {
    super(`${step}: ${message}`);
    this.name = 'DrRestoreDrillError';
  }
}

/** As tabelas que um banco do Spark restaurado precisa responder — contagens, nunca conteúdo. */
const ESSENTIAL_TABLES = [
  'server_metadata',
  'backup_snapshots',
  'sync_entities',
  'account_deletion_tombstones',
  'social_profiles',
] as const;

/**
 * O ensaio de restauração em destino **limpo** (T18.3 §5/§6/§7).
 *
 * ```text
 * escolher o backup (id explícito, ou o válido mais recente)
 *       ↓
 * guardas de destino: nome na forma spark_drill_*, ≠ banco do manifesto,
 *                     banco novo (ou --replace de um ensaio anterior), nunca produção
 *       ↓
 * ler o dump · SHA-256 e tamanho iguais ao manifesto · pg_restore --list
 *       ↓
 * CREATE DATABASE <drill>  (vazio por construção)
 *       ↓
 * pg_restore --single-transaction (sem --clean: não há o que limpar)
 *       ↓
 * schema_migrations == manifesto · tabelas == manifesto (nenhum objeto a mais: destino limpo)
 *       ↓
 * a "aplicação" sobe: migrations pendentes aplicadas, readiness == /health/ready
 *       ↓
 * consultas essenciais respondem
 *       ↓
 * DROP DATABASE <drill>  (salvo --keep)
 *       ↓
 * RESTORE_DRILL_PASS
 * ```
 *
 * ## Por que o destino é sempre novo
 *
 * `pg_restore --clean --if-exists` sobre um banco existente só recria o que está **no dump**; um
 * objeto que o banco ganhou depois do snapshot (uma migration mais nova) sobrevive, e
 * `schema_migrations` restaurada deixa de descrevê-lo (`ops/tests/restore-old-snapshot-risk.test.sh`).
 * Um banco recém-criado não tem esse problema por construção — e a comparação "tabelas do banco
 * == tabelas do manifesto" é o que prova, a cada ensaio, que nada além do snapshot está lá.
 *
 * ## Produção nunca é destino
 *
 * Não existe default: `drillDatabase` é obrigatório, precisa ter a forma `spark_drill_*`, e é
 * recusado se coincidir com o nome do banco que o manifesto descreve. A conexão administrativa é
 * recusada se **for** o banco de produção do manifesto (mesmo host, mesmo database): um ensaio
 * conecta a um banco de manutenção — nunca ao que está sendo protegido.
 */
export async function runDrRestoreDrill(
  deps: DrRestoreDrillDependencies,
): Promise<DrRestoreDrillResult> {
  const startedAt = deps.clock.now();
  const drill = deps.drillDatabase;
  const logBase = {
    operation: 'db_restore_drill',
    drillDatabase: drill,
    provider: deps.store.provider,
  };

  // --- 0. guardas que não precisam de rede -----------------------------------------------
  if (!DRILL_DATABASE_PATTERN.test(drill)) {
    throw new DrRestoreDrillError(
      'destino',
      `o banco de ensaio precisa ter a forma spark_drill_<a-z0-9_>: recebido um nome fora da forma`,
    );
  }
  const admin = parsePostgresUrl(deps.adminUrl); // fail closed sem database explícito
  if (admin.database === drill) {
    throw new DrRestoreDrillError(
      'destino',
      'a conexão administrativa não pode apontar para o próprio banco de ensaio (ele ainda não existe)',
    );
  }

  const workDir = await mkdtemp(join(deps.workDir, 'spark-dr-drill-'));
  let databaseCreated = false;
  try {
    // --- 1. o backup ------------------------------------------------------------------------
    const backup = await step('backup', () => resolveBackup(deps));
    const manifest = backup.manifest;
    deps.logger.info('db_restore_started', {
      ...logBase,
      backupId: backup.backupId,
      schemaVersion: manifest.schema.schemaVersion,
      sizeBytes: manifest.dumpSizeBytes,
    });

    if (drill === manifest.databaseIdentity.database) {
      throw new DrRestoreDrillError(
        'destino',
        'o banco de ensaio tem o mesmo nome do banco descrito pelo manifesto — produção nunca é destino',
      );
    }
    if (
      admin.host === manifest.databaseIdentity.host &&
      admin.database === manifest.databaseIdentity.database
    ) {
      throw new DrRestoreDrillError(
        'destino',
        'a conexão administrativa É o banco de produção do manifesto; use um banco de manutenção ou outro servidor',
      );
    }
    if (admin.host === manifest.databaseIdentity.host) {
      deps.logger.warn('db_restore_drill_shares_production_host', {
        ...logBase,
        backupId: backup.backupId,
      });
    }

    // --- 2. o dump, conferido contra o manifesto --------------------------------------------
    const dumpBytes = await step('dump', () => deps.store.readDump(backup.backupId));
    if (dumpBytes === null) {
      throw new DrRestoreDrillError('dump', 'database.dump não encontrado para o backup escolhido');
    }
    if (dumpBytes.length !== manifest.dumpSizeBytes) {
      throw new DrRestoreDrillError('checksum', 'tamanho do dump difere do manifesto');
    }
    const sha256 = createHash('sha256').update(dumpBytes).digest('hex');
    if (sha256 !== manifest.sha256) {
      throw new DrRestoreDrillError('checksum', 'SHA-256 do dump difere do manifesto');
    }
    const dumpFile = join(workDir, 'database.dump');
    await writeFile(dumpFile, dumpBytes, { mode: 0o600 });

    const toc = await step('pg_restore --list', () => deps.tools.listToc(dumpFile));
    if (toc.length === 0 || !toc.some((entry) => / TABLE DATA .* schema_migrations /.test(entry))) {
      throw new DrRestoreDrillError(
        'pg_restore --list',
        'o dump não abre ou não contém schema_migrations',
      );
    }

    // --- 3. o destino limpo -----------------------------------------------------------------
    await step('create database', async () => {
      const client = await connect(deps.adminUrl);
      try {
        const existing = await client.query('SELECT 1 FROM pg_database WHERE datname = $1', [
          drill,
        ]);
        if ((existing.rowCount ?? 0) > 0) {
          if (!deps.replaceExisting) {
            throw new DrRestoreDrillError(
              'create database',
              `o banco de ensaio já existe; um ensaio só restaura em banco novo (use --replace para descartar um ensaio anterior com o mesmo nome)`,
            );
          }
          // Só um banco que passou pelo padrão `spark_drill_*` chega aqui — nunca outro nome.
          await client.query(`DROP DATABASE "${drill}" WITH (FORCE)`);
        }
        await client.query(`CREATE DATABASE "${drill}"`);
        databaseCreated = true;
      } finally {
        await client.end().catch(() => undefined);
      }
    });

    const drillUrl = withDatabase(deps.adminUrl, drill);

    // --- 4. restaurar -----------------------------------------------------------------------
    await step('pg_restore', () => deps.tools.restore(drillUrl, dumpFile));

    // --- 5. o restaurado é exatamente o snapshot --------------------------------------------
    const restored = await step('schema', () => describeRestored(drillUrl));
    const expectedMigrations = manifest.schema.migrations.map((m) => `${m.version}:${m.name}`);
    const actualMigrations = restored.migrations.map((m) => `${m.version}:${m.name}`);
    if (JSON.stringify(expectedMigrations) !== JSON.stringify(actualMigrations)) {
      throw new DrRestoreDrillError('schema', 'schema_migrations restaurada difere do manifesto');
    }
    const expectedTables = [...manifest.schema.tables].sort();
    const actualTables = [...restored.tables].sort();
    if (JSON.stringify(expectedTables) !== JSON.stringify(actualTables)) {
      throw new DrRestoreDrillError(
        'schema',
        `as tabelas restauradas não são exatamente as do manifesto (esperadas ${expectedTables.length}, encontradas ${actualTables.length}) — o destino não estava limpo ou o dump é outro`,
      );
    }

    // --- 6. a aplicação sobe sobre o restaurado ---------------------------------------------
    //
    // O mesmo caminho de uma recuperação real: o Job de migration aplica o que faltar (um
    // snapshot mais antigo que o código atual), e a readiness confere que tudo o que o código
    // espera está aplicado — é a verificação de `/health/ready`, sem o HTTP.
    const migrations = loadMigrations(deps.migrationsDirectory);
    // Um `Pool` (e não `Client`): o runner distingue os dois pela presença de `connect()`, e um
    // `Client` também a tem — passar um `Client` o faria tratar a conexão como pool.
    const pool = new Pool({
      connectionString: normalizeSslMode(drillUrl).connectionString,
      max: 1,
    });
    let applied: Awaited<ReturnType<typeof runMigrations>>;
    let ready: boolean;
    try {
      applied = await step('migrations', () => runMigrations(pool, migrations));
      ready = await step('readiness', async () => {
        const versions = new Set(await appliedVersions(pool));
        return migrations.every((migration) => versions.has(migration.version));
      });
    } finally {
      await pool.end().catch(() => undefined);
    }
    if (!ready) {
      throw new DrRestoreDrillError(
        'readiness',
        'o banco restaurado não está no nível de schema que o código espera',
      );
    }

    // --- 7. consultas essenciais ------------------------------------------------------------
    const essentialCounts = await step('consultas essenciais', async () => {
      const client = await connect(drillUrl);
      try {
        const counts: Record<string, number> = {};
        for (const table of ESSENTIAL_TABLES) {
          const res = await client.query<{ n: string }>(`SELECT COUNT(*) AS n FROM ${table}`);
          counts[table] = Number(res.rows[0]?.n ?? 0);
        }
        return counts;
      } finally {
        await client.end().catch(() => undefined);
      }
    });

    const durationMs = deps.clock.now() - startedAt;
    const result: DrRestoreDrillResult = {
      verdict: 'RESTORE_DRILL_PASS',
      backupId: backup.backupId,
      drillDatabase: drill,
      restoredSchemaVersion: manifest.schema.schemaVersion,
      migrationsAppliedDuringDrill: applied.length,
      finalSchemaVersion: migrations.at(-1)?.version ?? 0,
      tables: actualTables.length,
      essentialCounts,
      durationMs,
    };
    deps.logger.info('db_restore_completed', {
      ...logBase,
      backupId: backup.backupId,
      status: 'RESTORE_DRILL_PASS',
      durationMs,
      sizeBytes: manifest.dumpSizeBytes,
      sha256: manifest.sha256,
      restoredSchemaVersion: manifest.schema.schemaVersion,
      migrationsAppliedDuringDrill: applied.length,
      tables: actualTables.length,
    });
    return result;
  } catch (error) {
    deps.logger.error('db_restore_failed', {
      ...logBase,
      status: 'RESTORE_DRILL_FAIL',
      durationMs: deps.clock.now() - startedAt,
      step: error instanceof DrRestoreDrillError ? error.step : 'desconhecido',
      errorName: error instanceof Error ? error.name : 'UNKNOWN',
      errorMessage: error instanceof Error ? error.message : String(error),
    });
    throw error;
  } finally {
    await rm(workDir, { recursive: true, force: true }).catch(() => undefined);
    if (databaseCreated && !deps.keepDatabase) {
      await dropDrillDatabase(deps, drill).catch((error: unknown) => {
        deps.logger.warn('db_restore_drill_cleanup_failed', {
          ...logBase,
          errorName: error instanceof Error ? error.name : 'UNKNOWN',
        });
      });
    }
  }
}

async function step<T>(name: string, work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch (error) {
    if (error instanceof DrRestoreDrillError) {
      throw error;
    }
    throw new DrRestoreDrillError(name, error instanceof Error ? error.message : String(error));
  }
}

async function resolveBackup(deps: DrRestoreDrillDependencies): Promise<DrValidBackup> {
  if (deps.backupId === null) {
    const latest = await deps.store.latestValid();
    if (latest === null) {
      throw new DrRestoreDrillError('backup', 'nenhum backup válido encontrado no namespace de DR');
    }
    return latest;
  }
  if (!isDrBackupId(deps.backupId)) {
    throw new DrRestoreDrillError('backup', 'backupId fora da forma esperada');
  }
  const folder = (await deps.store.listFolders()).find((f) => f.backupId === deps.backupId);
  if (folder === undefined) {
    throw new DrRestoreDrillError('backup', 'backup não encontrado');
  }
  const status = await deps.store.statusOf(folder);
  if (status.kind !== 'valid') {
    throw new DrRestoreDrillError('backup', `o backup escolhido não é válido: ${status.kind}`);
  }
  return status.backup;
}

async function connect(url: string): Promise<Client> {
  const client = new Client({
    connectionString: normalizeSslMode(url).connectionString,
    statement_timeout: 120_000,
  });
  await client.connect();
  return client;
}

/** A mesma URL, apontando para outro database — autoridade e query string preservadas. */
export function withDatabase(url: string, database: string): string {
  const parsed = new URL(url);
  parsed.pathname = `/${database}`;
  return parsed.toString();
}

interface RestoredDescription {
  readonly migrations: readonly { version: number; name: string }[];
  readonly tables: readonly string[];
}

async function describeRestored(url: string): Promise<RestoredDescription> {
  const client = await connect(url);
  try {
    const migrations = await client.query<{ version: number; name: string }>(
      'SELECT version, name FROM schema_migrations ORDER BY version',
    );
    const tables = await client.query<{ table_name: string }>(
      `SELECT table_name FROM information_schema.tables
       WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
       ORDER BY table_name`,
    );
    return {
      migrations: migrations.rows.map((row) => ({ version: Number(row.version), name: row.name })),
      tables: tables.rows.map((row) => row.table_name),
    };
  } finally {
    await client.end().catch(() => undefined);
  }
}

async function dropDrillDatabase(deps: DrRestoreDrillDependencies, drill: string): Promise<void> {
  if (!DRILL_DATABASE_PATTERN.test(drill)) {
    return; // nunca chega aqui: a guarda de entrada já recusou. Defesa em profundidade.
  }
  const client = await connect(deps.adminUrl);
  try {
    await client.query(`DROP DATABASE IF EXISTS "${drill}" WITH (FORCE)`);
  } finally {
    await client.end().catch(() => undefined);
  }
}
