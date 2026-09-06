import { INestApplication, VersioningType } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppModule } from '../app.module';
import { AllExceptionsFilter } from '../common/all-exceptions.filter';
import { SparkLogger } from '../common/logger';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { SqliteService } from '../database/sqlite.service';
import { MAX_BACKUP_REQUEST_BODY_BYTES } from '../modules/backup/backup.limits';
import type { RequestWithRawBody } from '../common/raw-body';

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

  return configureApp(app, config);
}

/**
 * A configuração da instância — versionamento, banco, filtro de erro, shutdown hooks.
 *
 * Extraída de [createApp] para que o teste que precisa trocar **um** provedor (o verificador de
 * token, na T16.1) monte a aplicação pelo `@nestjs/testing` e ainda assim receba exatamente esta
 * configuração, em vez de uma reimplementação que envelhece em paralelo.
 */
export function configureApp(app: INestApplication, config: AppConfig): CreatedApp {
  // Nada de anunciar o framework para quem não precisa saber.
  app.getHttpAdapter().getInstance().disable('x-powered-by');

  // Teto de corpo declarado, e não herdado do default do Express: o backend não pode supor que só
  // o APK oficial faz requisições (T16.2 §46).
  //
  // O teto global é o do **backup**, que é a maior coisa que o Spark envia — um snapshot completo
  // do estado pessoal. O Coach continua com o teto dele, bem menor, aplicado no próprio módulo:
  // um teto global apertado o bastante para o Coach recusaria backups legítimos, e um teto único
  // largo deixaria o Coach aceitar payload que ele não tem por que aceitar.
  //
  // `verify` guarda o corpo cru para o hash canônico do backup (T16.4). Ele nunca vai para log:
  // o `SparkLogger` redige `req.body`/`body` e nenhum ponto do código registra `rawBody`.
  const withBodyParser = app as INestApplication & {
    useBodyParser?: (
      parser: 'json',
      options: {
        limit: number;
        verify?: (req: unknown, res: unknown, buf: Buffer, encoding: string) => void;
      },
    ) => unknown;
  };
  withBodyParser.useBodyParser?.('json', {
    limit: MAX_BACKUP_REQUEST_BODY_BYTES,
    verify: (req, _res, buf) => {
      (req as RequestWithRawBody).rawBody = buf.toString('utf8');
    },
  });

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
