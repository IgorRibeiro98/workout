import 'reflect-metadata';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { SparkLogger } from '../common/logger';
import { FileDeletionTombstoneLedger } from '../modules/account-deletion/file-deletion-tombstone.ledger';
import { ObjectStorageDeletionTombstoneLedger } from '../modules/account-deletion/object-storage-deletion-tombstone.ledger';
import { DeletionTombstoneLedgerError } from '../modules/account-deletion/deletion-tombstone-ledger.port';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';

/**
 * `migrate:deletion-ledger` — move o ledger anti-ressurreição do disco legado para o Object
 * Storage, antes do cutover para Cloud Run (T18.2 §30/§31).
 *
 * ## O que ele faz, e o que ele nunca faz
 *
 * Lê `deletion_tombstones.tsv` (o caminho de `DELETION_TOMBSTONES_FILE_PATH`) inteiro e valida
 * cada linha com a mesma régua estrita de sempre — uma linha malformada aborta a migração inteira,
 * e nada é escrito no bucket. Para cada hash distinto, grava o tombstone equivalente no ledger de
 * Object Storage (`ObjectStorageDeletionTombstoneLedger.appendDurably`, que já converge se o
 * objeto já existir — rodar de novo é seguro).
 *
 * **Nunca apaga, trunca ou modifica o arquivo de origem.** O cutover para `OBJECT_STORAGE_PROVIDER
 * =gcs` é uma etapa de deploy separada; até ela acontecer, o arquivo legado continua sendo a
 * autoridade em produção, e esta migração é só a preparação do destino.
 *
 * ## Idempotência
 *
 * Rodar de novo depois de uma falha no meio (rede, quota) converge: os hashes já presentes no
 * bucket são confirmados de novo sem erro, e só os que faltam são gravados.
 *
 * ```bash
 * OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=... DELETION_TOMBSTONES_FILE_PATH=/data/deletion_tombstones.tsv \
 *   node dist/cli/migrate-deletion-ledger-to-object-storage.js
 * ```
 */
export async function runDeletionLedgerMigration(): Promise<number> {
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

  if (config.objectStorageProvider !== 'gcs') {
    process.stderr.write(
      'migrate:deletion-ledger exige o provider de bucket ativo — o ambiente atual está configurado para o disco local, e não há destino no bucket para migrar.\n',
    );
    return 1;
  }

  const logger = new SparkLogger(config);
  const objectStorage = await createObjectStorageClient(config, logger);
  const source = new FileDeletionTombstoneLedger(config);

  let entries;
  try {
    entries = await source.readEntries();
  } catch (error) {
    if (error instanceof DeletionTombstoneLedgerError) {
      process.stderr.write(`migração abortada: ${error.message}\n  local: ${source.location}\n`);
      return 2;
    }
    throw error;
  }

  const byHash = new Map<string, number>();
  for (const entry of entries) {
    if (!byHash.has(entry.hash)) {
      byHash.set(entry.hash, entry.deletedAt);
    }
  }
  const duplicates = entries.length - byHash.size;

  process.stdout.write(
    `ledger de origem: ${entries.length} linha(s), ${byHash.size} hash(es) distinto(s), ${duplicates} duplicata(s)\n`,
  );

  const target = new ObjectStorageDeletionTombstoneLedger(objectStorage);

  let alreadyPresent = 0;
  let migrated = 0;
  const { hashes: existing } = await target.readHashes();

  for (const [hash, deletedAt] of byHash) {
    const wasPresent = existing.has(hash);
    try {
      await target.appendDurably(hash, deletedAt);
    } catch (error) {
      process.stderr.write(
        `migração abortada ao gravar um tombstone: ${
          error instanceof Error ? error.message : String(error)
        }\n` + `hashes migrados antes da falha: ${migrated}; execute de novo, ela converge.\n`,
      );
      return 1;
    }
    if (wasPresent) {
      alreadyPresent += 1;
    } else {
      migrated += 1;
    }
  }

  process.stdout.write(
    `migração concluída: ${migrated} tombstone(s) novo(s), ${alreadyPresent} já presente(s) no destino\n`,
  );
  process.stdout.write(`origem preservada: ${source.location}\n`);
  return 0;
}

if (require.main === module) {
  runDeletionLedgerMigration()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `migração abortada: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
