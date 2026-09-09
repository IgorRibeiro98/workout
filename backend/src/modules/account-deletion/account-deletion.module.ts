import { Module } from '@nestjs/common';
import { DatabaseModule } from '../../database/database.module';
import { AuthModule } from '../auth/auth.module';
import { AccountDeletionRepository } from './account-deletion.repository';
import { DeletionTombstoneLedger } from './deletion-tombstone.ledger';
import { AccountDeletionService } from './account-deletion.service';
import { AccountDeletionReconciler } from './account-deletion.reconciler';
import { AccountDeletionController } from './account-deletion.controller';
import { SocialModule } from '../social/social.module';

/**
 * A exclusão de conta (T17.6), que desde a T17.9 também apaga **arquivos**.
 *
 * `SocialModule` entra nos imports por uma razão só: o `SOCIAL_MEDIA_STORE`. O purge do SQLite
 * remove a metadata da mídia; os bytes vivem no volume, e só o store sabe traduzir uma chave em
 * caminho (§25). Sem este import, "conta excluída" significaria "conta excluída, exceto as fotos"
 * — que §195 lista como bloqueante.
 */
@Module({
  imports: [DatabaseModule, AuthModule, SocialModule],
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
