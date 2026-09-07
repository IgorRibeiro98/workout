import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { SyncController } from './sync.controller';
import { SyncRateLimiter } from './sync.rate-limit';
import { SyncRepository } from './sync.repository';
import { SyncService } from './sync.service';

/**
 * Módulo de sincronização incremental (T16.6).
 *
 * Importa `AuthModule` porque **não existe rota de sync pública**: dado pessoal no Spark Backend
 * só se move com um Firebase ID Token verificado, e o dono sai dele.
 *
 * Nenhum provider aqui é substituível por configuração. Não há flag que desligue validação,
 * ownership, checagem de revision ou idempotência — as quatro são invariantes, não opções de
 * deploy.
 */
@Module({
  imports: [AuthModule],
  controllers: [SyncController],
  providers: [SyncService, SyncRepository, SyncRateLimiter],
})
export class SyncModule {}
