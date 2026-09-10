import 'reflect-metadata';
import { SparkLogger } from '../common/logger';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { PostgresService } from '../database/postgres.service';
import { LocalObjectStorageClient } from '../object-storage/local-object-storage.client';
import { ObjectAlreadyExistsError } from '../object-storage/object-storage.client';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';
import {
  SocialMediaRepository,
  type MediaMigrationRow,
} from '../modules/social/social-media.repository';
import {
  ObjectStorageSocialMediaStore,
  contentHashOf,
  type SocialMediaStore,
} from '../modules/social/social-media.store';

/** Quantas linhas cada lote lê do banco. Bounded: os arquivos são pequenos (≤ 1,5 MiB cada). */
const BATCH_SIZE = 50;

export interface SocialMediaMigrationReport {
  /** Objetos que subiram para o destino nesta execução. */
  readonly migrated: number;
  /** O objeto já existia no destino, idêntico: uma execução anterior já tinha migrado este. */
  readonly converged: number;
  /** Linhas recusadas — fail closed. Nem origem, nem destino foram alterados. */
  readonly failed: number;
}

/**
 * `migrate-social-media-to-object-storage` — copia os bytes de mídia social ainda só no disco
 * legado (`SOCIAL_MEDIA_ROOT`) para o bucket GCS configurado (T18.1.1, requisito 4).
 *
 * ```bash
 * SOCIAL_MEDIA_ROOT=/caminho/do/volume/legado \
 * OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=<nome-do-bucket> \
 *   node dist/cli/migrate-social-media-to-object-storage.js
 * ```
 *
 * ## Por que este comando existe, e o que a T18.1 não resolveu
 *
 * A T18.1 ensinou o backend a falar com dois providers (`local`/`gcs`), mas trocar
 * `OBJECT_STORAGE_PROVIDER=local` por `gcs` num deploy não move um byte: os arquivos que já
 * existiam em `SOCIAL_MEDIA_ROOT/checkins/…` continuam lá, e o backend passa a procurar tudo no
 * bucket — onde nada foi gravado ainda. Este comando é o passo manual e explícito entre os dois
 * estados.
 *
 * ## Por que a origem é PostgreSQL + disco, e não uma varredura do disco
 *
 * O PostgreSQL continua sendo a autoridade sobre o que existe, quem é dono e qual é o hash — listar
 * o diretório e subir tudo o que encontrar migraria lixo (upload abandonado nunca commitado) e não
 * saberia validar nada contra `content_hash`. A fonte é `SocialMediaRepository.listForMigration`
 * (§ requisito 4); o disco só entra para ler os bytes que a linha aponta.
 *
 * ## Por linha, nesta ordem, e nunca em outra
 *
 * ```text
 * storage_key (do banco)
 *     ↓ arquivo local correspondente
 *     ↓ ler bytes                                              ← ausente: FAIL CLOSED
 *     ↓ SHA-256 conferido contra content_hash                  ← divergência: FAIL CLOSED
 *     ↓ o objeto já existe no destino?
 *     │    sim, mesmo conteúdo   → converge (execução anterior já migrou este)
 *     │    sim, conteúdo outro   → FAIL CLOSED: nunca sobrescrever
 *     │    não                   → upload create-only
 *     ↓ leitura de volta do destino + SHA-256                  ← divergência: FAIL CLOSED
 *     ↓ sucesso operacional — nenhuma escrita no PostgreSQL
 * ```
 *
 * `storage_key` nunca muda: ele já era `checkins/xx/yy/<uuid>.webp` nos dois providers (T18.1
 * §namespaces), e a única coisa que se move são os bytes. Por isso este comando não escreve no
 * banco — não há coluna equivalente a `backup_snapshots.storage_key` para preencher.
 *
 * ## Fail closed, idempotente, retomável
 *
 * Parar no meio é seguro: a próxima execução relê a mesma página pelo cursor de `id`, e toda linha
 * já migrada converge sem reenviar nada — o teste de "objeto destino igual" é o que torna isso
 * barato. Uma linha recusada (`FAILED`) nunca é reprocessada na mesma execução, mas continua
 * aparecendo nas execuções seguintes até alguém investigar — o comando nunca a marca como resolvida
 * sozinho, porque não há onde marcar isso sem tocar no banco.
 *
 * ## O que este comando nunca faz
 *
 * Nunca apaga o arquivo de origem — a remoção do volume legado é uma etapa operacional posterior,
 * depois de o cutover ser validado (T18.1.1 requisito 4, "origem não é apagada automaticamente").
 * Nunca sobrescreve um objeto de destino incompatível. Nunca altera `storage_key` nem qualquer outra
 * coluna de `social_checkin_media`.
 */
