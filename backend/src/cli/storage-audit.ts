import 'reflect-metadata';
import { SystemClock } from '../common/clock';
import { SparkLogger } from '../common/logger';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { PostgresService } from '../database/postgres.service';
import { DEFAULT_STORAGE_AUDITOR_OPTIONS, StorageAuditor } from '../dr/storage-auditor';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';

/**
 * `storage-audit` — o auditor PostgreSQL ↔ Object Storage, como comando (T18.3 §11).
 *
 * ```bash
 * DATABASE_URL=... OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=<bucket> node dist/cli/storage-audit.js
 * SPARK_AUDIT_HASH_SAMPLE=100 node dist/cli/storage-audit.js     # confere mais hashes
 * ```
 *
 * **Somente leitura.** Nada é apagado, corrigido ou migrado. O relatório (JSON) vai para stdout —
 * é o canal de dado, e é onde as chaves dos achados aparecem; o log (stderr) carrega só contagens
 * por classe. Código de saída: 0 sem achados (ou só `RECENT_UNREFERENCED`), 2 com achados, 1 em
 * falha de configuração/execução. `DATABASE_MIGRATION_MODE=verify` é forçado: um auditor nunca
 * aplica migration.
 *
 * Usa `AppConfig` como `reconcile-account-deletions`: a URL pooled basta para ler, e a factory de
 * Object Storage é a mesma do runtime — a auditoria olha exatamente o bucket que a API usa.
 * `missingRequirements()` não é consultado de propósito: este comando não precisa de HMAC, Gemini
 * nem Firebase, e exigi-los aqui só tornaria o Job mais privilegiado do que a leitura pede.
 */
export async function runStorageAuditCli(): Promise<number> {
  let config: AppConfig;
  try {
    config = AppConfig.fromEnv({ ...process.env, DATABASE_MIGRATION_MODE: 'verify' });
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const hashSampleRaw = process.env.SPARK_AUDIT_HASH_SAMPLE?.trim();
  const hashSampleSize =
    hashSampleRaw !== undefined && hashSampleRaw !== '' && /^\d+$/.test(hashSampleRaw)
      ? Number.parseInt(hashSampleRaw, 10)
      : DEFAULT_STORAGE_AUDITOR_OPTIONS.hashSampleSize;

  const logger = new SparkLogger(config);
  const storage = await createObjectStorageClient(config, logger);
  const postgres = new PostgresService(config, logger);
  try {
    await postgres.initialize();
    const auditor = new StorageAuditor(postgres, storage, new SystemClock(), logger, {
      ...DEFAULT_STORAGE_AUDITOR_OPTIONS,
      hashSampleSize,
    });
    const report = await auditor.audit();
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
    process.stdout.write(
      `STORAGE_AUDIT_${report.clean ? 'CLEAN' : 'ISSUES'} findings=${report.findings.length} durationMs=${report.durationMs}\n`,
    );
    return report.clean ? 0 : 2;
  } catch (error) {
    process.stderr.write(
      `STORAGE_AUDIT_FAILED ${error instanceof Error ? error.message : String(error)}\n`,
    );
    return 1;
  } finally {
    await postgres.onApplicationShutdown();
  }
}

if (require.main === module) {
  runStorageAuditCli()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `STORAGE_AUDIT_FAILED ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
