import { Module } from '@nestjs/common';
import { DatabaseModule } from '../../database/database.module';
import { AuthModule } from '../auth/auth.module';
import { AccountDeletionRepository } from './account-deletion.repository';
import { AccountDeletionService } from './account-deletion.service';
import { AccountDeletionReconciler } from './account-deletion.reconciler';
import { AccountDeletionController } from './account-deletion.controller';

@Module({
  imports: [DatabaseModule, AuthModule],
  controllers: [AccountDeletionController],
  providers: [AccountDeletionRepository, AccountDeletionService, AccountDeletionReconciler],
  exports: [AccountDeletionService, AccountDeletionRepository],
})
export class AccountDeletionModule {}
