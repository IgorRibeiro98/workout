import 'reflect-metadata';
import { createApp } from './bootstrap/create-app';
import { AppConfig, ConfigValidationError } from './config/app-config';

/**
 * Ponto de entrada do processo.
 *
 * Ordem deliberada: valida configuração -> abre banco e aplica migrations -> só então escuta HTTP.
 * Um processo que não conseguiu carregar configuração ou migrar o banco morre com código 1 em vez
 * de subir e responder erro em toda requisição.
 */
async function bootstrap(): Promise<void> {
  let config: AppConfig;
  try {
    config = AppConfig.fromEnv();
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      // Sem logger ainda: a configuração é justamente o que falhou.
      process.stderr.write(`${error.message}\n`);
      process.exit(1);
    }
    throw error;
  }

  const { app, logger } = await createApp(config);

  await app.listen(config.port, '0.0.0.0');
  logger.info('server.started', { port: config.port, nodeEnv: config.nodeEnv });

  const shutdown = (signal: NodeJS.Signals): void => {
    logger.info('server.shutdown', { signal });
    const timer = setTimeout(() => {
      logger.error('server.shutdown.forced', { signal, timeoutMs: config.shutdownTimeoutMs });
      process.exit(1);
    }, config.shutdownTimeoutMs);
    timer.unref();

    // `app.close()` dispara os shutdown hooks do Nest, que fecham o SQLite (SqliteService).
    void app
      .close()
      .then(() => {
        clearTimeout(timer);
        process.exit(0);
      })
      .catch((error: unknown) => {
        logger.error('server.shutdown.failed', {
          errorMessage: error instanceof Error ? error.message : 'unknown',
        });
        process.exit(1);
      });
  };

  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

void bootstrap();
