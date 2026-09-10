import { Module } from '@nestjs/common';
import { DatabaseModule } from '../../database/database.module';
import { AuthModule } from '../auth/auth.module';
import { AccountDeletionRepository } from './account-deletion.repository';
import { DeletionTombstoneLedger } from './deletion-tombstone.ledger';
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
    DeletionTombstoneLedger,
    AccountDeletionService,
    AccountDeletionReconciler,
  ],
  exports: [AccountDeletionService, AccountDeletionRepository, DeletionTombstoneLedger],
})
export class AccountDeletionModule {}
