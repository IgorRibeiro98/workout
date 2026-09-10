import 'reflect-metadata';
import { createHash } from 'node:crypto';
import { SparkLogger } from '../common/logger';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { PostgresService } from '../database/postgres.service';
import {
  ObjectStorageBackupPayloadStore,
  type BackupPayloadStore,
} from '../modules/backup/backup-payload.store';
import { BackupRepository, type LegacySnapshot } from '../modules/backup/backup.repository';
import { ObjectAlreadyExistsError } from '../object-storage/object-storage.client';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';

/** Quantos snapshots cada lote lê do banco. Bounded: o documento de cada um cabe em 4 MiB. */
const BATCH_SIZE = 50;

export interface MigrationReport {
  /** Snapshots que passaram a apontar para um objeto nesta execução. */
  readonly migrated: number;
  /** O objeto já existia, idêntico: o banco só foi atualizado. Uma execução anterior parou aqui. */
  readonly converged: number;
  /** Snapshots recusados — fail closed. Nada deles foi alterado. */
  readonly failed: number;
  /** Snapshots sem documento em lugar nenhum (anteriores à T16.5). Não há o que migrar. */
  readonly withoutDocument: number;
}

/**
 * `migrate-backup-payloads-to-object-storage` — move o documento canônico dos backups anteriores
 * à T18.1 do PostgreSQL para o Object Storage (T18.1 §32–§35).
 *
 * ```bash
 * node dist/cli/migrate-backup-payloads-to-object-storage.js
 * ```
 *
 * ## Por snapshot, nesta ordem, e nunca em outra
 *
 * ```text
 * payload legado (texto, verbatim)
 *     ↓ SHA-256 e tamanho conferidos contra a metadata          ← divergência: FAIL CLOSED
 *     ↓ o objeto já existe?
 *     │    sim, com o mesmo hash e tamanho  → converge (uma execução anterior parou aqui)
 *     │    sim, diferente                   → FAIL CLOSED: nunca sobrescrever
 *     │    não                              → upload (create-only, integridade do SDK)
 *     ↓ leitura de volta + SHA-256                                ← divergência: FAIL CLOSED
 *     ↓ transação: storage_key = …, backup_snapshots.payload = NULL, backup_items.payload = NULL
 * ```
 *
 * O texto legado só sai do banco **depois** de o objeto estar gravado e conferido (§34). A
 * ordem inversa — apagar e então tentar subir — é o desenho em que uma falha de rede vira um
 * backup que existe como metadata e não pode mais ser restaurado.
 *
 * ## Idempotente e retomável (§33)
 *
 * Parar no meio é seguro: um snapshot já migrado não aparece mais na consulta
 * (`storage_key IS NULL AND payload IS NOT NULL`), e um snapshot cujo objeto subiu mas cujo
 * banco não foi atualizado converge na execução seguinte pelo hash. Rodar de novo sem nada a
 * fazer termina com zero em tudo e código 0.
 *
 * ## Fail closed
 *
 * Um snapshot cujo texto não fecha com o próprio `payload_hash`, ou cujo objeto já existe com
 * outro conteúdo, é reportado e **deixado como está**: o migrador termina com código 1, e o
 * restore daquele snapshot continua funcionando pelo caminho legado até alguém olhar. Nunca se
 * sobrescreve um objeto incompatível, nunca se apaga um texto que não foi provado no bucket.
 */
export async function runBackupPayloadMigration(): Promise<number> {
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

  const missing = config.missingRequirements();
  if (missing.length > 0) {
    process.stderr.write(
      `Configuração incompleta:\n${missing.map((m) => `  - ${m}`).join('\n')}\n`,
    );
    return 1;
  }

  const logger = new SparkLogger(config);
  const postgres = new PostgresService(config, logger);
  try {
    await postgres.initialize();
    // O mesmo provider do runtime (§39): nunca um `new Local...` aqui.
    const objectStorage = await createObjectStorageClient(config, logger);
    const payloads = new ObjectStorageBackupPayloadStore(objectStorage);
    const repository = new BackupRepository(postgres);

    process.stdout.write(`object storage: provider=${objectStorage.provider}\n`);

    const report = await migrateLegacyPayloads(repository, payloads, (line) =>
      process.stdout.write(`${line}\n`),
    );

    process.stdout.write(
      `snapshots migrados: ${report.migrated} | convergidos: ${report.converged} | ` +
        `recusados: ${report.failed} | sem documento (pré-T16.5): ${report.withoutDocument}\n`,
    );
    return report.failed > 0 ? 1 : 0;
  } finally {
    await postgres.onApplicationShutdown();
  }
}

