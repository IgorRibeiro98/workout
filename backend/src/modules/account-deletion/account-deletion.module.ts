import { Module } from '@nestjs/common';
import { DatabaseModule } from '../../database/database.module';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import {
  OBJECT_STORAGE_CLIENT,
  type ObjectStorageClient,
} from '../../object-storage/object-storage.client';
import { AuthModule } from '../auth/auth.module';
import { AccountDeletionRepository } from './account-deletion.repository';
import { DELETION_TOMBSTONE_LEDGER } from './deletion-tombstone-ledger.port';
import { createDeletionTombstoneLedger } from './deletion-tombstone-ledger.factory';
import { AccountDeletionService } from './account-deletion.service';
import { AccountDeletionReconciler } from './account-deletion.reconciler';
import { AccountDeletionController } from './account-deletion.controller';
import { SocialModule } from '../social/social.module';
import { BackupModule } from '../backup/backup.module';

/**
 * A exclusão de conta (T17.6), que desde a T17.9 também apaga **arquivos** — e desde a T18.1,
 * **objetos**: fotos e documentos de backup, no disco ou no bucket.
 *
 * `SocialModule` entra nos imports por uma razão só: o `SOCIAL_MEDIA_STORE`. `BackupModule`, pela
 * razão simétrica: o `BACKUP_PAYLOAD_STORE`. O purge do banco remove a metadata; os bytes vivem
 * no Object Storage, e só cada store sabe traduzir a sua chave em nome de objeto. Sem estes
 * imports, "conta excluída" significaria "conta excluída, exceto as fotos e os backups" — que a
 * T17.9 §195 e a T18.1 listam como bloqueante. É este módulo que conhece os dois; nenhum dos dois
 * conhece o outro.
 */
@Module({
  imports: [DatabaseModule, AuthModule, SocialModule, BackupModule],
  controllers: [AccountDeletionController],
  providers: [
    AccountDeletionRepository,
    {
      provide: DELETION_TOMBSTONE_LEDGER,
      useFactory: (config: AppConfig, objectStorage: ObjectStorageClient) =>
        createDeletionTombstoneLedger(config, objectStorage),
      inject: [APP_CONFIG, OBJECT_STORAGE_CLIENT],
    },
    AccountDeletionService,
    AccountDeletionReconciler,
  ],
  exports: [
    AccountDeletionService,
    AccountDeletionRepository,
    DELETION_TOMBSTONE_LEDGER,
    // T18.2 §33 — `spark-maintenance` chama `processDueJobs()` diretamente, fora do timer.
    AccountDeletionReconciler,
  ],
})
export class AccountDeletionModule {}
