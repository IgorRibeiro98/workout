import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { AppConfig } from './config/app-config';
import { ConfigModule } from './config/config.module';
import { CommonModule } from './common/common.module';
import { HttpLoggerMiddleware } from './common/http-logger.middleware';
import { RequestIdMiddleware } from './common/request-id.middleware';
import { DatabaseModule } from './database/database.module';
import { AuthModule } from './modules/auth/auth.module';
import { HealthModule } from './modules/health/health.module';

/**
 * Spark Backend — monólito modular.
 *
 * `auth` (T16.1) já está aqui. As features futuras da T16 (`ai`, `sync`, `backup`) e da T17
 * (`social`) entram como módulos aqui dentro, no mesmo processo e no mesmo banco. Não há necessidade operacional que
 * justifique separá-los em serviços: um grupo pequeno de usuários, uma VPS, um deploy.
 */
@Module({
  imports: [CommonModule, DatabaseModule, HealthModule, AuthModule],
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
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    // A ordem importa: o request ID precisa existir antes de qualquer log ou resposta de erro.
    consumer.apply(RequestIdMiddleware, HttpLoggerMiddleware).forRoutes('*splat');
  }
}
