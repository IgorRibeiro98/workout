import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { Client } from 'pg';
import type { Clock } from '../common/clock';
import type { SparkLogger } from '../common/logger';
import { normalizeSslMode, postgresUrlIdentity } from '../database/postgres-url';
import { DrBackupStore } from './dr-backup.store';
import {
  DR_MANIFEST_FORMAT_VERSION,
  drBackupIdFor,
  drDumpObjectName,
  type DrBackupManifest,
} from './dr-manifest';
import { planDrRetention } from './dr-retention';
import type { PgTools } from './pg-tools';

export interface DrBackupDependencies {
  readonly databaseUrlDirect: string;
  readonly store: DrBackupStore;
  readonly tools: PgTools;
  readonly clock: Clock;
  readonly logger: SparkLogger;
  readonly retentionCount: number;
  readonly workDir: string;
  readonly maxDumpBytes: number;
  readonly gitCommit: string | null;
  readonly imageDigest: string | null;
}

export interface DrBackupResult {
  readonly backupId: string;
  readonly dumpSizeBytes: number;
  readonly sha256: string;
  readonly schemaVersion: number;
  readonly retention: { readonly kept: number; readonly removed: number; readonly ignored: number };
  readonly durationMs: number;
}

export class DrBackupError extends Error {
  constructor(
    readonly step: string,
    message: string,
  ) {
    super(`${step}: ${message}`);
    this.name = 'DrBackupError';
  }
}

/**
 * O backup de DR do PostgreSQL, ponta a ponta (T18.3 §2/§3/§8).
 *
 * ```text
 * identidade da URL (fail closed)
 *       ↓
 * pg_dump --format=custom → arquivo temporário
 *       ↓
 * tamanho > 0 · pg_restore --list (o arquivo abre) · SHA-256
 *       ↓
 * upload de database.dump (create-only, CRC32C pelo provider)
 *       ↓
 * releitura do dump: tamanho e SHA-256 iguais aos calculados
 *       ↓
 * manifest.json — gravado por ÚLTIMO
 *       ↓
 * releitura do manifesto (faz parse, backupId bate)
 *       ↓
 * retenção: só backups válidos além de `retentionCount`, nunca o último
 *       ↓
 * db_backup_completed
 * ```
 *
 * ## Falha fechada
 *
 * Qualquer passo que lance derruba o Job com código != 0 e `db_backup_failed` — inclusive a
 * releitura. "O `pg_dump` saiu com 0" não é um backup; "o objeto foi lido de volta com o mesmo
 * hash e o manifesto está lá" é. Um dump que subiu e cujo manifesto não subiu fica como pasta
 * incompleta: a retenção o ignora e o auditor o aponta, mas ninguém o toma por válido.
 *
 * ## Retenção depois, nunca antes
 *
 * A retenção roda **depois** de o backup novo estar completo. Se ela falhar, o Job falha (o
 * operador precisa saber), mas o backup novo já está íntegro no bucket — falha de retenção nunca
 * custa um backup.
 */
