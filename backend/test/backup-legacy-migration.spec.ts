import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixtureText } from './support/backup-fixtures';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { PostgresService } from '../src/database/postgres.service';
import { BackupRepository } from '../src/modules/backup/backup.repository';
import { ObjectStorageBackupPayloadStore } from '../src/modules/backup/backup-payload.store';
import { canonicalize, parseCanonical } from '../src/modules/backup/canonical-json';
import { LocalObjectStorageClient } from '../src/object-storage/local-object-storage.client';
import {
  migrateLegacyPayloads,
  runBackupPayloadMigration,
} from '../src/cli/migrate-backup-payloads-to-object-storage';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

interface LegacyRow {
  readonly sequence: number;
  readonly backupId: string;
  readonly text: string;
  readonly hash: string;
}

/**
 * T18.1 §32–§35 — a migração dos backups anteriores à T18.1 para o Object Storage.
 *
 * Os snapshots legados são semeados **como a T16.5 os gravava**: documento canônico em
 * `backup_snapshots.payload`, payload de cada agregado em `backup_items.payload`, `storage_key`
 * ausente. O migrador é exercitado pela mesma função que o comando operacional roda — e, no fim,
 * pelo próprio comando, com o ambiente que o operador daria a ele.
 */
describe('T18.1 — migração dos payloads legados para o Object Storage', () => {
  let temp: TempDb;
  let postgres: PostgresService;
  let repository: BackupRepository;
  let root: string;

  const sha256 = (bytes: Buffer): string => createHash('sha256').update(bytes).digest('hex');

  beforeEach(async () => {
    temp = createTempDb();
    root = join(temp.directory, 'objects');
    postgres = postgresFor(configFor(temp.path, { SOCIAL_MEDIA_ROOT: root }));
    await postgres.initialize();
    repository = new BackupRepository(postgres);
  });

  afterEach(async () => {
    await postgres.close();
    temp.cleanup();
  });

  /** Um snapshot como a T16.5 o deixava no banco: documento e itens com payload. */
  async function seedLegacy(
    ownerUid: string,
    clientBackupId: string,
    rawBody: string,
  ): Promise<LegacyRow> {
    const document = parseCanonical(rawBody);
    const text = document.text;
    const bytes = Buffer.from(text, 'utf8');
    const hash = sha256(bytes);
    const backupId = randomUUID();
    const items = (document.members?.get('items')?.elements ?? []).map((node) => ({
      value: node.value as { entityType: string; syncId: string; entitySchemaVersion: number },
      payloadText: node.members?.get('payload')?.text ?? '{}',
    }));

    const inserted = await postgres.query<{ id: string | number }>(
      `INSERT INTO backup_snapshots
         (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
          payload_hash, item_count, size_bytes, captured_at, created_at, payload)
       VALUES ($1, $2, $3, 'device-legado', 1, $4, $5, $6, 1, $7, $8)
       RETURNING id`,
      [backupId, ownerUid, clientBackupId, hash, items.length, bytes.length, Date.now(), text],
    );
    const sequence = Number(inserted.rows[0].id);
    for (const item of items) {
      await postgres.query(
        `INSERT INTO backup_items
           (snapshot_id, entity_type, entity_sync_id, entity_schema_version, payload, content_hash)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [
          sequence,
          item.value.entityType,
          item.value.syncId,
          item.value.entitySchemaVersion,
          item.payloadText,
          sha256(Buffer.from(item.payloadText, 'utf8')),
        ],
      );
    }
    return { sequence, backupId, text, hash };
  }

  async function rowOf(backupId: string) {
    const res = await postgres.query<{
      storage_key: string | null;
      payload: string | null;
      payload_hash: string;
      item_payloads: string | number;
      items: string | number;
    }>(
      `SELECT s.storage_key, s.payload, s.payload_hash,
              (SELECT COUNT(*) FROM backup_items i WHERE i.snapshot_id = s.id AND i.payload IS NOT NULL) AS item_payloads,
              (SELECT COUNT(*) FROM backup_items i WHERE i.snapshot_id = s.id) AS items
         FROM backup_snapshots s WHERE s.backup_id = $1`,
      [backupId],
    );
    return res.rows[0];
  }

  const keyOf = (backupId: string) =>
    `backups/${backupId.slice(0, 2)}/${backupId.slice(2, 4)}/${backupId}.json`;

  it('move o documento para o Object Storage, esvazia os payloads do banco e mantém o restore igual', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-complete'));
    expect(Number((await rowOf(legacy.backupId)).item_payloads)).toBe(9);

    const store = new ObjectStorageBackupPayloadStore(new LocalObjectStorageClient(root));
    const report = await migrateLegacyPayloads(repository, store);
    expect(report).toEqual({ migrated: 1, converged: 0, failed: 0, withoutDocument: 0 });

    // O objeto tem exatamente o texto legado; o hash da metadata não mudou.
    const object = readFileSync(join(root, keyOf(legacy.backupId)));
    expect(object.toString('utf8')).toBe(legacy.text);
    expect(sha256(object)).toBe(legacy.hash);

    const row = await rowOf(legacy.backupId);
    expect(row.storage_key).toBe(keyOf(legacy.backupId));
    expect(row.payload).toBeNull();
    expect(row.payload_hash).toBe(legacy.hash);
    expect(Number(row.items)).toBe(9);
    expect(Number(row.item_payloads)).toBe(0);

    // E o restore pela API devolve o mesmo documento — agora vindo do Object Storage.
    const app = await startApp();
    try {
      const response = await request(app.getHttpServer())
        .get(`/v1/backups/${legacy.backupId}/content`)
        .set('Authorization', `Bearer ${TOKEN}`)
        .expect(200);
      expect(response.text).toBe(legacy.text);
      expect(sha256(Buffer.from(response.text, 'utf8'))).toBe(legacy.hash);
    } finally {
      await app.close();
    }
  });

  it('rodar de novo não duplica, não sobrescreve e não erra (§33)', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-complete'));
    const store = new ObjectStorageBackupPayloadStore(new LocalObjectStorageClient(root));

    await migrateLegacyPayloads(repository, store);
    const objectBefore = readFileSync(join(root, keyOf(legacy.backupId)));

    const again = await migrateLegacyPayloads(repository, store);
    expect(again).toEqual({ migrated: 0, converged: 0, failed: 0, withoutDocument: 0 });
    expect(readFileSync(join(root, keyOf(legacy.backupId))).equals(objectBefore)).toBe(true);
    expect((await rowOf(legacy.backupId)).storage_key).toBe(keyOf(legacy.backupId));
  });

  it('é retomável: objeto já gravado com o mesmo conteúdo converge; o banco é atualizado (§33)', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-complete'));
    const store = new ObjectStorageBackupPayloadStore(new LocalObjectStorageClient(root));

    // Uma execução anterior subiu o objeto e morreu antes da transação.
    await store.write(keyOf(legacy.backupId), Buffer.from(legacy.text, 'utf8'), {
      sha256: legacy.hash,
    });

    const report = await migrateLegacyPayloads(repository, store);
    expect(report).toEqual({ migrated: 0, converged: 1, failed: 0, withoutDocument: 0 });
    const row = await rowOf(legacy.backupId);
    expect(row.storage_key).toBe(keyOf(legacy.backupId));
    expect(row.payload).toBeNull();
    expect(Number(row.item_payloads)).toBe(0);
  });

  it('objeto existente com conteúdo diferente: FAIL CLOSED — nada é sobrescrito nem apagado (§33)', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-complete'));
    const store = new ObjectStorageBackupPayloadStore(new LocalObjectStorageClient(root));

    await store.write(keyOf(legacy.backupId), Buffer.from('{"outro":true}', 'utf8'), {
      sha256: 'x',
    });

    const lines: string[] = [];
    const report = await migrateLegacyPayloads(repository, store, (line) => lines.push(line));
    expect(report).toEqual({ migrated: 0, converged: 0, failed: 1, withoutDocument: 0 });
    expect(lines.join('\n')).toContain('OBJECT_MISMATCH');
    // Nem o conteúdo, nem o dono, nem o payload aparecem no relatório.
    expect(lines.join('\n')).not.toContain(UID);
    expect(lines.join('\n')).not.toContain('Treino');

    // O objeto estranho ficou como estava; o banco continua legado e restaurável.
    expect(readFileSync(join(root, keyOf(legacy.backupId))).toString()).toBe('{"outro":true}');
    const row = await rowOf(legacy.backupId);
    expect(row.storage_key).toBeNull();
    expect(row.payload).toBe(legacy.text);
    expect(Number(row.item_payloads)).toBe(9);
  });

  it('payload legado que não fecha com o próprio hash: FAIL CLOSED, sem upload (§34)', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-complete'));
    await postgres.query(`UPDATE backup_snapshots SET payload = $1 WHERE backup_id = $2`, [
      legacy.text.replace('"backupSchemaVersion":1', '"backupSchemaVersion":2'),
      legacy.backupId,
    ]);
    const store = new ObjectStorageBackupPayloadStore(new LocalObjectStorageClient(root));

    const report = await migrateLegacyPayloads(repository, store);
    expect(report.failed).toBe(1);
    expect(existsSync(join(root, 'backups'))).toBe(false);
    expect((await rowOf(legacy.backupId)).storage_key).toBeNull();
  });

  it('snapshot anterior à T16.5 — sem documento em lugar nenhum — é contado e deixado em paz', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-minimal'));
    await postgres.query(`UPDATE backup_snapshots SET payload = NULL WHERE backup_id = $1`, [
      legacy.backupId,
    ]);
    const store = new ObjectStorageBackupPayloadStore(new LocalObjectStorageClient(root));

    const report = await migrateLegacyPayloads(repository, store);
    expect(report).toEqual({ migrated: 0, converged: 0, failed: 0, withoutDocument: 1 });
  });

  it('vários snapshots, de várias contas, em lotes — e o upload é verificado por leitura', async () => {
    const seeded = [];
    for (let index = 0; index < 7; index += 1) {
      seeded.push(
        await seedLegacy(
          index % 2 === 0 ? UID : 'outra-conta',
          `legado-${index}`,
          index % 2 === 0
            ? fixtureText('backup-v1-complete')
            : canonicalize(fixtureText('backup-v1-minimal')).replace(
                '"clientBackupId":"',
                `"clientBackupId":"${index}-`,
              ),
        ),
      );
    }
    const fake = new InMemoryObjectStorageClient();
    const store = new ObjectStorageBackupPayloadStore(fake);

    const report = await migrateLegacyPayloads(repository, store);
    expect(report).toEqual({ migrated: 7, converged: 0, failed: 0, withoutDocument: 0 });
    expect(fake.names()).toHaveLength(7);
    for (const legacy of seeded) {
      const row = await rowOf(legacy.backupId);
      expect(row.storage_key).toBe(keyOf(legacy.backupId));
      expect(row.payload).toBeNull();
      expect((await fake.read(keyOf(legacy.backupId)))!.toString('utf8')).toBe(legacy.text);
      // A metadata segura do objeto carrega o hash — e é o hash da metadata do banco.
      expect(fake.metadataOf(keyOf(legacy.backupId))).toEqual({ 'spark-sha256': legacy.hash });
    }
  });

  it('o comando operacional roda com o provider configurado e termina com código 0', async () => {
    const legacy = await seedLegacy(UID, 'legado-1', fixtureText('backup-v1-complete'));

    const saved = { ...process.env };
    Object.assign(process.env, {
      NODE_ENV: 'test',
      LOG_LEVEL: 'silent',
      DATABASE_URL: temp.databaseUrl,
      SOCIAL_MEDIA_ROOT: root,
      OBJECT_STORAGE_PROVIDER: 'local',
    });
    try {
      await expect(runBackupPayloadMigration()).resolves.toBe(0);
    } finally {
      for (const key of Object.keys(process.env)) delete process.env[key];
      Object.assign(process.env, saved);
    }

    expect(readFileSync(join(root, keyOf(legacy.backupId))).toString('utf8')).toBe(legacy.text);
    expect((await rowOf(legacy.backupId)).payload).toBeNull();
  });

  async function startApp(): Promise<INestApplication> {
    return createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: root }),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
    );
  }
});
