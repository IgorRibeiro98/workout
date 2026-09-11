import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import BetterSqlite3 from 'better-sqlite3';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';
import { AccountDeletionReconciler } from '../src/modules/account-deletion/account-deletion.reconciler';
import { DELETION_TOMBSTONE_LEDGER } from '../src/modules/account-deletion/deletion-tombstone-ledger.port';
import type { DeletionTombstoneLedgerPort } from '../src/modules/account-deletion/deletion-tombstone-ledger.port';
import { PostgresService } from '../src/database/postgres.service';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
  C: { token: 'token-c', uid: 'uid-c', email: 'c@example.com', name: 'Carol' },
} as const;

const HMAC_KEY = 'chave-hmac-de-teste-para-exclusao-de-conta';

/**
 * T17.13.1 §5–§13 e §64 — a exclusão de conta é durável, atômica e nunca mente.
 *
 * ## As três garantias em teste
 *
 * ```text
 * 1. atomicidade   tombstone + job + purge são UMA transação. Falha no meio ⇒ ROLLBACK total.
 * 2. durabilidade  o ledger de DR é obrigatório. Falha ao gravá-lo ⇒ DELETION_PENDING, nunca
 *                  DELETED — e o estado pendente sobrevive a um restart do processo.
 * 3. irreversível  depois do COMMIT do purge, NADA restaura os dados. Nem falha de arquivo, nem
 *                  falha do Firebase, nem falha do ledger.
 * ```
 *
 * A tensão entre 2 e 3 é o ponto da fase: uma falha depois do commit **não** desfaz a exclusão,
 * mas também não permite declará-la concluída enquanto o registro anti-ressurreição não estiver no
 * disco. O estado intermediário tem nome (`LEDGER_PENDING`) e mora no SQLite.
 */