export async function runDrBackup(deps: DrBackupDependencies): Promise<DrBackupResult> {
  const startedAt = deps.clock.now();
  const identity = postgresUrlIdentity(deps.databaseUrlDirect);
  const backupId = drBackupIdFor(startedAt);
  const logFields = {
    operation: 'db_backup',
    backupId,
    database: identity.database,
    provider: deps.store.provider,
  };
  deps.logger.info('db_backup_started', logFields);

  const workDir = await mkdtemp(join(deps.workDir, 'spark-dr-'));
  const dumpFile = join(workDir, 'database.dump');
  try {
    // --- 1. o dump ------------------------------------------------------------------------
    const { pgDumpVersion } = await step('pg_dump', () =>
      deps.tools.dump(deps.databaseUrlDirect, dumpFile),
    );
    const dumpStat = await step('arquivo', () => stat(dumpFile));
    if (dumpStat.size <= 0) {
      throw new DrBackupError('arquivo', 'o dump está vazio');
    }
    if (dumpStat.size > deps.maxDumpBytes) {
      throw new DrBackupError(
        'arquivo',
        `o dump tem ${dumpStat.size} bytes, acima do teto SPARK_DR_MAX_DUMP_BYTES=${deps.maxDumpBytes}`,
      );
    }

    // --- 2. o arquivo abre, e o que ele contém ----------------------------------------------
    const toc = await step('pg_restore --list', () => deps.tools.listToc(dumpFile));
    if (toc.length === 0) {
      throw new DrBackupError('pg_restore --list', 'o dump não tem entradas no índice');
    }
    if (!toc.some((entry) => / TABLE DATA .* schema_migrations /.test(entry))) {
      throw new DrBackupError('pg_restore --list', 'o dump não contém schema_migrations');
    }

    const sha256 = await step('sha256', () => sha256OfFile(dumpFile));

    // --- 3. o que o servidor diz sobre si — versão e schema, do mesmo banco do dump -----------
    const server = await step('schema', () => describeServer(deps.databaseUrlDirect));

    // --- 4. upload + releitura --------------------------------------------------------------
    //
    // O buffer do upload vive só dentro de `uploadDump`: quando a releitura começa, ele já pode
    // ser recolhido. Sem isso o pico de memória do Job seria três cópias do dump (arquivo em
    // tmpfs, buffer de upload, buffer de releitura) — com 512 MiB isso é um OOM, não um backup.
    const uploadDump = async (): Promise<void> => {
      const dumpBytes = await readFile(dumpFile);
      await deps.store.writeDump(backupId, dumpBytes, sha256);
    };
    await step('upload do dump', uploadDump);

    const readBack = await step('releitura do dump', () => deps.store.readDump(backupId));
    if (readBack === null) {
      throw new DrBackupError('releitura do dump', 'o objeto recém-gravado não foi encontrado');
    }
    if (readBack.length !== dumpStat.size) {
      throw new DrBackupError('releitura do dump', 'tamanho lido difere do tamanho gravado');
    }
    const readBackSha = createHash('sha256').update(readBack).digest('hex');
    if (readBackSha !== sha256) {
      throw new DrBackupError('releitura do dump', 'SHA-256 lido difere do calculado');
    }

    // --- 5. o manifesto, por último ---------------------------------------------------------
    const manifest: DrBackupManifest = {
      formatVersion: DR_MANIFEST_FORMAT_VERSION,
      backupId,
      createdAt: new Date(startedAt).toISOString(),
      createdAtEpochMs: startedAt,
      databaseIdentity: identity,
      gitCommit: deps.gitCommit,
      imageDigest: deps.imageDigest,
      dumpFormat: 'pg_dump-custom',
      dumpObject: drDumpObjectName(backupId),
      dumpSizeBytes: dumpStat.size,
      sha256,
      postgresVersion: server.version,
      pgDumpVersion,
      tocEntries: toc.length,
      schema: {
        schemaVersion: server.schemaVersion,
        migrations: server.migrations,
        tables: server.tables,
      },
    };
    await step('upload do manifesto', () => deps.store.writeManifest(manifest));

    const manifestBack = await step('releitura do manifesto', () =>
      deps.store.readManifest(backupId),
    );
    if (manifestBack === null || manifestBack.backupId !== backupId) {
      throw new DrBackupError(
        'releitura do manifesto',
        'o manifesto não foi encontrado após o upload',
      );
    }
    if (manifestBack.sha256 !== sha256 || manifestBack.dumpSizeBytes !== dumpStat.size) {
      throw new DrBackupError(
        'releitura do manifesto',
        'o manifesto lido não descreve o dump gravado',
      );
    }

    // --- 6. retenção ------------------------------------------------------------------------
    const statuses = await step('retenção', () => deps.store.listStatuses());
    const plan = planDrRetention(statuses, deps.retentionCount);
    if (!plan.keep.some((kept) => kept.backupId === backupId)) {
      // Impossível por construção (o backup recém-validado é o mais novo); se acontecer, é um
      // defeito, e remover qualquer coisa seria agravá-lo.
      throw new DrBackupError('retenção', 'o backup recém-criado não está entre os mantidos');
    }
    let removed = 0;
    for (const old of plan.remove) {
      await step('retenção', () => deps.store.remove(old.backupId));
      removed += 1;
      deps.logger.info('db_backup_retention_removed', {
        ...logFields,
        removedBackupId: old.backupId,
      });
    }
    for (const ignored of plan.ignored) {
      deps.logger.warn('db_backup_retention_ignored', {
        ...logFields,
        ignoredBackupId: ignored.backupId,
        reason: ignored.kind,
      });
    }

    const durationMs = deps.clock.now() - startedAt;
    const result: DrBackupResult = {
      backupId,
      dumpSizeBytes: dumpStat.size,
      sha256,
      schemaVersion: server.schemaVersion,
      retention: { kept: plan.keep.length, removed, ignored: plan.ignored.length },
      durationMs,
    };
    deps.logger.info('db_backup_completed', {
      ...logFields,
      status: 'SUCCESS',
      durationMs,
      sizeBytes: dumpStat.size,
      sha256,
      schemaVersion: server.schemaVersion,
      objectKey: drDumpObjectName(backupId),
      retentionKept: plan.keep.length,
      retentionRemoved: removed,
      imageDigest: deps.imageDigest,
    });
    return result;
  } catch (error) {
    deps.logger.error('db_backup_failed', {
      ...logFields,
      status: 'FAILED',
      durationMs: deps.clock.now() - startedAt,
      step: error instanceof DrBackupError ? error.step : 'desconhecido',
      errorName: error instanceof Error ? error.name : 'UNKNOWN',
      // A mensagem é de ferramenta/etapa — nunca carrega a URL: `PgToolError` guarda só o stderr
      // da ferramenta, e a libpq não imprime a senha.
      errorMessage: error instanceof Error ? error.message : String(error),
    });
    throw error;
  } finally {
    await rm(workDir, { recursive: true, force: true }).catch(() => undefined);
  }
}

