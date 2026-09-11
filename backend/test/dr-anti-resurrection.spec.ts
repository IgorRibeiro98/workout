import { INestApplication } from '@nestjs/common';
import { Client } from 'pg';
import request from 'supertest';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';
import { ObjectStorageDeletionTombstoneLedger } from '../src/modules/account-deletion/object-storage-deletion-tombstone.ledger';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { configFor, createTempDb, DEFAULT_TEST_DATABASE_URL, type TempDb } from './support/temp-db';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-anti-a', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-anti-b', name: 'Bob' },
} as const;

/**
 * T18.3 §23 — anti-ressurreição com o ledger em Object Storage (a topologia Cloud Run).
 *
 * ```text
 * usuário excluído
 *   → tombstone persistido FORA do PostgreSQL (objeto no bucket, `system/deletion-tombstones/<hash>`)
 *   → o PostgreSQL volta a um backup ANTERIOR à exclusão (o usuário "ressuscita" na tabela)
 *   → reconciliação lê o ledger externo e purga de novo
 *   → o usuário NÃO consegue usar a conta: 403 ACCOUNT_DELETED
 * ```
 *
 * O "backup antigo" aqui é uma cópia das tabelas do schema isolado (a mesma técnica de
 * `dr-reconciliation.spec.ts`); o mesmo cenário com `pg_dump`/`pg_restore` reais roda no ensaio
 * do CI com a imagem (`ops/gcp/dr-backup-drill.sh`). O ledger, porém, é o de Object Storage de
 * verdade (com o dublê em memória do bucket) — é ele a barreira autoritativa, e é ele que este
 * teste prova que basta.
 */
describe('T18.3 — o ledger externo impede a ressurreição depois de um restore', () => {
  let temp: TempDb;
  let app: INestApplication;
  let storage: InMemoryObjectStorageClient;
  let verifier: FakeAuthTokenVerifier;

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  async function boot(): Promise<void> {
    app = await createTestApp(
      configFor(temp.path, {
        ACCOUNT_DELETION_HMAC_KEY: 'chave-hmac-de-teste-anti-ressurreicao',
        OBJECT_STORAGE_PROVIDER: 'gcs',
        GCS_BUCKET_NAME: 'spark-teste-anti-ressurreicao',
      }),
      verifier,
      undefined,
      undefined,
      undefined,
      { objectStorageClient: storage },
    );
  }

  const sql = async (statement: string): Promise<void> => {
    const client = new Client({
      connectionString: DEFAULT_TEST_DATABASE_URL.replace(/[?&]options=[^&]+/g, ''),
    });
    await client.connect();
    try {
      await client.query(statement);
    } finally {
      await client.end();
    }
  };

  const count = async (table: string, uid: string): Promise<number> => {
    const client = new Client({ connectionString: temp.databaseUrl });
    await client.connect();
    try {
      const res = await client.query<{ n: string }>(
        `SELECT COUNT(*) AS n FROM ${table} WHERE owner_uid = $1`,
        [uid],
      );
      return Number(res.rows[0]?.n ?? 0);
    } finally {
      await client.end();
    }
  };

  /** O "backup antigo": uma cópia das tabelas do schema, como um pg_dump tirado agora. */
  const snapshot = () =>
    sql(`
      DO $$
      DECLARE tbl text;
      BEGIN
        CREATE SCHEMA IF NOT EXISTS "${temp.schema}_snap";
        FOR tbl IN (SELECT tablename FROM pg_tables WHERE schemaname = '${temp.schema}') LOOP
          EXECUTE format('DROP TABLE IF EXISTS "${temp.schema}_snap".%I CASCADE', tbl);
          EXECUTE format('CREATE TABLE "${temp.schema}_snap".%I (LIKE "${temp.schema}".%I INCLUDING ALL)', tbl, tbl);
          EXECUTE format('INSERT INTO "${temp.schema}_snap".%I SELECT * FROM "${temp.schema}".%I', tbl, tbl);
        END LOOP;
      END $$;
    `);

  /** O restore: o PostgreSQL volta a ser o do backup. O bucket NÃO volta — ele é externo. */
  const restoreSnapshot = async () => {
    await app.close();
    await sql(`
      DO $$
      DECLARE tbl text;
      BEGIN
        FOR tbl IN (SELECT tablename FROM pg_tables WHERE schemaname = '${temp.schema}') LOOP
          EXECUTE format('DROP TABLE IF EXISTS "${temp.schema}".%I CASCADE', tbl);
        END LOOP;
        FOR tbl IN (SELECT tablename FROM pg_tables WHERE schemaname = '${temp.schema}_snap') LOOP
          EXECUTE format('CREATE TABLE "${temp.schema}".%I (LIKE "${temp.schema}_snap".%I INCLUDING ALL)', tbl, tbl);
          EXECUTE format('INSERT INTO "${temp.schema}".%I SELECT * FROM "${temp.schema}_snap".%I', tbl, tbl);
        END LOOP;
      END $$;
    `);
    await boot();
  };

  beforeEach(async () => {
    temp = createTempDb();
    storage = new InMemoryObjectStorageClient();
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: `${account.uid}@example.com` });
    }
    await boot();
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  it('usuário excluído → ledger externo → restore de backup antigo → reconciliação → 403, nunca conta ativa', async () => {
    for (const account of [ACCOUNTS.A, ACCOUNTS.B]) {
      await request(server())
        .post('/v1/social/me/activate')
        .set('Authorization', auth(account.token))
        .send({ displayName: account.name })
        .expect(200);
    }

    // 1. O backup é tirado ANTES da exclusão: nele, A existe.
    await snapshot();

    // 2. A é excluída pelo caminho real; o tombstone vai para o bucket, não para um arquivo.
    const deleted = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(deleted.body.status).toBe('DELETED');
    const ledgerObjects = storage
      .names()
      .filter((name) => name.startsWith('system/deletion-tombstones/'));
    expect(ledgerObjects).toHaveLength(1);
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);

    // 3. O desastre: o PostgreSQL volta ao backup antigo. A ressuscitou NA TABELA.
    await restoreSnapshot();
    expect(await count('social_profiles', ACCOUNTS.A.uid)).toBe(1);
    // ...mas o bucket continua sabendo da exclusão: o ledger não faz parte do dump.
    expect(
      storage.names().filter((name) => name.startsWith('system/deletion-tombstones/')),
    ).toEqual(ledgerObjects);

    // Sem reconciliação, o guard ainda deixaria A entrar — é exatamente a janela que a
    // reconciliação obrigatória do runbook fecha.
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);

    // 4. A reconciliação (o que `reconcile-account-deletions` executa) lê o ledger EXTERNO.
    const ledger = new ObjectStorageDeletionTombstoneLedger(storage);
    const { hashes } = await ledger.readHashes();
    expect(hashes.size).toBe(1);
    const purged = await app.get(AccountDeletionService).reconcileTombstones(hashes);
    expect(purged).toBe(1);

    // 5. A não pode usar a conta — funcionalmente excluída de novo; B sobreviveu.
    expect(await count('social_profiles', ACCOUNTS.A.uid)).toBe(0);
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
    await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    // Uma nova ativação de A também é recusada: o tombstone voltou ao banco.
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ displayName: 'Alice de novo' })
      .expect(403);
  });
});
