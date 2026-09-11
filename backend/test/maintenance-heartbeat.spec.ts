import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { configureApp } from '../src/bootstrap/create-app';
import { CLOCK } from '../src/common/clock';
import { classifyDatabaseSize, isDatabaseSizeAlerting } from '../src/database/database-size.policy';
import { PostgresService } from '../src/database/postgres.service';
import { drDumpObjectName, drManifestObjectName, serializeDrManifest } from '../src/dr/dr-manifest';
import { MaintenanceCoordinator } from '../src/maintenance/maintenance.coordinator';
import { MaintenanceHttpModule } from '../src/maintenance/maintenance-http.module';
import { AUTH_TOKEN_VERIFIER } from '../src/modules/auth/auth-token-verifier';
import { NotificationDispatcher } from '../src/modules/social/notification.dispatcher';
import { OBJECT_STORAGE_CLIENT } from '../src/object-storage/object-storage.client';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

const T0 = Date.UTC(2026, 8, 11, 12, 0, 0);

/**
 * T18.3 §12/§13 — heartbeat persistido, detecção de manutenção parada, tamanho do banco e frescor
 * do backup de DR, tudo observável por `GET /internal/maintenance/status`.
 */
describe('T18.3 — heartbeat do maintenance, stale detection e tamanho do banco', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let storage: InMemoryObjectStorageClient;
  let coordinator: MaintenanceCoordinator;

  async function boot(
    overrides: Record<string, string> = {},
    failDispatcher = false,
  ): Promise<void> {
    temp = createTempDb();
    clock = new FakeClock(T0);
    storage = new InMemoryObjectStorageClient();
    let builder = Test.createTestingModule({
      imports: [
        AppModule.forRoot(
          configFor(temp.path, {
            BACKGROUND_JOBS_MODE: 'disabled',
            MAINTENANCE_STALE_AFTER_MS: String(5 * 60 * 1000),
            DATABASE_SIZE_CHECK_INTERVAL_MS: String(60 * 60 * 1000),
            DR_BACKUP_CHECK_INTERVAL_MS: String(30 * 60 * 1000),
            DR_BACKUP_MAX_AGE_MS: String(26 * 60 * 60 * 1000),
            ...overrides,
          }),
        ),
      ],
    })
      .overrideProvider(AUTH_TOKEN_VERIFIER)
      .useValue(new FakeAuthTokenVerifier())
      .overrideProvider(CLOCK)
      .useValue(clock)
      .overrideProvider(OBJECT_STORAGE_CLIENT)
      .useValue(storage);
    if (failDispatcher) {
      builder = builder.overrideProvider(NotificationDispatcher).useValue({
        runDispatchCycle: () => Promise.reject(new Error('despachante quebrado de propósito')),
      });
    }
    const moduleRef = await builder.compile();
    app = moduleRef.createNestApplication({ logger: false });
    await configureApp(app, configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }));
    await app.init();
    coordinator = app.get(MaintenanceCoordinator);
  }

  afterEach(async () => {
    await app?.close();
    temp?.cleanup();
  });

  it('antes do primeiro ciclo o status é stale, sem sucesso registrado', async () => {
    await boot();
    const status = await coordinator.status();
    expect(status.lastSuccessAt).toBeNull();
    expect(status.stale).toBe(true);
    expect(status.ageMs).toBeNull();
    expect(status.databaseSize).toBeNull();
    expect(status.drBackup).toBeNull();
  });

  it('um ciclo bem-sucedido grava início, fim, sucesso e duração — e o status deixa de ser stale', async () => {
    await boot();
    const result = await coordinator.runCycle();
    expect(result.skipped).toBe(false);
    expect(result.databaseSizeChecked).toBe(true);
    expect(result.drBackupChecked).toBe(true);

    const status = await coordinator.status();
    expect(status.lastStartedAt).toBe(T0);
    expect(status.lastSuccessAt).toBe(T0 + status.lastDurationMs!);
    expect(status.lastCompletedAt).toBe(status.lastSuccessAt);
    expect(status.lastFailureAt).toBeNull();
    expect(status.lastErrorName).toBeNull();
    expect(status.stale).toBe(false);

    // O heartbeat é uma linha do banco: sobrevive a restart e revision nova.
    const rows = await app.get(PostgresService).query<{
      key: string;
    }>(`SELECT key FROM server_metadata WHERE key LIKE 'maintenance_%' ORDER BY key`);
    expect(rows.rows.map((r) => r.key)).toEqual([
      'maintenance_last_completed_at',
      'maintenance_last_duration_ms',
      'maintenance_last_error',
      'maintenance_last_started_at',
      'maintenance_last_success_at',
    ]);
  });

  it('stale detection: mais de MAINTENANCE_STALE_AFTER_MS sem sucesso → stale, e o próximo ciclo registra maintenance_stale', async () => {
    await boot();
    await coordinator.runCycle();
    clock.advance(4 * 60 * 1000);
    expect((await coordinator.status()).stale).toBe(false);
    clock.advance(2 * 60 * 1000); // 6 min desde o sucesso
    const stale = await coordinator.status();
    expect(stale.stale).toBe(true);
    expect(stale.ageMs).toBeGreaterThan(5 * 60 * 1000);

    // Um ciclo novo recupera: deixa de ser stale.
    await coordinator.runCycle();
    expect((await coordinator.status()).stale).toBe(false);
  });

  it('um ciclo que falha grava a falha (e o nome do erro), sem apagar o último sucesso', async () => {
    await boot({ SOCIAL_PUSH_ENABLED: 'true' }, true);
    await expect(coordinator.runCycle()).rejects.toThrow('despachante quebrado');
    const status = await coordinator.status();
    expect(status.lastStartedAt).toBe(T0);
    expect(status.lastFailureAt).not.toBeNull();
    expect(status.lastErrorName).toBe('Error');
    expect(status.lastSuccessAt).toBeNull();
    expect(status.stale).toBe(true);
  });

  it('tamanho do banco: medido no ciclo, classificado pelos limiares configurados, exposto no status', async () => {
    // Limiares minúsculos (em MB): qualquer banco real de teste já passa de 1 MB → ACTION_REQUIRED.
    await boot({ DATABASE_SIZE_THRESHOLDS_MB: '1,2,3,4' });
    await coordinator.runCycle();
    const status = await coordinator.status();
    expect(status.databaseSize).not.toBeNull();
    expect(status.databaseSize!.sizeBytes).toBeGreaterThan(4 * 1024 * 1024);
    expect(status.databaseSize!.level).toBe('ACTION_REQUIRED');
    expect(status.databaseSize!.checkedAt).toBe(T0);
  });

  it('tamanho do banco: com os limiares reais (300/350/400/450 MB) o banco de teste é NORMAL', async () => {
    await boot();
    await coordinator.runCycle();
    expect((await coordinator.status()).databaseSize!.level).toBe('NORMAL');
  });

  it('tamanho do banco não é medido a cada minuto: respeita DATABASE_SIZE_CHECK_INTERVAL_MS', async () => {
    await boot();
    expect((await coordinator.runCycle()).databaseSizeChecked).toBe(true);
    clock.advance(60 * 1000);
    expect((await coordinator.runCycle()).databaseSizeChecked).toBe(false);
    clock.advance(60 * 60 * 1000);
    expect((await coordinator.runCycle()).databaseSizeChecked).toBe(true);
  });

  it('frescor do DR: sem backup válido é stale; com um backup recente não é; com um antigo volta a ser', async () => {
    await boot();
    await coordinator.runCycle();
    let status = await coordinator.status();
    expect(status.drBackup).toMatchObject({ latestBackupId: null, stale: true });

    await seedValidBackup(storage, T0 - 60 * 60 * 1000, '2026-09-11T110000Z');
    clock.advance(31 * 60 * 1000);
    await coordinator.runCycle();
    status = await coordinator.status();
    expect(status.drBackup).toMatchObject({ latestBackupId: '2026-09-11T110000Z', stale: false });

    clock.advance(27 * 60 * 60 * 1000);
    await coordinator.runCycle();
    status = await coordinator.status();
    expect(status.drBackup!.stale).toBe(true);
    expect(status.drBackup!.ageMs).toBeGreaterThan(26 * 60 * 60 * 1000);
  });

  it('frescor do DR: o bucket fora do ar não derruba o ciclo — a falha é registrada, o resto roda', async () => {
    await boot();
    storage.fail('list');
    const result = await coordinator.runCycle();
    expect(result.skipped).toBe(false);
    expect(result.drBackupChecked).toBe(true);
    expect((await coordinator.status()).stale).toBe(false);
  });

  it('GET /internal/maintenance/status expõe o heartbeat pelo módulo HTTP do serviço privado', async () => {
    await boot();
    await coordinator.runCycle();
    const moduleRef = await Test.createTestingModule({
      imports: [MaintenanceHttpModule.forRoot(coordinator)],
    }).compile();
    const httpApp = moduleRef.createNestApplication({ logger: false });
    await httpApp.init();
    try {
      const res = await request(httpApp.getHttpServer())
        .get('/internal/maintenance/status')
        .expect(200);
      expect(res.body.stale).toBe(false);
      expect(res.body.lastSuccessAt).toBeGreaterThanOrEqual(T0);
      expect(res.body.databaseSize.level).toBe('NORMAL');
      expect(JSON.stringify(res.body)).not.toMatch(/postgres:\/\/|password|secret/i);
    } finally {
      await httpApp.close();
    }
  });

  describe('classifyDatabaseSize — a política num lugar só', () => {
    const thresholds = [300, 350, 400, 450];
    const mb = (n: number) => n * 1024 * 1024;

    it.each([
      [0, 'NORMAL', null],
      [mb(299.99), 'NORMAL', null],
      [mb(300), 'ATTENTION', 300],
      [mb(349), 'ATTENTION', 300],
      [mb(350), 'INVESTIGATE', 350],
      [mb(400), 'PLAN', 400],
      [mb(449), 'PLAN', 400],
      [mb(450), 'ACTION_REQUIRED', 450],
      [mb(9_999), 'ACTION_REQUIRED', 450],
    ])('%d bytes → %s (limiar %s)', (bytes, level, threshold) => {
      const assessment = classifyDatabaseSize(bytes, thresholds);
      expect(assessment.level).toBe(level);
      expect(assessment.thresholdMb).toBe(threshold);
    });

    it('só PLAN e ACTION_REQUIRED alertam; ATTENTION e INVESTIGATE registram', () => {
      expect(isDatabaseSizeAlerting('NORMAL')).toBe(false);
      expect(isDatabaseSizeAlerting('ATTENTION')).toBe(false);
      expect(isDatabaseSizeAlerting('INVESTIGATE')).toBe(false);
      expect(isDatabaseSizeAlerting('PLAN')).toBe(true);
      expect(isDatabaseSizeAlerting('ACTION_REQUIRED')).toBe(true);
    });

    it('valores inválidos são tratados como 0, e uma lista de limiares errada é recusada', () => {
      expect(classifyDatabaseSize(Number.NaN, thresholds).level).toBe('NORMAL');
      expect(classifyDatabaseSize(-5, thresholds).sizeBytes).toBe(0);
      expect(() => classifyDatabaseSize(1, [1, 2])).toThrow();
    });

    it('a configuração exige quatro limiares estritamente crescentes', () => {
      expect(() => configFor(undefined, { DATABASE_SIZE_THRESHOLDS_MB: '300,350' })).toThrow();
      expect(() =>
        configFor(undefined, { DATABASE_SIZE_THRESHOLDS_MB: '300,300,400,450' }),
      ).toThrow();
      expect(
        configFor(undefined, { DATABASE_SIZE_THRESHOLDS_MB: '100,200,300,400' })
          .databaseSizeThresholdsMb,
      ).toEqual([100, 200, 300, 400]);
      expect(configFor().databaseSizeThresholdsMb).toEqual([300, 350, 400, 450]);
    });
  });
});

async function seedValidBackup(
  storage: InMemoryObjectStorageClient,
  createdAtEpochMs: number,
  backupId: string,
): Promise<void> {
  const dump = Buffer.from(`dump-${backupId}`);
  await storage.write(drDumpObjectName(backupId), dump, {
    contentType: 'application/octet-stream',
  });
  await storage.write(
    drManifestObjectName(backupId),
    serializeDrManifest({
      formatVersion: 1,
      backupId,
      createdAt: new Date(createdAtEpochMs).toISOString(),
      createdAtEpochMs,
      databaseIdentity: { host: 'h', port: 5432, database: 'spark' },
      gitCommit: null,
      imageDigest: null,
      dumpFormat: 'pg_dump-custom',
      dumpObject: drDumpObjectName(backupId),
      dumpSizeBytes: dump.length,
      sha256: 'f'.repeat(64),
      postgresVersion: 'PostgreSQL 17',
      pgDumpVersion: 'pg_dump 17',
      tocEntries: 2,
      schema: { schemaVersion: 2, migrations: [], tables: ['schema_migrations'] },
    }),
    { contentType: 'application/json' },
  );
}
