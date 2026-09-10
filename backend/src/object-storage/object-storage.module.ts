import { Global, Module } from '@nestjs/common';
import { SparkLogger } from '../common/logger';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { OBJECT_STORAGE_CLIENT } from './object-storage.client';
import { createObjectStorageClient } from './object-storage.factory';

/**
 * A infraestrutura de Object Storage do processo (T18.1).
 *
 * `@Global`, como `DatabaseModule`: onde os bytes moram não pertence a domínio nenhum, e os dois
 * que precisam deles — `SocialModule` (fotos) e `BackupModule` (documentos canônicos) — recebem o
 * **mesmo** cliente sem que um importe o outro. A escolha do provider mora na factory, e só lá.
 */
@Global()
@Module({
  providers: [
    {
      provide: OBJECT_STORAGE_CLIENT,
      useFactory: (config: AppConfig, logger: SparkLogger) =>
        createObjectStorageClient(config, logger),
      inject: [APP_CONFIG, SparkLogger],
    },
  ],
  exports: [OBJECT_STORAGE_CLIENT],
})
export class ObjectStorageModule {}
