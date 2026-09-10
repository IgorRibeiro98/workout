import 'reflect-metadata';
import { createApp } from './bootstrap/create-app';
import { AppConfig, ConfigValidationError } from './config/app-config';
import {
  FirebaseAdminCredentialError,
  verifyFirebaseAdminCredential,
} from './modules/auth/firebase-auth-token-verifier';

/**
 * Ponto de entrada do processo.
 *
 * Ordem deliberada: valida configuração -> confere as exigências declaradas -> verifica a
 * credencial obrigatória -> abre banco e aplica migrations -> só então escuta HTTP. Um processo que não conseguiu carregar configuração ou
 * migrar o banco morre com código 1 em vez de subir e responder erro em toda requisição.
 *
 * A consequência que importa em produção (T16.8 §19/§20): `/health/ready` **não existe** enquanto
 * as migrations não terminaram, porque o servidor HTTP ainda não está escutando. Não há janela em
 * que o readiness responda 200 sobre um schema pela metade.
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

  // As exigências que este deploy declarou (`REQUIRE_*`). Cada valor é individualmente válido; o
  // que falta é a combinação que o operador prometeu. Falhar aqui é visível; subir e responder
  // 503 em cada requisição parece instabilidade e leva horas para ser diagnosticado.
  const missing = config.missingRequirements();
  if (missing.length > 0) {
    process.stderr.write(
      `Configuração incompleta:\n${missing.map((m) => `  - ${m}`).join('\n')}\n`,
    );
    process.exit(1);
  }

  // A exigência declarada, verificada de verdade (T16.8.1 §8).
  //
  // `missingRequirements()` acima confere que o **caminho** foi informado. Isso é barato e vale a
  // pena, mas um caminho preenchido não é uma credencial utilizável: o arquivo pode não existir,
  // pode estar montado sem permissão de leitura para o usuário do container, ou pode ser um JSON
  // truncado. Nesses casos o processo subia, respondia `/health/ready` 200, e devolvia `503` em
  // toda requisição autenticada — indistinguível de instabilidade de rede.
  //
  // Aqui, e não no readiness: `/health/ready` continua sem consultar Firebase (§13.7). A checagem
  // é local e offline — arquivo, JSON, forma e `initializeApp` do Admin SDK —, e acontece **antes**
  // de abrir o banco e de escutar a porta.
  if (config.requireFirebaseAdmin) {
    try {
      await verifyFirebaseAdminCredential(
        config.googleApplicationCredentials,
        config.firebaseProjectId,
      );
    } catch (error) {
      if (error instanceof FirebaseAdminCredentialError) {
        // Sem logger ainda, e de propósito: subir para responder erro seria o que este bloco
        // existe para impedir. stderr + código 1 é o que o Docker e o systemd sabem ler.
        process.stderr.write(
          `REQUIRE_FIREBASE_ADMIN=true, mas ${error.reason}.\n` +
            'Corrija a service account ou desative a exigência. ' +
            'Ver docs/operations/PRODUCTION_DEPLOYMENT.md.\n',
        );
        process.exit(1);
      }
      throw error;
    }
  }

  const { app, logger } = await createApp(config);

  // Um erro que escapou de todo tratamento deixa o processo em estado desconhecido. Continuar
  // servindo a partir daí é pior que morrer: a política de restart do Docker sobe um processo
  // limpo em segundos, e o SQLite fica consistente porque cada transação já é atômica.
  const fatal = (event: string) => (error: unknown) => {
    logger.error(event, {
      errorName: error instanceof Error ? error.name : 'UnknownError',
      errorMessage: error instanceof Error ? error.message : undefined,
    });
    process.exit(1);
  };
  process.on('uncaughtException', fatal('process.uncaughtException'));
  process.on('unhandledRejection', fatal('process.unhandledRejection'));

  await app.listen(config.port, '0.0.0.0');

  // Tetos de tempo declarados, e não herdados do default do Node (T16.8 §88).
  //
  // `keepAliveTimeout` precisa ser **maior** que o keep-alive do proxy à frente: se o Node fecha
  // primeiro, o Caddy reaproveita uma conexão morta e o cliente vê um 502 esporádico que não tem
  // nada a ver com a aplicação. `headersTimeout` acompanha o `requestTimeout` porque o Node exige
  // que ele não seja menor.
  const server = app.getHttpServer() as {
    requestTimeout: number;
    headersTimeout: number;
    keepAliveTimeout: number;
  };
  server.requestTimeout = config.httpRequestTimeoutMs;
  server.headersTimeout = config.httpRequestTimeoutMs + 5_000;
  server.keepAliveTimeout = config.httpKeepAliveTimeoutMs;

  logger.info('server.started', {
    port: config.port,
    nodeEnv: config.nodeEnv,
    // Metadata de operação, nunca segredo: é o que permite responder "qual versão está no ar?" e
    // "por que o Coach está fora?" lendo o log, sem entrar na VPS.
    maintenanceMode: config.maintenanceMode,
    aiEnabled: config.aiEnabled,
    syncWriteEnabled: config.syncWriteEnabled,
    requestTimeoutMs: config.httpRequestTimeoutMs,
  });

  const shutdown = (signal: NodeJS.Signals): void => {
    logger.info('server.shutdown', { signal });
    const timer = setTimeout(() => {
      logger.error('server.shutdown.forced', { signal, timeoutMs: config.shutdownTimeoutMs });
      process.exit(1);
    }, config.shutdownTimeoutMs);
    timer.unref();

    // `app.close()` dispara os shutdown hooks do Nest, que fecham o pool do PostgreSQL (PostgresService).
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

void bootstrap().catch((error: unknown) => {
  // Falha antes de o logger existir — abrir o banco, aplicar migration, escutar a porta. Sem
  // logger estruturado aqui de propósito: se o bootstrap falhou, ele pode ser exatamente o que
  // não subiu. `stderr` + código 1 é o que o Docker e o systemd sabem ler.
  process.stderr.write(
    `Falha no bootstrap: ${error instanceof Error ? error.message : 'erro desconhecido'}\n`,
  );
  process.exit(1);
});
