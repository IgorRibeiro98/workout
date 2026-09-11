import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SparkLogger } from '../src/common/logger';
import { PostgresUrlIdentityError } from '../src/database/postgres-url';
import { DrBackupError, runDrBackup, type DrBackupDependencies } from '../src/dr/db-backup.runner';
import { DrBackupStore } from '../src/dr/dr-backup.store';
import {
  DR_POSTGRES_PREFIX,
  drBackupIdFor,
  drDumpObjectName,
  drManifestObjectName,
  parseDrManifest,
  serializeDrManifest,
  type DrBackupManifest,
} from '../src/dr/dr-manifest';
import { planDrRetention } from '../src/dr/dr-retention';
import { FakeClock } from './support/fake-clock';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { FakePgTools, fakeToolFailure } from './support/fake-pg-tools';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';

const PASSWORD = 'senha-que-nunca-entra-no-manifesto';

/**
 * T18.3 §2/§3/§8 — o backup de DR do PostgreSQL falha fechado, e a retenção preserva.
 *
 * O `pg_dump` é um dublê (`FakePgTools`); o Object Storage é o dublê em memória com o mesmo
 * contrato do bucket; o banco de onde saem versão, migrations e tabelas é o PostgreSQL real de
 * teste. O binário real é exercitado no ensaio do CI com a imagem (`ops/gcp/dr-backup-drill.sh`).
 */
