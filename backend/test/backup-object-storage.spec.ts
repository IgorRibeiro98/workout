import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { fixtureText, withClientBackupId } from './support/backup-fixtures';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { PostgresService } from '../src/database/postgres.service';
import { BackupRepository } from '../src/modules/backup/backup.repository';
import { BackupPayloadCleaner } from '../src/modules/backup/backup-payload.cleaner';
import { canonicalize } from '../src/modules/backup/canonical-json';
import { OBJECT_STORAGE_ORPHAN_GRACE_MS } from '../src/object-storage/object-storage.limits';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';
const NOW = Date.parse('2026-09-10T12:00:00Z');

interface SnapshotRow {
  readonly backup_id: string;
  readonly storage_key: string | null;
  readonly payload: string | null;
  readonly payload_hash: string;
  readonly size_bytes: number;
  readonly item_payloads: number | string;
  readonly items: number | string;
}

/**
 * T18.1 — o documento canônico do backup vive no Object Storage; o PostgreSQL guarda metadata,
 * hashes e a chave.
 *
 * Duas montagens, de propósito:
 *
 * - **provider `local` real** (`SOCIAL_MEDIA_ROOT` temporária): o caminho que desenvolvimento e
 *   CI usam, com os bytes no disco de verdade — é onde se prova "objeto criado", "byte a byte",
 *   "payload NULL" e a retenção;
 * - **dublê em memória** com o contrato do bucket: é onde se injeta a falha — write que falha,
 *   delete que falha, objeto corrompido — sem rede e sem GCP, para provar a ordem entre o Object
 *   Storage e o PostgreSQL e a coleta de órfãos.
 *
 * Nada aqui toca o GCS.
 */
