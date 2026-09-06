import { INestApplication, VersioningType } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppModule } from '../app.module';
import { AllExceptionsFilter } from '../common/all-exceptions.filter';
import { SparkLogger } from '../common/logger';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { SqliteService } from '../database/sqlite.service';

export interface CreatedApp {
  readonly app: INestApplication;
  readonly config: AppConfig;
  readonly sqlite: SqliteService;
  readonly logger: SparkLogger;
}

/**
 * Monta a aplicação sem escutar em porta nenhuma.
 *
 * Separado de `main.ts` para que os testes exercitem exatamente a mesma montagem que a produção —
 * mesmos middlewares, mesmo filtro de erro, mesmo versionamento — em vez de uma aproximação.
 */
export async function createApp(config: AppConfig): Promise<CreatedApp> {
  const app = await NestFactory.create(AppModule.forRoot(config), {
    // O log da aplicação passa pelo SparkLogger; o logger padrão do Nest fica só com bootstrap.
    logger: config.nodeEnv === 'test' ? false : ['error', 'warn'],
    bufferLogs: true,
  });

  // Nada de anunciar o framework para quem não precisa saber.
  app.getHttpAdapter().getInstance().disable('x-powered-by');

  // Toda API de produto nasce sob `/v1`: um `@Controller('sync')` futuro responde em `/v1/sync`
  // sem que ninguém precise lembrar de escrever o prefixo. Health é VERSION_NEUTRAL.
  app.enableVersioning({
    type: VersioningType.URI,
    prefix: 'v',
    defaultVersion: '1',
  });

  const sqlite = app.get(SqliteService);
  sqlite.initialize();

  const logger = app.get(SparkLogger);
  app.useGlobalFilters(new AllExceptionsFilter(app.get<AppConfig>(APP_CONFIG), logger));

  // Fecha o SQLite em SIGTERM/SIGINT antes de o processo sair.
  app.enableShutdownHooks();

  return { app, config, sqlite, logger };
}
