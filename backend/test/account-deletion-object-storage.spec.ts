import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { fixtureText } from './support/backup-fixtures';
import { jpeg } from './support/image-fixtures';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { PostgresService } from '../src/database/postgres.service';
import { AccountDeletionService } from '../src/modules/account-deletion/account-deletion.service';
import { BackupPayloadCleaner } from '../src/modules/backup/backup-payload.cleaner';
import { SocialMediaCleaner } from '../src/modules/social/social-media.cleaner';
import { runReconciliation } from '../src/cli/reconcile-account-deletions';
import { OBJECT_STORAGE_ORPHAN_GRACE_MS } from '../src/object-storage/object-storage.limits';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
} as const;

const HMAC_KEY = 'chave-hmac-de-teste-para-exclusao-de-conta';
const NOW = Date.parse('2026-09-10T12:00:00Z');

/**
 * T18.1 §36–§38 — a exclusão de conta apaga fotos **e** documentos de backup do Object Storage,
 * o protocolo de tombstone/ledger não regrediu, e a reconciliação de DR fala com o provider
 * configurado.
 */
describe('T18.1 — exclusão de conta e Object Storage', () => {
  let temp: TempDb;
  let app: INestApplication | undefined;
  let clock: FakeClock;
  let root: string;
  let ledgerPath: string;

  const server = () => app!.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  beforeEach(() => {
    temp = createTempDb();
    root = join(temp.directory, 'objects');
    ledgerPath = join(temp.directory, 'deletion_tombstones.tsv');
    clock = new FakeClock(NOW);
  });

  afterEach(async () => {
    await app?.close();
    app = undefined;
    temp.cleanup();
  });

  const verifier = () => {
    const v = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      v.accept(account.token, { uid: account.uid, email: account.email });
    }
    return v;
  };

  const config = () =>
    configFor(temp.path, {
      SOCIAL_MEDIA_ROOT: root,
      DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
      ACCOUNT_DELETION_HMAC_KEY: HMAC_KEY,
    });

  const startLocal = async () => {
    app = await createTestApp(config(), verifier(), undefined, clock);
  };

  const startWithFake = async (fake: InMemoryObjectStorageClient) => {
    fake.setClock(() => clock.now());
    app = await createTestApp(config(), verifier(), undefined, clock, undefined, {
      objectStorageClient: fake,
    });
  };

  /** Uma conta com perfil, uma sessão sincronizada, uma foto publicada e um backup. */
  async function populate(account: (typeof ACCOUNTS)[keyof typeof ACCOUNTS]) {
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(account.token))
      .send({ displayName: account.name })
      .expect(200);

    const syncId = uuid();
    await request(server())
      .post('/v1/sync/push')
      .set('Authorization', auth(account.token))
      .set('Content-Type', 'application/json')
      .send(
        pushBody([
          {
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            payload: sessionPayload(syncId, {
              startedAt: NOW - 60 * 60 * 1000,
              finishedAt: NOW - 30 * 60 * 1000,
            }),
          },
        ]),
      )
      .expect(200);

    const media = await request(server())
      .post('/v1/social/checkin-media')
      .query({ sessionSyncId: syncId, clientUploadId: uuid() })
      .set('Authorization', auth(account.token))
      .set('Content-Type', 'image/jpeg')
      .send(await jpeg())
      .expect(201);
    await request(server())
      .post('/v1/social/workout-checkins')
      .set('Authorization', auth(account.token))
      .send({ sessionSyncId: syncId, clientRequestId: uuid(), mediaId: media.body.mediaId })
      .expect(201);

    const backup = await request(server())
      .post('/v1/backups')
      .set('Authorization', auth(account.token))
      .set('Content-Type', 'application/json')
      .send(fixtureText('backup-v1-complete'))
      .expect(201);

    const keys = await app!.get(PostgresService).query<{ media: string; backup: string }>(
      `SELECT (SELECT storage_key FROM social_checkin_media WHERE owner_uid = $1 LIMIT 1) AS media,
              (SELECT storage_key FROM backup_snapshots WHERE owner_uid = $1 LIMIT 1) AS backup`,
      [account.uid],
    );
    return {
      mediaKey: keys.rows[0].media,
      backupKey: keys.rows[0].backup,
      backupId: backup.body.backupId as string,
    };
  }

  const filesUnder = (dir: string): string[] => {
    const out: string[] = [];
    const walk = (d: string) => {
      if (!existsSync(d)) return;
      for (const entry of readdirSync(d, { withFileTypes: true })) {
        const path = join(d, entry.name);
        if (entry.isDirectory()) walk(path);
        else out.push(path);
      }
    };
    walk(dir);
    return out;
  };

  const countOf = async (sql: string, ...params: unknown[]): Promise<number> =>
    Number((await app!.get(PostgresService).query<{ n: string | number }>(sql, params)).rows[0].n);

  it('exclui metadata, foto e backup; tombstone e ledger permanecem (§36/§37)', async () => {
    await startLocal();
    const a = await populate(ACCOUNTS.A);
    const b = await populate(ACCOUNTS.B);

    expect(existsSync(join(root, 'checkins'))).toBe(true);
    expect(filesUnder(join(root, 'checkins'))).toHaveLength(2);
    expect(filesUnder(join(root, 'backups'))).toHaveLength(2);

    const deleted = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(deleted.body.status).toBe('DELETED');

    // Metadata de A sumiu; a de B ficou.
    expect(
      await countOf(
        `SELECT COUNT(*) AS n FROM social_profiles WHERE owner_uid = $1`,
        ACCOUNTS.A.uid,
      ),
    ).toBe(0);
    expect(
      await countOf(
        `SELECT COUNT(*) AS n FROM backup_snapshots WHERE owner_uid = $1`,
        ACCOUNTS.A.uid,
      ),
    ).toBe(0);
    expect(
      await countOf(
        `SELECT COUNT(*) AS n FROM backup_snapshots WHERE owner_uid = $1`,
        ACCOUNTS.B.uid,
      ),
    ).toBe(1);

    // Os objetos de A sumiram — foto **e** backup —; os de B continuam.
    expect(existsSync(join(root, a.mediaKey))).toBe(false);
    expect(existsSync(join(root, a.backupKey))).toBe(false);
    expect(existsSync(join(root, b.mediaKey))).toBe(true);
    expect(existsSync(join(root, b.backupKey))).toBe(true);

    // O protocolo não regrediu: tombstone no banco, ledger no disco, job encerrado.
    expect(await countOf(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`)).toBe(1);
    expect(await countOf(`SELECT COUNT(*) AS n FROM account_deletion_jobs`)).toBe(0);
    expect(readFileSync(ledgerPath, 'utf8')).toMatch(/^[0-9a-f]{64}\t\d+\n$/);
    await request(server())
      .get(`/v1/backups/${a.backupId}`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);
  });

  it('falha temporária ao apagar objetos não ressuscita a conta; os órfãos convergem pela coleta (§37)', async () => {
    const fake = new InMemoryObjectStorageClient();
    await startWithFake(fake);
    const a = await populate(ACCOUNTS.A);
    expect(fake.names().sort()).toEqual([`social/${a.mediaKey}`, a.backupKey].sort());

    fake.fail('remove');
    const deleted = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    fake.restore();

    // A exclusão terminou — tombstone, ledger, purge — mesmo com o Object Storage falhando.
    expect(deleted.body.status).toBe('DELETED');
    expect(await countOf(`SELECT COUNT(*) AS n FROM social_profiles`)).toBe(0);
    expect(await countOf(`SELECT COUNT(*) AS n FROM backup_snapshots`)).toBe(0);
    expect(await countOf(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`)).toBe(1);
    expect(existsSync(ledgerPath)).toBe(true);
    // Os objetos ficaram para trás — inacessíveis pela API, porque a metadata já não existe.
    expect(fake.names()).toHaveLength(2);
    await request(server())
      .get(`/v1/backups/${a.backupId}`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(403);

    // Recentes: nenhuma coleta os toca ainda.
    expect(await app!.get(SocialMediaCleaner).sweep()).toBe(0);
    expect(await app!.get(BackupPayloadCleaner).sweep()).toBe(0);
    expect(fake.names()).toHaveLength(2);

    // Depois da carência, os dois coletores — cada um no seu namespace — os recolhem.
    clock.advance(OBJECT_STORAGE_ORPHAN_GRACE_MS + 1);
    expect(await app!.get(SocialMediaCleaner).sweep()).toBe(1);
    expect(await app!.get(BackupPayloadCleaner).sweep()).toBe(1);
    expect(fake.names()).toEqual([]);
  });

  it('a reconciliação de DR purga banco, foto e backup pelo provider configurado (§38)', async () => {
    await startLocal();
    const a = await populate(ACCOUNTS.A);
    const service = app!.get(AccountDeletionService);
    const hashA = service.hashUid(ACCOUNTS.A.uid);

    // O cenário de DR: o banco "voltou" com A dentro, e o ledger diz que A foi excluída.
    await app!.close();
    app = undefined;
    writeFileSync(ledgerPath, `${hashA}\t1\n`, 'utf8');

    const saved = { ...process.env };
    Object.assign(process.env, {
      NODE_ENV: 'test',
      LOG_LEVEL: 'silent',
      DATABASE_URL: temp.databaseUrl,
      SOCIAL_MEDIA_ROOT: root,
      OBJECT_STORAGE_PROVIDER: 'local',
      DELETION_TOMBSTONES_FILE_PATH: ledgerPath,
      ACCOUNT_DELETION_HMAC_KEY: HMAC_KEY,
    });
    try {
      await expect(runReconciliation()).resolves.toBe(0);
    } finally {
      for (const key of Object.keys(process.env)) delete process.env[key];
      Object.assign(process.env, saved);
    }

    // Banco e objetos: os dois namespaces, pelo mesmo provider que o runtime usa.
    expect(existsSync(join(root, a.mediaKey))).toBe(false);
    expect(existsSync(join(root, a.backupKey))).toBe(false);
    await startLocal();
    expect(
      await countOf(
        `SELECT COUNT(*) AS n FROM backup_snapshots WHERE owner_uid = $1`,
        ACCOUNTS.A.uid,
      ),
    ).toBe(0);
    expect(await countOf(`SELECT COUNT(*) AS n FROM account_deletion_tombstones`)).toBe(1);
  });
});