describe('T18.1 — backup no Object Storage', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let root: string;

  beforeEach(() => {
    temp = createTempDb();
    root = join(temp.directory, 'objects');
    clock = new FakeClock(NOW);
  });

  afterEach(async () => {
    await app?.close();
    app = undefined as unknown as INestApplication;
    temp.cleanup();
  });

  const startLocal = async (overrides: Record<string, string> = {}): Promise<void> => {
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: root, ...overrides }),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
      undefined,
      clock,
    );
  };

  const startWithFake = async (
    fake: InMemoryObjectStorageClient,
    overrides: Record<string, string> = {},
  ): Promise<void> => {
    fake.setClock(() => clock.now());
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: root, ...overrides }),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
      undefined,
      clock,
      undefined,
      { objectStorageClient: fake },
    );
  };

  const post = (body: string) =>
    request(app.getHttpServer())
      .post('/v1/backups')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(body);

  const content = (backupId: string) =>
    request(app.getHttpServer())
      .get(`/v1/backups/${backupId}/content`)
      .set('Authorization', `Bearer ${TOKEN}`)
      .buffer(true)
      .parse((response, callback) => {
        const chunks: Buffer[] = [];
        response.on('data', (chunk: Buffer) => chunks.push(chunk));
        response.on('end', () => callback(null, Buffer.concat(chunks)));
      });

  const snapshotRow = async (backupId: string): Promise<SnapshotRow> => {
    const res = await app.get(PostgresService).query<SnapshotRow>(
      `SELECT s.backup_id, s.storage_key, s.payload, s.payload_hash, s.size_bytes,
              (SELECT COUNT(*) FROM backup_items i WHERE i.snapshot_id = s.id AND i.payload IS NOT NULL) AS item_payloads,
              (SELECT COUNT(*) FROM backup_items i WHERE i.snapshot_id = s.id) AS items
         FROM backup_snapshots s WHERE s.backup_id = $1`,
      [backupId],
    );
    return res.rows[0];
  };

  const snapshotCount = async (): Promise<number> =>
    Number(
      (
        await app
          .get(PostgresService)
          .query<{ n: string | number }>(`SELECT COUNT(*) AS n FROM backup_snapshots`)
      ).rows[0].n,
    );

  /** Todos os objetos de backup no disco do provider local. */
  const backupObjectsOnDisk = (): string[] => {
    const out: string[] = [];
    const walk = (dir: string, prefix: string) => {
      if (!existsSync(dir)) return;
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const name = `${prefix}${entry.name}`;
        if (entry.isDirectory()) walk(join(dir, entry.name), `${name}/`);
        else out.push(name);
      }
    };
    walk(join(root, 'backups'), 'backups/');
    return out.sort();
  };

  const sha256 = (bytes: Buffer): string => createHash('sha256').update(bytes).digest('hex');

  // ================================================================ criação e restore

  describe('backup novo (§18/§19/§22/§24)', () => {
    it('POST cria o objeto; o PostgreSQL fica com metadata, hashes e a chave — e sem payload', async () => {
      await startLocal();
      const body = fixtureText('backup-v1-complete');
      const expectedText = canonicalize(body);

      const created = await post(body).expect(201);
      const backupId = created.body.backupId as string;

      const row = await snapshotRow(backupId);
      expect(row.storage_key).toBe(
        `backups/${backupId.slice(0, 2)}/${backupId.slice(2, 4)}/${backupId}.json`,
      );
      expect(row.payload).toBeNull();
      expect(row.payload_hash).toBe(created.body.payloadHash);
      expect(Number(row.items)).toBe(9);
      expect(Number(row.item_payloads)).toBe(0);

      // O objeto existe, sob `backups/`, com exatamente o texto canônico.
      expect(backupObjectsOnDisk()).toEqual([row.storage_key]);
      const stored = readFileSync(join(root, row.storage_key!));
      expect(stored.toString('utf8')).toBe(expectedText);
      expect(sha256(stored)).toBe(created.body.payloadHash);
      expect(stored.length).toBe(created.body.sizeBytes);

      // A chave nunca sai na API.
      expect(JSON.stringify(created.body)).not.toContain('backups/');
      expect(JSON.stringify(created.body)).not.toContain('storageKey');
    });

    it('GET content devolve o documento byte a byte, igual ao objeto e ao hash (§22)', async () => {
      await startLocal();
      const body = fixtureText('backup-v1-complete');
      const created = await post(body).expect(201);
      const backupId = created.body.backupId as string;

      const response = await content(backupId).expect(200);
      const bytes = response.body as Buffer;
      const row = await snapshotRow(backupId);

      expect(bytes.equals(readFileSync(join(root, row.storage_key!)))).toBe(true);
      expect(bytes.toString('utf8')).toBe(canonicalize(body));
      expect(sha256(bytes)).toBe(created.body.payloadHash);
      expect(bytes.length).toBe(created.body.sizeBytes);
      expect(response.headers['content-type']).toContain('application/json');
    });

    it('a chave do objeto não deriva de nada que o cliente escolha (§21)', async () => {
      await startLocal();
      const body = withClientBackupId('backup-v1-complete', 'tentativa-com-nome-reconhecivel');
      const created = await post(JSON.stringify(body)).expect(201);
      const row = await snapshotRow(created.body.backupId as string);

      for (const forbidden of [
        'tentativa-com-nome-reconhecivel',
        UID,
        body.deviceId as string,
        'Treino',
      ]) {
        expect(row.storage_key).not.toContain(forbidden);
      }
    });
  });

  // ================================================================ integridade no download

  describe('integridade no download (§24)', () => {
    it('objeto alterado → hash diverge → nada é servido (410)', async () => {
      await startLocal();
      const created = await post(fixtureText('backup-v1-complete')).expect(201);
      const backupId = created.body.backupId as string;
      const row = await snapshotRow(backupId);

      // Mesmo tamanho, um byte diferente: só o hash pega.
      const original = readFileSync(join(root, row.storage_key!));
      const tampered = Buffer.from(original);
      tampered[tampered.length - 2] = tampered[tampered.length - 2] === 0x7d ? 0x5d : 0x7d;
      writeFileSync(join(root, row.storage_key!), tampered);

      const response = await content(backupId).expect(410);
      expect(JSON.parse((response.body as Buffer).toString()).error.code).toBe(
        'BACKUP_CONTENT_UNAVAILABLE',
      );
    });

    it('objeto truncado → tamanho diverge → nada é servido (410)', async () => {
      await startLocal();
      const created = await post(fixtureText('backup-v1-complete')).expect(201);
      const backupId = created.body.backupId as string;
      const row = await snapshotRow(backupId);

      const original = readFileSync(join(root, row.storage_key!));
      writeFileSync(join(root, row.storage_key!), original.subarray(0, original.length - 10));

      const response = await content(backupId).expect(410);
      expect(JSON.parse((response.body as Buffer).toString()).error.code).toBe(
        'BACKUP_CONTENT_UNAVAILABLE',
      );
    });

    it('objeto ausente → CONTENT_UNAVAILABLE; a metadata continua existindo (§24/§31)', async () => {
      await startLocal();
      const created = await post(fixtureText('backup-v1-complete')).expect(201);
      const backupId = created.body.backupId as string;
      const row = await snapshotRow(backupId);
      rmSync(join(root, row.storage_key!));

      const response = await content(backupId).expect(410);
      expect(JSON.parse((response.body as Buffer).toString()).error.code).toBe(
        'BACKUP_CONTENT_UNAVAILABLE',
      );
      await request(app.getHttpServer())
        .get(`/v1/backups/${backupId}`)
        .set('Authorization', `Bearer ${TOKEN}`)
        .expect(200);
    });

    it('Object Storage indisponível na leitura → 503, e nunca "não pode ser restaurado" (§40)', async () => {
      const fake = new InMemoryObjectStorageClient();
      await startWithFake(fake);
      const created = await post(fixtureText('backup-v1-complete')).expect(201);
      const backupId = created.body.backupId as string;

      fake.fail('read');
      const response = await content(backupId).expect(503);
      expect(JSON.parse((response.body as Buffer).toString()).error.code).toBe(
        'BACKUP_STORAGE_UNAVAILABLE',
      );

      fake.restore();
      await content(backupId).expect(200);
    });
  });

  // ================================================================ falhas entre GCS e PostgreSQL

  describe('falhas entre o Object Storage e o PostgreSQL (§25/§26/§27)', () => {
    it('write falha → 503, nenhuma linha nova, nenhum objeto', async () => {
      const fake = new InMemoryObjectStorageClient();
      await startWithFake(fake);
      fake.fail('write');

      const response = await post(fixtureText('backup-v1-complete')).expect(503);
      expect(response.body.error.code).toBe('BACKUP_STORAGE_UNAVAILABLE');
      expect(await snapshotCount()).toBe(0);
      expect(fake.names()).toEqual([]);

      // Reenviar depois é idempotente: a mesma tentativa vira o backup.
      fake.restore();
      await post(fixtureText('backup-v1-complete')).expect(201);
      expect(await snapshotCount()).toBe(1);
      expect(fake.names()).toHaveLength(1);
    });

    it('write OK, INSERT falha → o objeto recém-criado é removido (tentativa de limpeza)', async () => {
      const fake = new InMemoryObjectStorageClient();
      await startWithFake(fake);
      const repository = app.get(BackupRepository);
      const insert = jest.spyOn(repository, 'insert').mockImplementation(() => {
        throw new Error('falha injetada no INSERT');
      });

      const response = await post(fixtureText('backup-v1-complete'));
      expect(response.status).toBe(500);
      expect(insert).toHaveBeenCalled();
      expect(await snapshotCount()).toBe(0);
      // O objeto subiu e foi apagado: nenhuma metadata aponta para ele, e ele não ficou.
      expect(fake.names()).toEqual([]);

      insert.mockRestore();
    });

    it('INSERT falha e a limpeza também → órfão; a coleta o recolhe depois da carência (§26/§30)', async () => {
      const fake = new InMemoryObjectStorageClient();
      await startWithFake(fake);
      const repository = app.get(BackupRepository);
      const insert = jest.spyOn(repository, 'insert').mockImplementation(() => {
        throw new Error('falha injetada no INSERT');
      });
      // O `remove` da limpeza falha; o `write` já aconteceu.
      const originalRemove = fake.remove.bind(fake);
      const remove = jest.spyOn(fake, 'remove').mockImplementation(() => {
        throw new Error('falha injetada no remove');
      });

      await post(fixtureText('backup-v1-complete')).expect(500);
      expect(await snapshotCount()).toBe(0);
      expect(fake.names()).toHaveLength(1);

      insert.mockRestore();
      remove.mockImplementation(originalRemove);

      // Recente: a coleta não toca — poderia ser um backup em curso.
      const cleaner = app.get(BackupPayloadCleaner);
      expect(await cleaner.sweep()).toBe(0);
      expect(fake.names()).toHaveLength(1);

      // Antigo o bastante: sai.
      clock.advance(OBJECT_STORAGE_ORPHAN_GRACE_MS + 1);
      expect(await cleaner.sweep()).toBe(1);
      expect(fake.names()).toEqual([]);
    });

    it('commit OK, resposta perdida, retry → replay, sem segundo objeto (§27)', async () => {
      await startLocal();
      const body = fixtureText('backup-v1-complete');

      const first = await post(body).expect(201);
      const retry = await post(body).expect(200);

      expect(retry.body.backupId).toBe(first.body.backupId);
      expect(await snapshotCount()).toBe(1);
      expect(backupObjectsOnDisk()).toHaveLength(1);
    });

    it('a coleta de órfãos nunca toca um objeto referenciado, nem um objeto recente sem linha', async () => {
      const fake = new InMemoryObjectStorageClient();
      await startWithFake(fake);
      const created = await post(fixtureText('backup-v1-complete')).expect(201);
      const referenced = (await snapshotRow(created.body.backupId as string)).storage_key!;

      // Um objeto sem linha, "gravado agora" por um upload em curso.
      await fake.write(
        'backups/aa/bb/aabb0000-0000-4000-8000-000000000000.json',
        Buffer.from('{}'),
        { contentType: 'application/json' },
      );
      // E um objeto estranho sob o prefixo, que não é nosso para apagar.
      await fake.write('backups/manual/anotacao.json', Buffer.from('{}'), {
        contentType: 'application/json',
      });
      fake.setCreatedAt('backups/manual/anotacao.json', NOW - 10 * OBJECT_STORAGE_ORPHAN_GRACE_MS);

      const cleaner = app.get(BackupPayloadCleaner);
      expect(await cleaner.sweep()).toBe(0);

      clock.advance(OBJECT_STORAGE_ORPHAN_GRACE_MS + 1);
      expect(await cleaner.sweep()).toBe(1);
      expect(fake.names().sort()).toEqual([referenced, 'backups/manual/anotacao.json'].sort());
    });
  });

  // ================================================================ idempotência concorrente

  describe('idempotência concorrente (§27)', () => {
    it('mesmo clientBackupId + mesmo payload em paralelo → 201 e 200, um objeto só', async () => {
      await startLocal();
      const body = JSON.stringify(
        withClientBackupId('backup-v1-minimal', '00000000-0000-4000-8000-000000000001'),
      );

      const [a, b] = await Promise.all([post(body), post(body)]);
      expect([a.status, b.status].sort()).toEqual([200, 201]);
      expect(a.body.backupId).toBe(b.body.backupId);
      expect(await snapshotCount()).toBe(1);

      // O perdedor limpou o próprio objeto; o do vencedor é o único que existe, e é o referenciado.
      const row = await snapshotRow(a.body.backupId as string);
      expect(backupObjectsOnDisk()).toEqual([row.storage_key]);
    });

    it('mesmo clientBackupId + payload diferente em paralelo → 201 e 409, e só o objeto do vencedor', async () => {
      await startLocal();
      const clientBackupId = '00000000-0000-4000-8000-000000000002';
      const bodyA = JSON.stringify(withClientBackupId('backup-v1-minimal', clientBackupId));
      const bodyB = JSON.stringify({
        ...withClientBackupId('backup-v1-minimal', clientBackupId),
        capturedAt: 1700000000000,
      });

      const [a, b] = await Promise.all([post(bodyA), post(bodyB)]);
      expect([a.status, b.status].sort()).toEqual([201, 409]);
      const winner = a.status === 201 ? a : b;
      expect(await snapshotCount()).toBe(1);
      const row = await snapshotRow(winner.body.backupId as string);
      expect(backupObjectsOnDisk()).toEqual([row.storage_key]);
      // E o vencedor continua restaurável, com o conteúdo dele.
      const bytes = (await content(winner.body.backupId as string).expect(200)).body as Buffer;
      expect(sha256(bytes)).toBe(winner.body.payloadHash);
    });
  });

  // ================================================================ retenção

  describe('retenção (§28/§29)', () => {
    const id = (index: number) => `0000000${index}-0000-4000-8000-000000000000`;

    it('com BACKUP_RETENTION_COUNT=5, seis snapshots deixam 5 linhas e 5 objetos — os referenciados', async () => {
      await startLocal({ BACKUP_RETENTION_COUNT: '5' });
      for (let index = 1; index <= 6; index += 1) {
        await post(JSON.stringify(withClientBackupId('backup-v1-minimal', id(index)))).expect(201);
      }

      expect(await snapshotCount()).toBe(5);
      const rows = await app
        .get(PostgresService)
        .query<{ storage_key: string }>(`SELECT storage_key FROM backup_snapshots`);
      const referenced = rows.rows.map((r) => r.storage_key).sort();
      expect(referenced).toHaveLength(5);
      expect(backupObjectsOnDisk()).toEqual(referenced);
    });

    it('o objeto só sai depois do commit da retenção: se o delete falhar, a linha já se foi e o objeto vira órfão', async () => {
      const fake = new InMemoryObjectStorageClient();
      await startWithFake(fake, { BACKUP_RETENTION_COUNT: '5' });
      for (let index = 1; index <= 5; index += 1) {
        await post(JSON.stringify(withClientBackupId('backup-v1-minimal', id(index)))).expect(201);
      }
      expect(fake.names()).toHaveLength(5);

      // O delete do objeto antigo falha; o backup novo e a retenção do banco seguem normais.
      fake.fail('remove');
      await post(JSON.stringify(withClientBackupId('backup-v1-minimal', id(6)))).expect(201);
      fake.restore();

      expect(await snapshotCount()).toBe(5);
      expect(fake.names()).toHaveLength(6);
      const rows = await app
        .get(PostgresService)
        .query<{ storage_key: string }>(`SELECT storage_key FROM backup_snapshots`);
      const referenced = new Set(rows.rows.map((r) => r.storage_key));
      const orphan = fake.names().find((name) => !referenced.has(name))!;
      expect(orphan).toBeDefined();

      // O órfão é o objeto do snapshot mais antigo — que não está mais na API.
      await request(app.getHttpServer())
        .get('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN}`)
        .expect(200)
        .expect((res) => {
          expect(
            res.body.items.map((i: { clientBackupId: string }) => i.clientBackupId),
          ).not.toContain(id(1));
        });

      // A coleta o recolhe depois da carência, e só ele.
      const cleaner = app.get(BackupPayloadCleaner);
      expect(await cleaner.sweep()).toBe(0);
      clock.advance(OBJECT_STORAGE_ORPHAN_GRACE_MS + 1);
      expect(await cleaner.sweep()).toBe(1);
      expect(fake.names().sort()).toEqual([...referenced].sort());
      expect(await snapshotCount()).toBe(5);
    });
  });
});
