import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { AppConfig } from './config/app-config';
import { ConfigModule } from './config/config.module';
import { CommonModule } from './common/common.module';
import { HttpLoggerMiddleware } from './common/http-logger.middleware';
import { MaintenanceMiddleware } from './common/maintenance.middleware';
import { SecurityHeadersMiddleware } from './common/security-headers.middleware';
import { RequestIdMiddleware } from './common/request-id.middleware';
import { DatabaseModule } from './database/database.module';
import { AiModule } from './modules/ai/ai.module';
import { AuthModule } from './modules/auth/auth.module';
import { BackupModule } from './modules/backup/backup.module';
import { HealthModule } from './modules/health/health.module';
import { SocialModule } from './modules/social/social.module';
import { SyncModule } from './modules/sync/sync.module';
import { AccountDeletionModule } from './modules/account-deletion/account-deletion.module';

/**
 * Spark Backend — monólito modular.
 *
 * `auth` (T16.1), `ai` (T16.2), `backup` (T16.4/T16.5), `sync` (T16.6) e `social` (T17.0) estão
 * aqui, no mesmo processo e no mesmo banco. Não há necessidade operacional que justifique
 * separá-los em serviços: um grupo pequeno de usuários, uma VPS, um deploy.
 *
 * Estar no mesmo processo **não** os torna acoplados: `SocialModule` não importa `BackupModule`
 * nem `SyncModule`, e a fronteira entre o domínio privado e o social é a `SocialProjection`
 * (`modules/social/social.projection.ts`), não a proximidade dos arquivos.
 */
@Module({
  imports: [
    CommonModule,
    DatabaseModule,
    HealthModule,
    AuthModule,
    AiModule,
    BackupModule,
    SyncModule,
    SocialModule,
    AccountDeletionModule,
  ],
})
export class AppModule implements NestModule {
  static forRoot(config: AppConfig) {
    return {
      module: AppModule,
      imports: [
        ConfigModule.forRoot(config),
        CommonModule,
        DatabaseModule,
        HealthModule,
        AuthModule,
        AiModule,
        BackupModule,
        SyncModule,
        SocialModule,
        AccountDeletionModule,
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    // A ordem importa, e cada posição tem razão:
    //
    // 1. `RequestIdMiddleware` — o request ID precisa existir antes de qualquer log ou resposta de
    //    erro, inclusive a de manutenção.
    // 2. `SecurityHeadersMiddleware` — headers antes de qualquer resposta ser escrita, inclusive
    //    as que terminam aqui mesmo.
    // 3. `HttpLoggerMiddleware` — uma requisição recusada por manutenção também precisa aparecer
    //    no log de acesso; por isso ele vem **antes** do interruptor, não depois.
    // 4. `MaintenanceMiddleware` — o último, e o único que pode encerrar a requisição sem chegar
    //    ao controller. `/health/*` passa por ele intocado.
    consumer
      .apply(
        RequestIdMiddleware,
        SecurityHeadersMiddleware,
        HttpLoggerMiddleware,
        MaintenanceMiddleware,
      )
      .forRoutes('*splat');
  }
}
