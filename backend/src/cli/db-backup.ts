import 'reflect-metadata';
import { SystemClock } from '../common/clock';
import { SparkLogger } from '../common/logger';
import { DrJobConfig, DrJobConfigError } from '../config/dr-job-config';
import { PostgresUrlIdentityError } from '../database/postgres-url';
import { runDrBackup } from '../dr/db-backup.runner';
import { DrBackupStore } from '../dr/dr-backup.store';
import { ProcessPgTools } from '../dr/pg-tools';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';

/**
 * `db-backup` — o Job `spark-db-backup` (T18.3 §2/§3/§8).
 *
 * ```bash
 * DATABASE_URL_DIRECT=postgres://... OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=<bucket> \
 *   node dist/cli/db-backup.js
 * ```
 *
 * Não usa `AppConfig` — ver `DrJobConfig`: este processo só conhece a URL direta, o destino dos
 * objetos, a retenção e a proveniência. Sem `DATABASE_URL_DIRECT` sai com código 1 sem tocar em
 * nada; com uma URL sem database explícito, idem (`PostgresUrlIdentityError`). Código 0 só depois
 * de `db_backup_completed` — dump, hash, upload, releitura, manifesto e retenção, nessa ordem.
 */
export async function runDbBackupCli(): Promise<number> {
  let config: DrJobConfig;
  try {
    config = DrJobConfig.fromEnv();
  } catch (error) {
    if (error instanceof DrJobConfigError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const logger = new SparkLogger(config);
  const storage = await createObjectStorageClient(config, logger);
  try {
    const result = await runDrBackup({
      databaseUrlDirect: config.databaseUrlDirect,
      store: new DrBackupStore(storage),
      tools: new ProcessPgTools(),
      clock: new SystemClock(),
      logger,
      retentionCount: config.retentionCount,
      workDir: config.workDir,
      maxDumpBytes: config.maxDumpBytes,
      gitCommit: config.gitCommit,
      imageDigest: config.imageDigest,
    });
    process.stdout.write(
      `DB_BACKUP_OK backupId=${result.backupId} sizeBytes=${result.dumpSizeBytes} sha256=${result.sha256} ` +
        `schemaVersion=${result.schemaVersion} kept=${result.retention.kept} removed=${result.retention.removed} ` +
        `ignored=${result.retention.ignored} durationMs=${result.durationMs}\n`,
    );
    return 0;
  } catch (error) {
    // `db_backup_failed` já foi logado com a etapa. Aqui só o veredito, para quem lê o Job.
    const reason =
      error instanceof PostgresUrlIdentityError
        ? `identidade do banco: ${error.message}`
        : error instanceof Error
          ? error.message
          : String(error);
    process.stderr.write(`DB_BACKUP_FAILED ${reason}\n`);
    return 1;
  }
}

if (require.main === module) {
  runDbBackupCli()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `DB_BACKUP_FAILED ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