export async function runSocialMediaMigration(): Promise<number> {
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

  // O destino precisa ser o bucket: migrar local → local não é o que este comando faz, e rodá-lo
  // assim quase sempre é um `OBJECT_STORAGE_PROVIDER` esquecido no ambiente do operador.
  if (config.objectStorageProvider !== 'gcs') {
    process.stderr.write(
      'OBJECT_STORAGE_PROVIDER precisa ser "gcs": este comando migra do disco legado para o bucket.\n',
    );
    return 1;
  }

  // A origem precisa ser declarada explicitamente. Sem isso, `socialMediaRoot` cai no diretório de
  // trabalho local (o fallback de desenvolvimento) — silenciosamente vazio, o que reportaria "nada
  // para migrar" quando a intenção era apontar para o volume legado de verdade.
  if (!config.socialMediaRootIsExplicit) {
    process.stderr.write(
      'SOCIAL_MEDIA_ROOT precisa apontar explicitamente para o volume legado a migrar.\n',
    );
    return 1;
  }

  const logger = new SparkLogger(config);
  const postgres = new PostgresService(config, logger);
  try {
    await postgres.initialize();

    // A origem é sempre o disco local — não é a factory quem decide isto, porque a migração
    // **é**, por definição, a ponte entre o provider antigo e o configurado. O destino, sim, passa
    // pela factory (§39): nunca um provider escolhido à mão para onde os bytes finais vão morar.
    const sourceStore: SocialMediaStore = new ObjectStorageSocialMediaStore(
      new LocalObjectStorageClient(config.socialMediaRoot),
    );
    const destinationClient = await createObjectStorageClient(config, logger);
    const destinationStore: SocialMediaStore = new ObjectStorageSocialMediaStore(destinationClient);
    const repository = new SocialMediaRepository(postgres);

    process.stdout.write(`object storage: origem=local destino=${destinationClient.provider}\n`);

    const report = await migrateSocialMedia(repository, sourceStore, destinationStore, (line) =>
      process.stdout.write(`${line}\n`),
    );

    process.stdout.write(
      `mídia migrada: ${report.migrated} | convergida: ${report.converged} | recusada: ${report.failed}\n`,
    );
    return report.failed > 0 ? 1 : 0;
  } finally {
    await postgres.onApplicationShutdown();
  }
}

type Outcome =
  | { readonly status: 'MIGRATED' | 'CONVERGED' }
  | { readonly status: 'FAILED'; readonly reason: string };

/**
 * O corpo do migrador, separado do processo para que o teste o exercite com o banco e os
 * armazenamentos de teste — e para que o comando e o teste sejam **o mesmo** código.
 */
export async function migrateSocialMedia(
  repository: SocialMediaRepository,
  source: SocialMediaStore,
  destination: SocialMediaStore,
  report: (line: string) => void = () => undefined,
): Promise<SocialMediaMigrationReport> {
  let migrated = 0;
  let converged = 0;
  let failed = 0;
  let cursor: string | null = null;

  for (;;) {
    const batch = await repository.listForMigration(cursor, BATCH_SIZE);
    if (batch.length === 0) {
      break;
    }
    cursor = batch[batch.length - 1].id;

    for (const row of batch) {
      const outcome = await migrateOne(source, destination, row);
      switch (outcome.status) {
        case 'MIGRATED':
          migrated += 1;
          break;
        case 'CONVERGED':
          converged += 1;
          break;
        case 'FAILED':
          failed += 1;
          // Metadata só: `id` é opaco, `ownerUid` nunca aparece em log (§ requisito 12).
          report(`recusado: mediaIdPrefix=${row.id.slice(0, 8)} motivo=${outcome.reason}`);
          break;
      }
    }
  }

  return { migrated, converged, failed };
}

async function migrateOne(
  source: SocialMediaStore,
  destination: SocialMediaStore,
  row: MediaMigrationRow,
): Promise<Outcome> {
  const bytes = await source.read(row.storageKey);
  if (bytes === null) {
    return { status: 'FAILED', reason: 'SOURCE_MISSING' };
  }
  if (bytes.length !== row.byteSize || contentHashOf(bytes) !== row.contentHash) {
    // O arquivo local não é o que a metadata descreve: nunca subir isto como se fosse.
    return { status: 'FAILED', reason: 'SOURCE_HASH_MISMATCH' };
  }

  const existing = await destination.read(row.storageKey);
  if (existing !== null) {
    if (existing.length !== row.byteSize || contentHashOf(existing) !== row.contentHash) {
      // Um objeto com este nome e outro conteúdo no destino: nunca sobrescrever.
      return { status: 'FAILED', reason: 'OBJECT_MISMATCH' };
    }
    return { status: 'CONVERGED' };
  }

  try {
    await destination.write(row.storageKey, bytes);
  } catch (error) {
    if (error instanceof ObjectAlreadyExistsError) {
      // Corrida com outra execução do migrador: o objeto nasceu entre a leitura e a escrita. A
      // próxima execução converge; esta fica fail closed.
      return { status: 'FAILED', reason: 'OBJECT_RACE' };
    }
    throw error;
  }

  // Confirmação pelo caminho de leitura, e não só pelo status do upload: o que o backend vai
  // servir depois do cutover precisa ser o que subiu.
  const written = await destination.read(row.storageKey);
  if (
    written === null ||
    written.length !== row.byteSize ||
    contentHashOf(written) !== row.contentHash
  ) {
    return { status: 'FAILED', reason: 'OBJECT_VERIFICATION' };
  }

  return { status: 'MIGRATED' };
}

/**
 * O comando só executa quando **é** o programa, e não quando é importado.
 */
if (require.main === module) {
  runSocialMediaMigration()
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
