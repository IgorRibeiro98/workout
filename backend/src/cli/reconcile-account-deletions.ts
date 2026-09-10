import 'reflect-metadata';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { SparkLogger } from '../common/logger';
import { SystemClock } from '../common/clock';
import { PostgresService } from '../database/postgres.service';
import { AccountDeletionRepository } from '../modules/account-deletion/account-deletion.repository';
import { AccountDeletionService } from '../modules/account-deletion/account-deletion.service';
import {
  DeletionTombstoneLedger,
  DeletionTombstoneLedgerError,
} from '../modules/account-deletion/deletion-tombstone.ledger';
import { LocalSocialMediaStore } from '../modules/social/social-media.store';
import type { AuthTokenVerifier } from '../modules/auth/auth-token-verifier';

/**
 * `spark-reconcile-account-deletions` — a reconciliação anti-ressurreição, como comando
 * operacional de verdade (T17.13.1 §14).
 *
 * ```bash
 * node dist/cli/reconcile-account-deletions.js
 * ```
 */
export async function runReconciliation(): Promise<number> {
  let config: AppConfig;
  try {
    config = AppConfig.fromEnv();
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const missing = config.missingRequirements();
  if (missing.length > 0) {
    process.stderr.write(
      `Configuração incompleta:\n${missing.map((m) => `  - ${m}`).join('\n')}\n`,
    );
    return 1;
  }

  const logger = new SparkLogger(config);
  const ledger = new DeletionTombstoneLedger(config);

  // O ledger é lido **antes** de o banco ser aberto. Se ele não serve, nada deve ser tocado.
  let hashes: Set<string>;
  let lineCount: number;
  try {
    ({ hashes, lineCount } = ledger.readHashes());
  } catch (error) {
    if (error instanceof DeletionTombstoneLedgerError) {
      process.stderr.write(
        `reconciliação abortada: ${error.message}\n` +
          `  arquivo: ${ledger.filePath}\n` +
          `  Em uma operação de recuperação de desastre isto é uma falha, e nunca "nenhuma conta\n` +
          `  excluída": sem o ledger não há como saber quem já foi excluído, e prosseguir\n` +
          `  devolveria essas contas ao ar. Restaure o ledger a partir do backup e rode de novo.\n`,
      );
      return 2;
    }
    throw error;
  }

  process.stdout.write(
    `ledger de exclusões: ${lineCount} registro(s), ${hashes.size} conta(s) distinta(s)\n`,
  );

  const postgres = new PostgresService(config, logger);
  try {
    await postgres.initialize();

    const repo = new AccountDeletionRepository(postgres);
    const mediaStore = new LocalSocialMediaStore(config);
    // A reconciliação não fala com o provedor de autenticação: as contas do ledger já foram
    // apagadas no Firebase quando foram excluídas. O que voltou foi o **arquivo do banco**, e é
    // só ele (mais a mídia) que precisa ser purgado de novo. Um verificador que lançasse em
    // qualquer uso é o que torna essa ausência de dependência verificável, em vez de assumida.
    const noAuthProvider: AuthTokenVerifier = {
      verify: () => {
        throw new Error('a reconciliação de DR não autentica ninguém');
      },
    };
    const service = new AccountDeletionService(
      repo,
      noAuthProvider,
      config,
      new SystemClock(),
      mediaStore,
      ledger,
      logger,
    );

    const purged = await service.reconcileTombstones(hashes);
    process.stdout.write(`contas reconciliadas (purgadas de novo): ${purged}\n`);

    // No PostgreSQL, integridade referencial é validada atomicamente em cada escrita com foreign keys ativas.
    process.stdout.write('integridade referencial e verificação ok\n');
    return 0;
  } finally {
    await postgres.onApplicationShutdown();
  }
}

/**
 * O comando só executa quando **é** o programa, e não quando é importado.
 */
if (require.main === module) {
  runReconciliation()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `reconciliação abortada: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