async function step<T>(name: string, work: () => Promise<T>): Promise<T> {
  try {
    return await work();
  } catch (error) {
    if (error instanceof DrBackupError) {
      throw error;
    }
    throw new DrBackupError(name, error instanceof Error ? error.message : String(error));
  }
}

function sha256OfFile(file: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256');
    createReadStream(file)
      .on('data', (chunk) => hash.update(chunk))
      .on('error', reject)
      .on('end', () => resolve(hash.digest('hex')));
  });
}

interface ServerDescription {
  readonly version: string;
  readonly schemaVersion: number;
  readonly migrations: DrBackupManifest['schema']['migrations'];
  readonly tables: string[];
}

/** Versão, migrations e tabelas — pelo driver, com a mesma política de TLS da API. */
async function describeServer(connectionUrl: string): Promise<ServerDescription> {
  const client = new Client({
    connectionString: normalizeSslMode(connectionUrl).connectionString,
    statement_timeout: 30_000,
  });
  await client.connect();
  try {
    const version = await client.query<{ version: string }>('SELECT version() AS version');
    const migrations = await client.query<{
      version: number;
      name: string;
      checksum: string | null;
    }>('SELECT version, name, checksum FROM schema_migrations ORDER BY version');
    const tables = await client.query<{ table_name: string }>(
      `SELECT table_name FROM information_schema.tables
       WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
       ORDER BY table_name`,
    );
    return {
      version: version.rows[0]?.version ?? 'desconhecida',
      schemaVersion: migrations.rows.at(-1)?.version ?? 0,
      migrations: migrations.rows.map((row) => ({
        version: Number(row.version),
        name: row.name,
        checksum: row.checksum,
      })),
      tables: tables.rows.map((row) => row.table_name),
    };
  } finally {
    await client.end().catch(() => undefined);
  }
}