/**
 * O corpo do migrador, separado do processo para que o teste o exercite com o banco e o
 * armazenamento de teste — e para que o comando e o teste sejam **o mesmo** código.
 */
export async function migrateLegacyPayloads(
  repository: BackupRepository,
  payloads: BackupPayloadStore,
  report: (line: string) => void = () => undefined,
): Promise<MigrationReport> {
  let migrated = 0;
  let converged = 0;
  let failed = 0;
  // Os recusados não saem da consulta; sem esta lista o laço os releria para sempre.
  const refused = new Set<number>();

  for (;;) {
    const batch = (await repository.listLegacySnapshots(BATCH_SIZE + refused.size)).filter(
      (snapshot) => !refused.has(snapshot.sequence),
    );
    if (batch.length === 0) {
      break;
    }

    for (const snapshot of batch) {
      const outcome = await migrateOne(repository, payloads, snapshot);
      switch (outcome.status) {
        case 'MIGRATED':
          migrated += 1;
          break;
        case 'CONVERGED':
          converged += 1;
          break;
        case 'SKIPPED':
          // Outro processo migrou, ou a retenção removeu o snapshot no meio: nada a fazer.
          break;
        case 'FAILED':
          failed += 1;
          refused.add(snapshot.sequence);
          // Metadata só: `backupId` é opaco do servidor; nunca o conteúdo, nunca o dono.
          report(`recusado: backupId=${snapshot.backupId} motivo=${outcome.reason}`);
          break;
      }
    }
  }

  const withoutDocument = await repository.countWithoutAnyPayload();
  return { migrated, converged, failed, withoutDocument };
}

type Outcome =
  | { readonly status: 'MIGRATED' | 'CONVERGED' | 'SKIPPED' }
  | { readonly status: 'FAILED'; readonly reason: string };

async function migrateOne(
  repository: BackupRepository,
  payloads: BackupPayloadStore,
  snapshot: LegacySnapshot,
): Promise<Outcome> {
  const text = await repository.findLegacyPayload(snapshot.sequence);
  if (text === null) {
    return { status: 'SKIPPED' };
  }

  // Os bytes que o hash resume (§22): UTF-8 do texto guardado verbatim pela T16.5.
  const bytes = Buffer.from(text, 'utf8');
  if (bytes.length !== snapshot.sizeBytes || sha256(bytes) !== snapshot.payloadHash) {
    return { status: 'FAILED', reason: 'LEGACY_PAYLOAD_INTEGRITY' };
  }

  const storageKey = payloads.newStorageKey(snapshot.backupId);

  const existing = await payloads.read(storageKey);
  if (existing !== null) {
    if (existing.length !== snapshot.sizeBytes || sha256(existing) !== snapshot.payloadHash) {
      // Um objeto com este nome e outro conteúdo: nunca sobrescrever (§33).
      return { status: 'FAILED', reason: 'OBJECT_MISMATCH' };
    }
    return (await repository.markMigrated(snapshot.sequence, storageKey))
      ? { status: 'CONVERGED' }
      : { status: 'SKIPPED' };
  }

  try {
    await payloads.write(storageKey, bytes, { sha256: snapshot.payloadHash });
  } catch (error) {
    if (error instanceof ObjectAlreadyExistsError) {
      // Corrida com outra execução do migrador: o objeto nasceu entre a leitura e a escrita. A
      // próxima execução converge; nesta, fail closed.
      return { status: 'FAILED', reason: 'OBJECT_RACE' };
    }
    throw error;
  }

  // Confirmação pelo caminho de leitura, e não só pelo status do upload (§32): o que o restore
  // vai baixar precisa ser o que subiu.
  const written = await payloads.read(storageKey);
  if (
    written === null ||
    written.length !== snapshot.sizeBytes ||
    sha256(written) !== snapshot.payloadHash
  ) {
    return { status: 'FAILED', reason: 'OBJECT_VERIFICATION' };
  }

  return (await repository.markMigrated(snapshot.sequence, storageKey))
    ? { status: 'MIGRATED' }
    : { status: 'SKIPPED' };
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

/**
 * O comando só executa quando **é** o programa, e não quando é importado.
 */
if (require.main === module) {
  runBackupPayloadMigration()
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
