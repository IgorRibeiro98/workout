import 'reflect-metadata';
import { join } from 'node:path';
import { SystemClock } from '../common/clock';
import { SparkLogger } from '../common/logger';
import { DrJobConfigError, DrRestoreDrillConfig } from '../config/dr-job-config';
import { MIGRATIONS_DIRNAME } from '../database/database.constants';
import { PostgresUrlIdentityError } from '../database/postgres-url';
import { runDrRestoreDrill } from '../dr/db-restore-drill.runner';
import { DrBackupStore } from '../dr/dr-backup.store';
import { ProcessPgTools } from '../dr/pg-tools';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';

/**
 * `db-restore-drill` — o ensaio de restauração em destino limpo (T18.3 §5/§6).
 *
 * ```bash
 * OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=<bucket> \
 * SPARK_DRILL_ADMIN_URL=postgresql://spark:spark@127.0.0.1:5432/postgres \
 * SPARK_DRILL_DATABASE=spark_drill_20260911 \
 *   node dist/cli/db-restore-drill.js            # o backup válido mais recente
 * SPARK_DR_BACKUP_ID=2026-09-11T120000Z ...      # um backup específico
 * ```
 *
 * A última linha da saída é o veredito: `RESTORE_DRILL_PASS` (código 0) ou `RESTORE_DRILL_FAIL`
 * (código 1). Nunca conecta a produção: não existe `DATABASE_URL` nem `DATABASE_URL_DIRECT` na
 * configuração deste comando — só o bucket e o servidor do ensaio.
 */
export async function runDbRestoreDrillCli(): Promise<number> {
  let config: DrRestoreDrillConfig;
  try {
    config = DrRestoreDrillConfig.fromEnv();
  } catch (error) {
    if (error instanceof DrJobConfigError) {
      process.stderr.write(`${error.message}\n`);
      process.stdout.write('RESTORE_DRILL_FAIL configuração inválida\n');
      return 1;
    }
    throw error;
  }

  const logger = new SparkLogger(config);
  const storage = await createObjectStorageClient(config, logger);
  try {
    const result = await runDrRestoreDrill({
      store: new DrBackupStore(storage),
      tools: new ProcessPgTools(),
      clock: new SystemClock(),
      logger,
      workDir: config.workDir,
      backupId: config.backupId,
      adminUrl: config.adminUrl,
      drillDatabase: config.drillDatabase,
      keepDatabase: config.keepDatabase,
      replaceExisting: config.replaceExisting,
      migrationsDirectory: join(__dirname, '..', '..', MIGRATIONS_DIRNAME),
    });
    process.stdout.write(`${JSON.stringify(result)}\n`);
    process.stdout.write(
      `RESTORE_DRILL_PASS backupId=${result.backupId} drillDatabase=${result.drillDatabase} ` +
        `restoredSchemaVersion=${result.restoredSchemaVersion} finalSchemaVersion=${result.finalSchemaVersion} ` +
        `tables=${result.tables} durationMs=${result.durationMs}\n`,
    );
    return 0;
  } catch (error) {
    const reason =
      error instanceof PostgresUrlIdentityError
        ? `identidade do banco: ${error.message}`
        : error instanceof Error
          ? error.message
          : String(error);
    process.stderr.write(`${reason}\n`);
    process.stdout.write(`RESTORE_DRILL_FAIL ${reason}\n`);
    return 1;
  }
}

if (require.main === module) {
  runDbRestoreDrillCli()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
      process.stdout.write('RESTORE_DRILL_FAIL erro inesperado\n');
      process.exitCode = 1;
    });
}
