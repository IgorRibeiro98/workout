import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Client } from 'pg';
import { SparkLogger } from '../src/common/logger';
import { PostgresUrlIdentityError } from '../src/database/postgres-url';
import { runDrBackup } from '../src/dr/db-backup.runner';
import {
  DrRestoreDrillError,
  runDrRestoreDrill,
  withDatabase,
  type DrRestoreDrillDependencies,
} from '../src/dr/db-restore-drill.runner';
import { DrBackupStore } from '../src/dr/dr-backup.store';
import {
  drDumpObjectName,
  drManifestObjectName,
  parseDrManifest,
  serializeDrManifest,
} from '../src/dr/dr-manifest';
import { FakeClock } from './support/fake-clock';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { FakePgTools } from './support/fake-pg-tools';
import {
  configFor,
  createTempDb,
  DEFAULT_TEST_DATABASE_URL,
  MIGRATIONS_DIR,
  postgresFor,
  type TempDb,
} from './support/temp-db';

/** A conexão administrativa do ensaio: o banco de manutenção do servidor de teste, nunca `spark_dev`. */
const ADMIN_URL = withDatabase(
  DEFAULT_TEST_DATABASE_URL.replace(/[?&]options=[^&]+/g, ''),
  'postgres',
);

/**
 * T18.3 §5/§6/§7 — o ensaio de restauração só restaura em destino limpo, explícito e nunca em
 * produção.
 *
 * O `pg_restore` é um dublê que aplica um schema real no destino (o que um dump de verdade
 * produziria); `CREATE DATABASE`/`DROP DATABASE`, a verificação do restaurado, as migrations
 * pendentes e a readiness rodam contra o PostgreSQL real de teste. O binário real é exercitado
 * no ensaio do CI com a imagem (`ops/gcp/dr-backup-drill.sh`).
 */
