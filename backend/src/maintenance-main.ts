import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { AppConfig, ConfigValidationError } from './config/app-config';
import {
  FirebaseAdminCredentialError,
  verifyFirebaseAdminCredential,
} from './modules/auth/firebase-auth-token-verifier';
import { PostgresService } from './database/postgres.service';
import { SparkLogger } from './common/logger';
import { MaintenanceCoordinator } from './maintenance/maintenance.coordinator';
import { MaintenanceHttpModule } from './maintenance/maintenance-http.module';

/**
 * Ponto de entrada do serviço `spark-maintenance` (T18.2 §33).
 *
 * ## Por que dois contextos Nest, e não um
 *
 * O primeiro (`domainApp`, `createApplicationContext`) monta o **mesmo** `AppModule.forRoot`
 * usado pela API — mesma configuração, mesmo `PostgresService`, mesmo Object Storage, mesmo
 * Firebase Admin, mesmo `MaintenanceCoordinator`. `createApplicationContext` nunca liga um
 * adaptador HTTP, então nenhuma das controllers de `SocialModule`/`BackupModule`/`AuthModule`
 * (herdadas por `AppModule` inteiro) chega a virar rota — é o que mantém este serviço privado com
 * uma superfície mínima sem precisar duplicar o grafo de dependência inteiro à mão.
 *
 * O segundo (`httpApp`) é um módulo minúsculo com **uma** rota (`MaintenanceController`), que só
 * encaminha para o `MaintenanceCoordinator` já resolvido no primeiro contexto.
 *
 * ## Por que a validação de bootstrap se repete de `main.ts`
 *
 * O mesmo config inválido, a mesma credencial obrigatória ausente e a mesma ordem "falha visível
 * antes de escutar porta" — `spark-maintenance` chama `AccountDeletionService.advanceJob`
 * (via `AccountDeletionReconciler`), que precisa do Firebase Admin tanto quanto a API. Duplicado
 * de propósito, e não extraído para um helper compartilhado: os dois entrypoints têm ciclo de vida
 * e testes próprios, e a duplicação de ~20 linhas de validação de startup é mais barata do que o
 * risco de uma abstração prematura acoplando os dois.
 */
async function bootstrap(): Promise<void> {
  let config: AppConfig;
  try {
    config = AppConfig.fromEnv();
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      process.stderr.write(`${error.message}\n`);
      process.exit(1);
    }
    throw error;
  }

  const missing = config.missingRequirements();
  if (missing.length > 0) {
    process.stderr.write(
      `Configuração incompleta:\n${missing.map((m) => `  - ${m}`).join('\n')}\n`,
    );
    process.exit(1);
  }

  if (config.requireFirebaseAdmin) {
    try {
      await verifyFirebaseAdminCredential(
        config.googleApplicationCredentials,
        config.firebaseProjectId,
        config.firebaseAdminCredentialMode,
      );
    } catch (error) {
      if (error instanceof FirebaseAdminCredentialError) {
        process.stderr.write(
          `REQUIRE_FIREBASE_ADMIN=true, mas ${error.reason}.\n` +
            'Corrija a service account/ADC ou desative a exigência. ' +
            'Ver docs/operations/CLOUD_RUN_DEPLOYMENT.md.\n',
        );
        process.exit(1);
      }
      throw error;
    }
  }

  const domainApp = await NestFactory.createApplicationContext(AppModule.forRoot(config), {
    logger: false,
  });

  const fatal = (event: string) => (error: unknown) => {
    process.stderr.write(
      `${event}: ${error instanceof Error ? error.message : 'erro desconhecido'}\n`,
    );
    process.exit(1);
  };
  process.on('uncaughtException', fatal('maintenance.uncaughtException'));
  process.on('unhandledRejection', fatal('maintenance.unhandledRejection'));

  const postgres = domainApp.get(PostgresService);
  await postgres.initialize();

  const logger = domainApp.get(SparkLogger);
  const coordinator = domainApp.get(MaintenanceCoordinator);

  const httpApp = await NestFactory.create(MaintenanceHttpModule.forRoot(coordinator), {
    logger: false,
  });
  httpApp.getHttpAdapter().getInstance().disable('x-powered-by');
  await httpApp.listen(config.port, '0.0.0.0');

  logger.info('maintenance.started', { port: config.port, nodeEnv: config.nodeEnv });

  const shutdown = (signal: NodeJS.Signals): void => {
    logger.info('maintenance.shutdown', { signal });
    const timer = setTimeout(() => {
      logger.error('maintenance.shutdown.forced', { signal, timeoutMs: config.shutdownTimeoutMs });
      process.exit(1);
    }, config.shutdownTimeoutMs);
    timer.unref();

    // HTTP primeiro, contexto de domínio depois — nunca em paralelo (T18.3.2).
    //
    // `domainApp.close()` dispara os shutdown hooks do Nest, e um deles fecha o pool do PostgreSQL.
    // Em paralelo com o dreno do HTTP, um `/internal/maintenance/run` em voo perde o banco no meio
    // do ciclo: o ciclo falha por indisponibilidade que o próprio shutdown criou. `main.ts` já
    // fecha na ordem certa porque lá existe um contexto só.
    void httpApp
      .close()
      .then(() => domainApp.close())
      .then(() => {
        clearTimeout(timer);
        process.exit(0);
      })
      .catch((error: unknown) => {
        logger.error('maintenance.shutdown.failed', {
          errorMessage: error instanceof Error ? error.message : 'unknown',
        });
        process.exit(1);
      });
  };

  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

void bootstrap().catch((error: unknown) => {
  process.stderr.write(
    `Falha no bootstrap de manutenção: ${error instanceof Error ? error.message : 'erro desconhecido'}\n`,
  );
  process.exit(1);
});
