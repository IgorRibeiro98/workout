import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { AppConfig } from './config/app-config';
import { ConfigModule } from './config/config.module';
import { CommonModule } from './common/common.module';
import { HttpLoggerMiddleware } from './common/http-logger.middleware';
import { RequestIdMiddleware } from './common/request-id.middleware';
import { DatabaseModule } from './database/database.module';
import { AiModule } from './modules/ai/ai.module';
import { AuthModule } from './modules/auth/auth.module';
import { BackupModule } from './modules/backup/backup.module';
import { HealthModule } from './modules/health/health.module';
import { SyncModule } from './modules/sync/sync.module';

/**
 * Spark Backend — monólito modular.
 *
 * `auth` (T16.1), `ai` (T16.2), `backup` (T16.4/T16.5) e `sync` (T16.6) já estão aqui. As features
 * futuras da T16 e da T17 (`social`) entram como módulos aqui dentro, no mesmo processo e no mesmo
 * banco. Não há necessidade operacional que justifique separá-los em serviços: um grupo pequeno de
 * usuários, uma VPS, um deploy.
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
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    // A ordem importa: o request ID precisa existir antes de qualquer log ou resposta de erro.
    consumer.apply(RequestIdMiddleware, HttpLoggerMiddleware).forRoutes('*splat');
  }
}
