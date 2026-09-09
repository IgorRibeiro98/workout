import 'reflect-metadata';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import { SparkLogger } from '../common/logger';
import { SystemClock } from '../common/clock';
import { SqliteService } from '../database/sqlite.service';
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
 *
 * ## O buraco que este arquivo fecha
 *
 * `AccountDeletionService.reconcileTombstones()` existia desde a T17.6 e **nenhum caminho
 * executável a chamava**. O único vestígio operacional era uma linha impressa por `ops/restore.sh`
 * pedindo ao operador que "rodasse a reconciliação depois" — sem dizer como, porque não havia
 * como. Na prática, restaurar um backup anterior a uma exclusão devolvia a conta excluída ao ar, e
 * a única defesa contra isso era a memória de quem estava de plantão às três da manhã.
 *
 * ## Uma autoridade só (§16)
 *
 * A reconciliação é **este comando**, e só ele. Não há reconciliação no startup do servidor, não
 * há gatilho no readiness, não há resíduo manual. A razão é que o momento certo de reconciliar é
 * conhecido — logo depois de um restore, antes de o servidor voltar a atender — e ele é um passo
 * de procedimento, não um estado do processo.
 *
 * A consequência prática é que `/health/ready` não precisa saber nada sobre isto: `ops/restore.sh
 * --install` roda o comando **antes** de declarar a restauração completa, e uma falha aqui falha o
 * restore inteiro. Meia reconciliação no startup e meia no procedimento seria a pior das opções —
 * duas autoridades para a mesma decisão, e nenhuma delas confiável sozinha.
 *
 * ## Falha fechada (§17/§18)
 *
 * Ledger ausente, ilegível ou com uma linha malformada ⇒ código de saída diferente de zero, e
 * nenhuma alteração no banco. Nunca "zero exclusões": as duas leituras produzem o mesmo efeito
 * visível — nada é apagado — e uma delas ressuscita contas em silêncio.
 *
 * A chave HMAC importa tanto quanto o arquivo: é ela que liga um hash do ledger a um uid do banco.
 * Rodar com a chave errada encontra zero correspondências e reporta sucesso. Por isso este comando
 * **precisa** do mesmo `ACCOUNT_DELETION_HMAC_KEY` do servidor — em produção a validação de
 * `AppConfig` já recusa o default de desenvolvimento.
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

  const sqlite = new SqliteService(config, logger);
  try {
    sqlite.initialize();

    const repo = new AccountDeletionRepository(sqlite);
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

    // §63 — os portões de integridade rodam **depois** da reconciliação, sobre o banco que ela
    // deixou. Um purge que quebrasse integridade referencial precisa reprovar o restore aqui, e
    // não aparecer como erro de aplicação na primeira requisição de um usuário.
    const violations = sqlite.connection.pragma('foreign_key_check') as unknown[];
    if (violations.length > 0) {
      process.stderr.write(
        `foreign_key_check encontrou ${violations.length} violação(ões) após a reconciliação\n`,
      );
      return 3;
    }
    const integrity = sqlite.connection.pragma('integrity_check', { simple: true });
    if (integrity !== 'ok') {
      process.stderr.write(`integrity_check: ${String(integrity)}\n`);
      return 3;
    }

    process.stdout.write('foreign_key_check e integrity_check ok\n');
    return 0;
  } finally {
    sqlite.onApplicationShutdown();
  }
}

/**
 * O comando só executa quando **é** o programa, e não quando é importado.
 *
 * É isso que permite ao teste de DR chamar [runReconciliation] — a mesma função, a mesma leitura
 * de ledger, os mesmos códigos de saída — em vez de reimplementar a reconciliação com o serviço
 * direto (§65). O caminho do operador e o caminho do teste são o mesmo código; o que o teste não
 * exercita é o `process.exitCode`, e disso cuida o smoke do CI, que roda o artefato construído.
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