describe('T17.13.1 — durabilidade e atomicidade da exclusão de conta', () => {
  let temp: TempDb;
  let app: INestApplication;
  let verifier: FakeAuthTokenVerifier;
  let ledgerPath: string;

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  /** Uma leitura direta do arquivo, fora da conexão do app. */
  const inDatabase = <T>(read: (db: BetterSqlite3.Database) => T): T => {
    const db = new BetterSqlite3(temp.path);
    try {
      return read(db);
    } finally {
      db.close();
    }
  };

  const countOf = (sql: string, ...params: unknown[]): number =>
    inDatabase((db) => (db.prepare(sql).get(...params) as { n: number }).n);

  const tombstoneCount = () => countOf(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`);
  const jobCount = () => countOf(`SELECT COUNT(*) AS n FROM account_deletion_jobs`);
  const jobPhase = (uid: string): string | undefined =>
    inDatabase(
      (db) =>
        (
          db.prepare(`SELECT phase FROM account_deletion_jobs WHERE firebase_uid = ?`).get(uid) as
            | { phase: string }
            | undefined
        )?.phase,
    );

  async function boot(): Promise<void> {
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(
      configFor(temp.path, {
        DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
        ACCOUNT_DELETION_HMAC_KEY: HMAC_KEY,
        SOCIAL_MEDIA_ROOT: join(temp.directory, 'media'),
      }),
      verifier,
    );
  }

  beforeEach(async () => {
    temp = createTempDb();
    ledgerPath = join(temp.directory, 'deletion_tombstones.tsv');
    await boot();
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const activate = (account: { token: string; name: string }) =>
    request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(account.token))
      .send({ displayName: account.name })
      .expect(200);

  // ============================================================ §64 caminho feliz

  it('caminho feliz: purge, ledger no disco, Firebase apagado, sem job pendente', async () => {
    await activate(ACCOUNTS.A);

    const res = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(res.body.status).toBe('DELETED');
    expect(tombstoneCount()).toBe(1);
    // Sem job pendente: a exclusão terminou, e "terminou" é a ausência da linha.
    expect(jobCount()).toBe(0);
    expect(verifier.deletedUids).toContain(ACCOUNTS.A.uid);
    expect(
      countOf(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`, ACCOUNTS.A.uid),
    ).toBe(0);

    const ledger = readFileSync(ledgerPath, 'utf8').trim().split('\n');
    expect(ledger).toHaveLength(1);
    // O formato exato que o reconciliador valida: 64 hex, TAB, epoch.
    expect(ledger[0]).toMatch(/^[0-9a-f]{64}\t\d+$/);
  });

  // ============================================================ §7 falha no purge

  it('falha no meio do purge: ROLLBACK total, sem tombstone, sem job, dados preservados', async () => {
    await activate(ACCOUNTS.A);
    await activate(ACCOUNTS.B);

    // Injeção de falha **real**, sem costura no código de produção: um gatilho que aborta a
    // exclusão de uma das tabelas que o purge varre. Ele dispara no meio da transação — depois do
    // tombstone, depois do job e depois de vários `DELETE` já terem sido executados —, que é
    // exatamente o ponto em que o estado parcial seria criado.
    //
    // Criado na **conexão do app**, e não numa segunda conexão: `better-sqlite3` mantém statements
    // preparados, e um gatilho criado de fora só é enxergado quando aquela conexão repara o
    // statement. O teste passava sozinho e falhava na suíte inteira por causa disso — a injeção
    // simplesmente não chegava a existir para quem executa o purge.
    await app.get(PostgresService).query(`
      CREATE OR REPLACE FUNCTION fail_purge_fn() RETURNS trigger AS $$
      BEGIN
        RAISE EXCEPTION 'falha injetada no purge';
      END;
      $$ LANGUAGE plpgsql;
      DROP TRIGGER IF EXISTS falha_no_purge ON social_privacy_settings;
      CREATE TRIGGER falha_no_purge
      BEFORE DELETE ON social_privacy_settings
      FOR EACH ROW EXECUTE FUNCTION fail_purge_fn();
    `);

    // `try/catch`, e nunca `.rejects.toThrow()` sem argumento: o erro vem do `better-sqlite3`, que
    // é um módulo **nativo**, e um erro nativo pode atravessar realms sob o Jest sem satisfazer
    // `instanceof Error` — o matcher então relata "did not throw" para uma promessa que rejeitou.
    // É o mesmo defeito de teste que `bootstrap.spec.ts` teve no CI (1c72045), e ele reapareceu
    // aqui exatamente do mesmo jeito: verde sozinho, vermelho na suíte inteira.
    const service = app.get(AccountDeletionService);
    let rejected = false;
    try {
      await service.deleteAccount(ACCOUNTS.A.uid);
    } catch {
      rejected = true;
    }
    expect(rejected).toBe(true);

    // Nada foi committed: nem o tombstone, nem o job, nem um único `DELETE`.
    expect(tombstoneCount()).toBe(0);
    expect(jobCount()).toBe(0);
    expect(
      countOf(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`, ACCOUNTS.A.uid),
    ).toBe(1);
    expect(
      countOf(
        `SELECT COUNT(*) AS n FROM social_privacy_settings WHERE owner_uid = ?`,
        ACCOUNTS.A.uid,
      ),
    ).toBe(1);

    // E a conta continua funcionando: um ROLLBACK que deixasse o tombstone a bloquearia para
    // sempre sobre dados que continuam no servidor.
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // B nunca foi tocada.
    expect(
      countOf(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`, ACCOUNTS.B.uid),
    ).toBe(1);
  });

  // ============================================================ §10/§11 ledger

  it('falha ao gravar o ledger: DELETION_PENDING, e nunca DELETED', async () => {
    await activate(ACCOUNTS.A);

    // O caminho do ledger vira um **diretório**: `openSync(path, "a")` responde EISDIR. É uma
    // falha de sistema de arquivos de verdade, do mesmo tipo que um volume somente-leitura ou um
    // disco cheio produziriam em produção.
    rmSync(ledgerPath, { force: true });
    mkdirSync(ledgerPath, { recursive: true });

    const res = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(res.body.status).toBe('DELETION_PENDING');

    // §9 — o purge **não** é desfeito. Os dados não voltam, e a conta continua bloqueada.
    expect(tombstoneCount()).toBe(1);
    expect(
      countOf(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`, ACCOUNTS.A.uid),
    ).toBe(0);
    // O que falta está nomeado e é retentável.
    expect(jobPhase(ACCOUNTS.A.uid)).toBe('LEDGER_PENDING');
    // E o Firebase **não** foi chamado: apagar o usuário lá antes de o registro anti-DR existir
    // encerraria a única chance de reconhecer esta exclusão depois.
    expect(verifier.deletedUids).not.toContain(ACCOUNTS.A.uid);

    // A conta segue inacessível fora das rotas de conta.
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
  });

  it('o estado pendente sobrevive a um restart do processo (§11)', async () => {
    await activate(ACCOUNTS.A);
    rmSync(ledgerPath, { force: true });
    mkdirSync(ledgerPath, { recursive: true });

    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(jobPhase(ACCOUNTS.A.uid)).toBe('LEDGER_PENDING');

    // Reinício de verdade: a aplicação inteira é derrubada e reconstruída sobre o mesmo arquivo.
    await app.close();
    app = undefined as unknown as INestApplication;
    await boot();

    // A fase continua lá — porque é uma linha do SQLite, e não estado em memória.
    expect(jobPhase(ACCOUNTS.A.uid)).toBe('LEDGER_PENDING');
    const status = await request(server())
      .get('/v1/account/deletion-status')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(status.body.status).toBe('DELETION_PENDING');
  });

  it('o retry do ledger converge: DELETED depois que o disco volta (§11)', async () => {
    await activate(ACCOUNTS.A);
    rmSync(ledgerPath, { force: true });
    mkdirSync(ledgerPath, { recursive: true });

    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // O problema de infraestrutura é resolvido.
    rmSync(ledgerPath, { recursive: true, force: true });

    const retry = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(retry.body.status).toBe('DELETED');
    expect(jobCount()).toBe(0);
    expect(existsSync(ledgerPath)).toBe(true);
    expect(readFileSync(ledgerPath, 'utf8')).toMatch(/^[0-9a-f]{64}\t\d+\n$/);
    expect(verifier.deletedUids).toContain(ACCOUNTS.A.uid);
  });

  it('o reconciliador em segundo plano destrava um job preso em LEDGER_PENDING (§11)', async () => {
    await activate(ACCOUNTS.A);
    rmSync(ledgerPath, { force: true });
    mkdirSync(ledgerPath, { recursive: true });

    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(jobPhase(ACCOUNTS.A.uid)).toBe('LEDGER_PENDING');

    rmSync(ledgerPath, { recursive: true, force: true });

    // O mesmo caminho que o `setInterval` do processo percorre — sem esperar um minuto por ele.
    const processed = await app.get(AccountDeletionReconciler).processDueJobs();
    expect(processed).toBe(1);
    expect(jobCount()).toBe(0);
    expect(readFileSync(ledgerPath, 'utf8')).toMatch(/^[0-9a-f]{64}\t\d+\n$/);
  });

  it('o ledger tolera o mesmo hash duas vezes: o leitor consome um conjunto (§13)', async () => {
    await activate(ACCOUNTS.A);
    await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    const first = readFileSync(ledgerPath, 'utf8');
    const hash = app.get(AccountDeletionService).hashUid(ACCOUNTS.A.uid);
    // Um retry depois de uma falha parcial pode reescrever a mesma linha.
    appendFileSync(ledgerPath, first, 'utf8');

    const contents = await app
      .get<DeletionTombstoneLedgerPort>(DELETION_TOMBSTONE_LEDGER)
      .readHashes();
    expect(contents.lineCount).toBe(2);
    expect(contents.hashes.size).toBe(1);
    expect(contents.hashes.has(hash)).toBe(true);
  });

  // ============================================================ §64 mídia e Firebase

  it('falha ao apagar arquivo de mídia não ressuscita a conta (§6/§64)', async () => {
    await activate(ACCOUNTS.A);

    const service = app.get(AccountDeletionService);
    // A remoção física falha para **todos** os arquivos. O purge do banco já foi committed antes
    // de a primeira chamada acontecer, e é isso que precisa continuar valendo.
    const store = (service as unknown as { mediaStore: { remove: (k: string) => Promise<void> } })
      .mediaStore;
    jest.spyOn(store, 'remove').mockRejectedValue(new Error('disco falhou'));

    const result = await service.deleteAccount(ACCOUNTS.A.uid);

    expect(result.status).toBe('DELETED');
    expect(tombstoneCount()).toBe(1);
    expect(
      countOf(`SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = ?`, ACCOUNTS.A.uid),
    ).toBe(0);
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
  });

  it('falha do Firebase depois do purge: DELETION_PENDING com o ledger já gravado', async () => {
    await activate(ACCOUNTS.A);
    verifier.deleteUserFailure = new Error('firebase indisponível');

    const res = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(res.body.status).toBe('DELETION_PENDING');
    // O ledger foi gravado **antes** do Firebase: a ordem de §9 é o que garante que uma falha
    // aqui não deixe a exclusão sem registro anti-ressurreição.
    expect(readFileSync(ledgerPath, 'utf8')).toMatch(/^[0-9a-f]{64}\t\d+\n$/);
    expect(jobPhase(ACCOUNTS.A.uid)).toBe('FIREBASE_PENDING');

    // E converge quando o Firebase volta.
    verifier.deleteUserFailure = undefined;
    const retry = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(retry.body.status).toBe('DELETED');
    expect(jobCount()).toBe(0);
    // O ledger não ganhou uma segunda linha: a fase já tinha avançado.
    expect(readFileSync(ledgerPath, 'utf8').trim().split('\n')).toHaveLength(1);
  });

  it('usuário inexistente no Firebase converge para DELETED (§64)', async () => {
    await activate(ACCOUNTS.A);
    // O verificador real trata `auth/user-not-found` como sucesso; o dublê representa isso não
    // falhando. O que este teste fixa é que a ausência de erro produz `DELETED`.
    const res = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(res.body.status).toBe('DELETED');
  });

  it('excluir duas vezes converge, e B e C continuam intactas (§64)', async () => {
    await activate(ACCOUNTS.A);
    await activate(ACCOUNTS.B);
    await activate(ACCOUNTS.C);

    const first = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    const second = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    expect(first.body.status).toBe('DELETED');
    expect(second.body.status).toBe('DELETED');
    expect(tombstoneCount()).toBe(1);
    expect(jobCount()).toBe(0);
    // Uma linha só no ledger: a segunda chamada não chegou a reexecutar a fase do ledger.
    expect(readFileSync(ledgerPath, 'utf8').trim().split('\n')).toHaveLength(1);

    for (const other of [ACCOUNTS.B, ACCOUNTS.C]) {
      await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(other.token))
        .expect(200);
    }
  });
});