describe('T18.3 — backup de DR do PostgreSQL', () => {
  let temp: TempDb;
  let storage: InMemoryObjectStorageClient;
  let tools: FakePgTools;
  let clock: FakeClock;
  let workDir: string;
  let directUrl: string;

  const deps = (overrides: Partial<DrBackupDependencies> = {}): DrBackupDependencies => ({
    databaseUrlDirect: directUrl,
    store: new DrBackupStore(storage),
    tools,
    clock,
    logger: new SparkLogger(configFor(temp.path)),
    retentionCount: 7,
    workDir,
    maxDumpBytes: 64 * 1024 * 1024,
    gitCommit: 'abcdef1234567',
    imageDigest: 'sha256:' + 'ab'.repeat(32),
    ...overrides,
  });

  beforeEach(async () => {
    temp = createTempDb();
    // As migrations reais no schema isolado: é de lá que o manifesto lê versão e tabelas.
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize();
    await postgres.close();
    // A URL de teste com a senha "visível", para provar que ela nunca chega ao manifesto.
    directUrl = temp.databaseUrl.replace('spark:spark@', `spark:${encodeURIComponent('spark')}@`);
    storage = new InMemoryObjectStorageClient();
    tools = new FakePgTools();
    clock = new FakeClock(Date.UTC(2026, 8, 11, 12, 0, 0));
    workDir = mkdtempSync(join(tmpdir(), 'spark-dr-test-'));
  });

  afterEach(() => {
    rmSync(workDir, { recursive: true, force: true });
    temp.cleanup();
  });

  // ================================================================ o caminho feliz

  it('backup válido: dump, hash, upload, releitura, manifesto por último, código de sucesso', async () => {
    const result = await runDrBackup(deps());

    expect(result.backupId).toBe('2026-09-11T120000Z');
    expect(result.dumpSizeBytes).toBe(tools.dumpBytes.length);
    expect(result.sha256).toBe(createHash('sha256').update(tools.dumpBytes).digest('hex'));
    expect(result.schemaVersion).toBe(2);
    expect(result.retention).toEqual({ kept: 1, removed: 0, ignored: 0 });

    expect(storage.names()).toEqual([
      `${DR_POSTGRES_PREFIX}2026-09-11T120000Z/database.dump`,
      `${DR_POSTGRES_PREFIX}2026-09-11T120000Z/manifest.json`,
    ]);
    expect(storage.metadataOf(drDumpObjectName(result.backupId))).toMatchObject({
      'spark-sha256': result.sha256,
    });

    const manifest = parseDrManifest((await storage.read(drManifestObjectName(result.backupId)))!);
    expect(manifest.databaseIdentity).toEqual({
      host: 'localhost',
      port: 5432,
      database: 'spark_dev',
    });
    expect(manifest.gitCommit).toBe('abcdef1234567');
    expect(manifest.imageDigest).toBe('sha256:' + 'ab'.repeat(32));
    expect(manifest.dumpFormat).toBe('pg_dump-custom');
    expect(manifest.postgresVersion).toContain('PostgreSQL');
    expect(manifest.pgDumpVersion).toContain('pg_dump');
    expect(manifest.schema.schemaVersion).toBe(2);
    expect(manifest.schema.migrations.map((m) => m.version)).toEqual([1, 2]);
    expect(manifest.schema.tables).toContain('schema_migrations');
    expect(manifest.schema.tables).toContain('backup_snapshots');
    // O `pg_dump` recebeu a URL — e só ele; o manifesto nunca.
    expect(tools.calls.dump).toEqual([directUrl]);
  });

  it('o manifesto nunca contém a connection string, a senha nem qualquer segredo', async () => {
    const result = await runDrBackup(deps());
    const text = (await storage.read(drManifestObjectName(result.backupId)))!.toString('utf8');
    // A URL inteira, a autoridade com senha, e qualquer forma de connection string.
    for (const forbidden of [directUrl, 'spark:spark@', 'postgresql://', 'postgres://', PASSWORD]) {
      expect(text).not.toContain(forbidden);
    }
    expect(text).not.toMatch(/HMAC|GEMINI|token|password/i);
  });

  // ================================================================ falha fechada

  it('pg_dump falha → nenhum objeto é gravado, db_backup_failed, erro propaga', async () => {
    tools.failDump = fakeToolFailure('pg_dump');
    await expect(runDrBackup(deps())).rejects.toThrow(DrBackupError);
    expect(storage.names()).toEqual([]);
  });

  it('dump vazio ou acima do teto é recusado antes de qualquer upload', async () => {
    tools.dumpBytes = Buffer.alloc(0);
    await expect(runDrBackup(deps())).rejects.toThrow(/vazio/);
    expect(storage.names()).toEqual([]);

    tools.dumpBytes = Buffer.alloc(2 * 1024 * 1024, 1);
    await expect(runDrBackup(deps({ maxDumpBytes: 1024 * 1024 }))).rejects.toThrow(
      /SPARK_DR_MAX_DUMP_BYTES/,
    );
    expect(storage.names()).toEqual([]);
  });

  it('pg_restore --list falha ou não vê schema_migrations → nada é gravado', async () => {
    tools.failListToc = fakeToolFailure('pg_restore');
    await expect(runDrBackup(deps())).rejects.toThrow(/pg_restore --list/);
    expect(storage.names()).toEqual([]);

    tools.failListToc = undefined;
    tools.toc = ['1; 0 0 TABLE public outra_tabela spark'];
    await expect(runDrBackup(deps())).rejects.toThrow(/schema_migrations/);
    expect(storage.names()).toEqual([]);
  });

  it('upload do dump falha → nenhum manifesto existe, e o backup não conta como válido', async () => {
    storage.fail('write');
    await expect(runDrBackup(deps())).rejects.toThrow(/upload do dump/);
    expect(storage.names()).toEqual([]);
    expect(await new DrBackupStore(storage).latestValid()).toBeNull();
  });

  it('manifesto falha → o dump fica como pasta INCOMPLETA: retenção ignora, latestValid é null', async () => {
    // O dublê grava o dump e falha só na segunda escrita (o manifesto).
    let writes = 0;
    const originalWrite = storage.write.bind(storage);
    storage.write = async (name, bytes, options) => {
      writes += 1;
      if (writes === 2) {
        storage.fail('write');
      }
      return originalWrite(name, bytes, options);
    };

    await expect(runDrBackup(deps())).rejects.toThrow(/upload do manifesto/);
    storage.restore();
    expect(storage.names()).toEqual([`${DR_POSTGRES_PREFIX}2026-09-11T120000Z/database.dump`]);

    const store = new DrBackupStore(storage);
    expect(await store.latestValid()).toBeNull();
    const statuses = await store.listStatuses();
    expect(statuses).toEqual([{ kind: 'incomplete', backupId: '2026-09-11T120000Z' }]);
    const plan = planDrRetention(statuses, 1);
    expect(plan.remove).toEqual([]);
    expect(plan.ignored).toHaveLength(1);
  });

  it('checksum mismatch na releitura → falha, e o manifesto nunca é gravado', async () => {
    const originalRead = storage.read.bind(storage);
    storage.read = async (name) => {
      const bytes = await originalRead(name);
      // Corrompe o que volta do bucket — um objeto que subiu diferente do que saiu do pg_dump.
      return bytes === null ? null : Buffer.concat([bytes.subarray(1), Buffer.from('x')]);
    };
    await expect(runDrBackup(deps())).rejects.toThrow(/SHA-256 lido difere/);
    expect(storage.names()).toEqual([`${DR_POSTGRES_PREFIX}2026-09-11T120000Z/database.dump`]);
  });

  it('URL sem database explícito falha fechada antes de qualquer pg_dump (§4)', async () => {
    await expect(
      runDrBackup(deps({ databaseUrlDirect: 'postgresql://spark:spark@localhost:5432' })),
    ).rejects.toThrow(PostgresUrlIdentityError);
    expect(tools.calls.dump).toEqual([]);
    expect(storage.names()).toEqual([]);
  });

  // ================================================================ retenção

  it('retenção remove só os válidos além do limite, do mais antigo; ignora incompletos; nunca o último', async () => {
    // Três backups válidos anteriores, um incompleto e um com manifesto ilegível.
    await seedValidBackup(storage, Date.UTC(2026, 8, 8, 3, 15));
    await seedValidBackup(storage, Date.UTC(2026, 8, 9, 3, 15));
    await seedValidBackup(storage, Date.UTC(2026, 8, 10, 3, 15));
    await storage.write(
      drDumpObjectName(drBackupIdFor(Date.UTC(2026, 8, 7, 3, 15))),
      Buffer.from('incompleto'),
      {
        contentType: 'application/octet-stream',
      },
    );
    await storage.write(
      drManifestObjectName(drBackupIdFor(Date.UTC(2026, 8, 6, 3, 15))),
      Buffer.from('{ não é json'),
      {
        contentType: 'application/json',
      },
    );
    await storage.write(
      drDumpObjectName(drBackupIdFor(Date.UTC(2026, 8, 6, 3, 15))),
      Buffer.from('x'),
      {
        contentType: 'application/octet-stream',
      },
    );

    const result = await runDrBackup(deps({ retentionCount: 2 }));
    expect(result.retention).toEqual({ kept: 2, removed: 2, ignored: 2 });

    const names = storage.names();
    // Ficam o novo e o de 10/09; saem 08/09 e 09/09; os dois inválidos ficam intocados.
    expect(names).toContain(drDumpObjectName('2026-09-11T120000Z'));
    expect(names).toContain(drDumpObjectName('2026-09-10T031500Z'));
    expect(names).not.toContain(drDumpObjectName('2026-09-09T031500Z'));
    expect(names).not.toContain(drDumpObjectName('2026-09-08T031500Z'));
    expect(names).toContain(drDumpObjectName('2026-09-07T031500Z'));
    expect(names).toContain(drManifestObjectName('2026-09-06T031500Z'));

    // Idempotente: rodar a retenção de novo sobre o mesmo estado não remove mais nada.
    const store = new DrBackupStore(storage);
    const again = planDrRetention(await store.listStatuses(), 2);
    expect(again.remove).toEqual([]);
    expect(again.keep.map((b) => b.backupId)).toEqual(['2026-09-11T120000Z', '2026-09-10T031500Z']);
  });

  it('planDrRetention nunca remove o único válido, mesmo com keepCount inválido', () => {
    const only = validStatus('2026-09-10T031500Z', Date.UTC(2026, 8, 10, 3, 15));
    expect(planDrRetention([only], 0).remove).toEqual([]);
    expect(planDrRetention([only], -5).keep).toHaveLength(1);
  });

  it('a retenção remove o manifesto antes do dump — uma falha no meio deixa uma pasta incompleta, nunca um manifesto órfão', async () => {
    await seedValidBackup(storage, Date.UTC(2026, 8, 9, 3, 15));
    await seedValidBackup(storage, Date.UTC(2026, 8, 10, 3, 15));
    const order: string[] = [];
    const originalRemove = storage.remove.bind(storage);
    storage.remove = async (name) => {
      order.push(name);
      return originalRemove(name);
    };

    await runDrBackup(deps({ retentionCount: 2 }));
    expect(order).toEqual([
      drManifestObjectName('2026-09-09T031500Z'),
      drDumpObjectName('2026-09-09T031500Z'),
    ]);
  });
});

