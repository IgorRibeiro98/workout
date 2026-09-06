import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { BackupController } from './backup.controller';
import { BackupRepository } from './backup.repository';
import { BackupService } from './backup.service';

/**
 * Módulo de backup (T16.4).
 *
 * Importa `AuthModule` porque **não existe rota de backup pública**: dado pessoal no Spark Backend
 * só se move com um Firebase ID Token verificado, e o dono sai dele.
 *
 * Nenhum provider aqui é substituível por configuração. Não há flag que desligue validação,
 * ownership ou retenção — as três são invariantes, não opções de deploy.
 */
@Module({
  imports: [AuthModule],
  controllers: [BackupController],
  providers: [BackupService, BackupRepository],
})
export class BackupModule {}