describe('T18.3 — ensaio de restauração em destino limpo', () => {
  let temp: TempDb;
  let storage: InMemoryObjectStorageClient;
  let tools: FakePgTools;
  let clock: FakeClock;
  let workDir: string;
  let backupId: string;
  const createdDatabases: string[] = [];

  const uniqueDrill = (): string => {
    const name = `spark_drill_t_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
    createdDatabases.push(name);
    return name;
  };

  const deps = (
    overrides: Partial<DrRestoreDrillDependencies> = {},
  ): DrRestoreDrillDependencies => ({
    store: new DrBackupStore(storage),
    tools,
    clock,
    logger: new SparkLogger(configFor(temp.path)),
    workDir,
    backupId: null,
    adminUrl: ADMIN_URL,
    drillDatabase: uniqueDrill(),
    keepDatabase: false,
    replaceExisting: false,
    migrationsDirectory: MIGRATIONS_DIR,
    ...overrides,
  });

  const admin = async <T>(work: (client: Client) => Promise<T>): Promise<T> => {
    const client = new Client({ connectionString: ADMIN_URL });
    await client.connect();
    try {
      return await work(client);
    } finally {
      await client.end();
    }
  };

  const databaseExists = (name: string) =>
    admin(async (client) => {
      const res = await client.query('SELECT 1 FROM pg_database WHERE datname = $1', [name]);
      return (res.rowCount ?? 0) > 0;
    });

  beforeEach(async () => {
    temp = createTempDb();
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize();
    await postgres.close();
    storage = new InMemoryObjectStorageClient();
    tools = new FakePgTools();
    clock = new FakeClock(Date.UTC(2026, 8, 11, 12, 0, 0));
    workDir = mkdtempSync(join(tmpdir(), 'spark-drill-test-'));

    // Um backup de verdade (pelo runner de backup, com o dump falso) — é dele que sai o manifesto
    // com as tabelas reais do schema, que o ensaio confere contra o destino.
    const result = await runDrBackup({
      databaseUrlDirect: temp.databaseUrl,
      store: new DrBackupStore(storage),
      tools,
      clock,
      logger: new SparkLogger(configFor(temp.path)),
      retentionCount: 7,
      workDir,
      maxDumpBytes: 64 * 1024 * 1024,
      gitCommit: null,
      imageDigest: null,
    });
    backupId = result.backupId;
  });

  afterEach(async () => {
    rmSync(workDir, { recursive: true, force: true });
    for (const name of createdDatabases.splice(0)) {
      await admin((client) => client.query(`DROP DATABASE IF EXISTS "${name}" WITH (FORCE)`)).catch(
        () => undefined,
      );
    }
    temp.cleanup();
  });

  // ================================================================ guardas

  it('exige destino explícito na forma spark_drill_*: outro nome é recusado sem tocar em nada', async () => {
    for (const name of [
      'spark',
      'spark_dev',
      'postgres',
      'drill',
      'spark_drill',
      'Spark_drill_x',
    ]) {
      await expect(runDrRestoreDrill(deps({ drillDatabase: name }))).rejects.toThrow(
        DrRestoreDrillError,
      );
    }
    expect(tools.calls.restore).toEqual([]);
  });

  it('recusa produção como destino: o nome do banco do manifesto nunca é aceito', async () => {
    // O manifesto descreve `spark_dev` (o "banco de produção" deste teste). Um destino com esse
    // nome não passa nem pela forma; e se passasse, cairia na segunda guarda.
    const manifest = parseDrManifest((await storage.read(drManifestObjectName(backupId)))!);
    expect(manifest.databaseIdentity.database).toBe('spark_dev');
    await expect(
      runDrRestoreDrill(deps({ drillDatabase: manifest.databaseIdentity.database })),
    ).rejects.toThrow(/spark_drill/);
    expect(tools.calls.restore).toEqual([]);
  });

  it('recusa a conexão administrativa que É o banco de produção do manifesto', async () => {
    const productionAdmin = DEFAULT_TEST_DATABASE_URL.replace(/[?&]options=[^&]+/g, '');
    await expect(runDrRestoreDrill(deps({ adminUrl: productionAdmin }))).rejects.toThrow(
      /É o banco de produção/,
    );
    expect(tools.calls.restore).toEqual([]);
  });

  it('conexão administrativa sem database explícito falha fechada (§4)', async () => {
    await expect(
      runDrRestoreDrill(deps({ adminUrl: 'postgresql://spark:spark@localhost:5432' })),
    ).rejects.toThrow(PostgresUrlIdentityError);
    expect(tools.calls.restore).toEqual([]);
  });

  it('checksum inválido: falha antes de criar qualquer banco', async () => {
    storage.corrupt(drDumpObjectName(backupId), Buffer.from('bytes que não são o dump'));
    // O tamanho também muda, então o backup deixa de ser "válido" para a listagem; com o id
    // explícito a recusa aponta o motivo.
    const drill = uniqueDrill();
    await expect(runDrRestoreDrill(deps({ backupId, drillDatabase: drill }))).rejects.toThrow(
      DrRestoreDrillError,
    );
    expect(await databaseExists(drill)).toBe(false);
    expect(tools.calls.restore).toEqual([]);
  });

  it('SHA-256 divergente com o mesmo tamanho é recusado pelo checksum', async () => {
    const original = (await storage.read(drDumpObjectName(backupId)))!;
    const tampered = Buffer.from(original);
    tampered[0] = tampered[0] ^ 0xff;
    storage.corrupt(drDumpObjectName(backupId), tampered);
    const drill = uniqueDrill();
    await expect(runDrRestoreDrill(deps({ drillDatabase: drill }))).rejects.toThrow(/SHA-256/);
    expect(await databaseExists(drill)).toBe(false);
  });

  it('um banco de ensaio que já existe não é reutilizado: só --replace o descarta', async () => {
    const drill = uniqueDrill();
    await admin((client) => client.query(`CREATE DATABASE "${drill}"`));
    await expect(runDrRestoreDrill(deps({ drillDatabase: drill }))).rejects.toThrow(/já existe/);
    expect(tools.calls.restore).toEqual([]);
  });

  // ================================================================ o ensaio que passa

  it('restaura em banco novo, confere schema e tabelas, sobe a aplicação e descarta o banco', async () => {
    const drill = uniqueDrill();
    const result = await runDrRestoreDrill(deps({ drillDatabase: drill }));

    expect(result.verdict).toBe('RESTORE_DRILL_PASS');
    expect(result.backupId).toBe(backupId);
    expect(result.restoredSchemaVersion).toBe(2);
    expect(result.migrationsAppliedDuringDrill).toBe(0);
    expect(result.finalSchemaVersion).toBe(2);
    expect(result.tables).toBeGreaterThan(30);
    expect(Object.keys(result.essentialCounts).sort()).toEqual([
      'account_deletion_tombstones',
      'backup_snapshots',
      'server_metadata',
      'social_profiles',
      'sync_entities',
    ]);
    // O restore recebeu a URL do banco de ensaio — nunca a de produção.
    expect(tools.calls.restore).toHaveLength(1);
    expect(tools.calls.restore[0]).toContain(`/${drill} `);
    expect(tools.calls.restore[0]).not.toContain('/spark_dev');
    // Descartado ao final (default).
    expect(await databaseExists(drill)).toBe(false);
  });

  it('--keep mantém o banco de ensaio para inspeção', async () => {
    const drill = uniqueDrill();
    await runDrRestoreDrill(deps({ drillDatabase: drill, keepDatabase: true }));
    expect(await databaseExists(drill)).toBe(true);
  });

  it('snapshot antigo + schema posteriormente expandido: o objeto novo NÃO sobrevive ao restore limpo (§7)', async () => {
    // O backup descreve a versão 1 do schema (as tabelas são as mesmas: a 0002 só acrescenta
    // coluna e índice), e o "dump" restaura só até a 1.
    const manifest = parseDrManifest((await storage.read(drManifestObjectName(backupId)))!);
    const oldManifest = {
      ...manifest,
      schema: {
        ...manifest.schema,
        schemaVersion: 1,
        migrations: manifest.schema.migrations.slice(0, 1),
      },
    };
    storage.corrupt(drManifestObjectName(backupId), serializeDrManifest(oldManifest));
    tools.restoreUpToVersion = 1;

    // O destino "de um ensaio anterior" tem um objeto que o snapshot antigo não conhece — o
    // cenário de `ops/tests/restore-old-snapshot-risk.test.sh`. Com `--replace`, o banco é
    // descartado e recriado do zero; sem isso, é recusado.
    const drill = uniqueDrill();
    await admin((client) => client.query(`CREATE DATABASE "${drill}"`));
    const stale = new Client({ connectionString: withDatabase(ADMIN_URL, drill) });
    await stale.connect();
    await stale.query('CREATE TABLE tabela_de_schema_mais_novo (id INT PRIMARY KEY)');
    await stale.end();

    const result = await runDrRestoreDrill(
      deps({ drillDatabase: drill, replaceExisting: true, keepDatabase: true }),
    );
    expect(result.verdict).toBe('RESTORE_DRILL_PASS');
    expect(result.restoredSchemaVersion).toBe(1);
    // A aplicação subiu sobre o snapshot antigo aplicando a migration que faltava (0002).
    expect(result.migrationsAppliedDuringDrill).toBe(1);
    expect(result.finalSchemaVersion).toBe(2);

    const restored = new Client({ connectionString: withDatabase(ADMIN_URL, drill) });
    await restored.connect();
    try {
      const extra = await restored.query(
        `SELECT 1 FROM information_schema.tables WHERE table_name = 'tabela_de_schema_mais_novo'`,
      );
      expect(extra.rowCount).toBe(0);
      const versions = await restored.query<{ version: number }>(
        'SELECT version FROM schema_migrations ORDER BY version',
      );
      expect(versions.rows.map((r) => Number(r.version))).toEqual([1, 2]);
    } finally {
      await restored.end();
    }
  });

  it('destino que não é exatamente o snapshot é reprovado: tabelas a mais reprovam o ensaio', async () => {
    // Um "dump" que restaura o schema completo, mas cujo manifesto declara menos tabelas: o
    // ensaio precisa acusar a diferença — é a mesma comparação que prova o destino limpo.
    const manifest = parseDrManifest((await storage.read(drManifestObjectName(backupId)))!);
    storage.corrupt(
      drManifestObjectName(backupId),
      serializeDrManifest({
        ...manifest,
        schema: { ...manifest.schema, tables: manifest.schema.tables.slice(0, -1) },
      }),
    );
    const drill = uniqueDrill();
    await expect(runDrRestoreDrill(deps({ drillDatabase: drill }))).rejects.toThrow(
      /não são exatamente as do manifesto/,
    );
    expect(await databaseExists(drill)).toBe(false);
  });
});
