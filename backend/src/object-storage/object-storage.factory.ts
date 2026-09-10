import { SparkLogger } from '../common/logger';
import { AppConfig } from '../config/app-config';
import { LocalObjectStorageClient } from './local-object-storage.client';
import type { ObjectStorageClient } from './object-storage.client';

/** Configuração de Object Storage inconsistente. Derruba o startup — nunca um fallback. */
export class ObjectStorageConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ObjectStorageConfigurationError';
  }
}

/**
 * O **único** ponto do processo que escolhe entre `local` e `gcs` (T18.1 §39).
 *
 * O módulo Nest (`ObjectStorageModule`) e os comandos operacionais (`reconcile-account-deletions`,
 * `migrate-backup-payloads-to-object-storage`, `object-storage-smoke`) passam por aqui. É o que
 * garante que produção e reconciliação de DR falam com o **mesmo** provider: um CLI que
 * instanciasse o provider local por conta própria purgaria o disco de um container enquanto as
 * fotos continuassem no bucket.
 *
 * O provider `gcs` é carregado por import dinâmico, de propósito: com `local` — a suíte, o CI, o
 * `start:dev` — o SDK do Google nem entra no grafo de módulos. "Os testes não exigem GCP" passa a
 * ser verificável (`test/object-storage-structure.spec.ts`), e não uma promessa.
 */
export async function createObjectStorageClient(
  config: AppConfig,
  logger: SparkLogger,
): Promise<ObjectStorageClient> {
  switch (config.objectStorageProvider) {
    case 'local': {
      logger.info('object_storage.ready', { provider: 'local' });
      return new LocalObjectStorageClient(config.socialMediaRoot);
    }
    case 'gcs': {
      const bucketName = config.gcsBucketName;
      if (bucketName === undefined) {
        // `AppConfig.missingRequirements()` já derruba o bootstrap antes disto; a checagem aqui é
        // a segunda barreira, para quem montar o processo por outro caminho.
        throw new ObjectStorageConfigurationError(
          'OBJECT_STORAGE_PROVIDER=gcs exige GCS_BUCKET_NAME',
        );
      }
      const { GcsObjectStorageClient } = await import('./gcs-object-storage.client');
      logger.info('object_storage.ready', {
        provider: 'gcs',
        timeoutMs: config.objectStorageTimeoutMs,
      });
      return new GcsObjectStorageClient(
        { bucketName, timeoutMs: config.objectStorageTimeoutMs },
        logger,
      );
    }
  }
}
