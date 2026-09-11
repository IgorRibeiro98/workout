import { Module } from '@nestjs/common';
import {
  OBJECT_STORAGE_CLIENT,
  type ObjectStorageClient,
} from '../../object-storage/object-storage.client';
import { AuthModule } from '../auth/auth.module';
import { BACKUP_PAYLOAD_STORE, ObjectStorageBackupPayloadStore } from './backup-payload.store';
import { BackupPayloadCleaner } from './backup-payload.cleaner';
import { BackupController } from './backup.controller';
import { BackupRateLimiter } from './backup.rate-limit';
import { BackupRepository } from './backup.repository';
import { BackupService } from './backup.service';

/**
 * Módulo de backup (T16.4).
 *
 * Importa `AuthModule` porque **não existe rota de backup pública**: dado pessoal no Spark Backend
 * só se move com um Firebase ID Token verificado, e o dono sai dele.
 *
 * Nenhum provider aqui é substituível por configuração. Não há flag que desligue validação,
 * ownership ou retenção — as três são invariantes, não opções de deploy. O que a configuração
 * escolhe é **onde** os bytes moram (`OBJECT_STORAGE_PROVIDER`), e isso acontece fora daqui.
 */
@Module({
  imports: [AuthModule],
  controllers: [BackupController],
  providers: [
    BackupService,
    BackupRepository,
    BackupRateLimiter,
    // T18.1 — o documento canônico vive no Object Storage do processo (disco local ou bucket
    // privado do GCS). Quem escolhe o provider é `object-storage.factory.ts`; aqui só existe o
    // adaptador de domínio, que conhece a forma da chave e o namespace `backups/`. Nenhum import
    // de `SocialModule`: o bucket é o mesmo, o domínio não.
    {
      provide: BACKUP_PAYLOAD_STORE,
      useFactory: (client: ObjectStorageClient) => new ObjectStorageBackupPayloadStore(client),
      inject: [OBJECT_STORAGE_CLIENT],
    },
    BackupPayloadCleaner,
  ],
  exports: [
    // A exclusão de conta (T17.6) precisa apagar os **objetos** de backup da conta (T18.1 §36):
    // o purge do PostgreSQL leva a metadata e não alcança o bucket.
    BACKUP_PAYLOAD_STORE,
    // T18.2 §33 — `spark-maintenance` chama `sweep()` diretamente, fora do timer.
    BackupPayloadCleaner,
  ],
})
export class BackupModule {}