// ---------------------------------------------------------------- helpers

function manifestFor(
  backupId: string,
  createdAtEpochMs: number,
  dumpBytes: Buffer,
): DrBackupManifest {
  return {
    formatVersion: 1,
    backupId,
    createdAt: new Date(createdAtEpochMs).toISOString(),
    createdAtEpochMs,
    databaseIdentity: { host: 'localhost', port: 5432, database: 'spark_dev' },
    gitCommit: null,
    imageDigest: null,
    dumpFormat: 'pg_dump-custom',
    dumpObject: drDumpObjectName(backupId),
    dumpSizeBytes: dumpBytes.length,
    sha256: createHash('sha256').update(dumpBytes).digest('hex'),
    postgresVersion: 'PostgreSQL 17 (teste)',
    pgDumpVersion: 'pg_dump 17 (teste)',
    tocEntries: 2,
    schema: { schemaVersion: 2, migrations: [], tables: ['schema_migrations'] },
  };
}

async function seedValidBackup(
  storage: InMemoryObjectStorageClient,
  createdAtEpochMs: number,
): Promise<void> {
  const backupId = drBackupIdFor(createdAtEpochMs);
  const dumpBytes = Buffer.from(`dump-${backupId}`);
  await storage.write(drDumpObjectName(backupId), dumpBytes, {
    contentType: 'application/octet-stream',
  });
  await storage.write(
    drManifestObjectName(backupId),
    serializeDrManifest(manifestFor(backupId, createdAtEpochMs, dumpBytes)),
    { contentType: 'application/json' },
  );
}

function validStatus(backupId: string, createdAtEpochMs: number) {
  return {
    kind: 'valid' as const,
    backup: { backupId, manifest: manifestFor(backupId, createdAtEpochMs, Buffer.from('x')) },
  };
}
