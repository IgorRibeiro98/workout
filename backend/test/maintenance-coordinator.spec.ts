import { INestApplication } from '@nestjs/common';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { MaintenanceCoordinator } from '../src/maintenance/maintenance.coordinator';
import { PostgresService } from '../src/database/postgres.service';
import { SparkLogger } from '../src/common/logger';
import { SocialMediaCleaner } from '../src/modules/social/social-media.cleaner';
import { BackupPayloadCleaner } from '../src/modules/backup/backup-payload.cleaner';

/**
 * T18.2 §37–§39 — o ciclo de manutenção do Cloud Run.
 *
 * ## O que a corrida prova
 *
 * Duas chamadas de `runCycle()` disparadas sem esperar uma pela outra (`Promise.all`, nunca
 * sequencial) são o cenário real que o Cloud Scheduler pode produzir: um retry chegando antes da
 * primeira invocação terminar. `pg_try_advisory_lock` é não-bloqueante — a segunda chamada nunca
 * espera a primeira, ela **desiste** na hora e devolve `skipped: true`. É por isso que o teste não
 * precisa de nenhum truque de sincronização: a corrida real já produz um resultado determinístico
 * (uma corrida, um `skipped`), porque só uma das duas consegue o lock.
 *
 * ## O lock é de transação (T18.3)
 *
 * Em produção `DATABASE_URL` é o endpoint pooled do Neon (PgBouncer em modo transação), onde um
 * lock consultivo de SESSÃO fica preso numa conexão de servidor que o `unlock` nunca reencontra —
 * aconteceu no primeiro dia de heartbeat: todo ciclo `skipped_locked`, `maintenance_stale`. Os
 * três últimos testes fixam o desenho que corrige isso: lock `xact` numa transação aberta pelo
 * ciclo, chave nova (um lock de sessão vazado sob a chave antiga não bloqueia), e nenhum lock de
 * sessão no código do coordenador.
 */
describe('T18.2 — MaintenanceCoordinator', () => {
  let temp: TempDb;
  let app: INestApplication;

  afterEach(async () => {
    await app?.close();
    temp?.cleanup();
  });

  it('duas chamadas concorrentes: só uma processa, a outra é skipped — nunca as duas', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }),
      new FakeAuthTokenVerifier(),
    );
    const coordinator = app.get(MaintenanceCoordinator);

    const [first, second] = await Promise.all([coordinator.runCycle(), coordinator.runCycle()]);

    const skippedCount = [first, second].filter((r) => r.skipped).length;
    const ranCount = [first, second].filter((r) => !r.skipped).length;
    expect(skippedCount).toBe(1);
    expect(ranCount).toBe(1);
  });

  it('chamadas sequenciais processam as duas — o lock libera ao final do ciclo', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }),
      new FakeAuthTokenVerifier(),
    );
    const coordinator = app.get(MaintenanceCoordinator);

    const first = await coordinator.runCycle();
    const second = await coordinator.runCycle();

    expect(first.skipped).toBe(false);
    expect(second.skipped).toBe(false);
  });

  it('o lock é de transação: outra sessão segurando-o numa transação aberta faz o ciclo desistir; ao fim dela, o ciclo volta', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }),
      new FakeAuthTokenVerifier(),
    );
    const coordinator = app.get(MaintenanceCoordinator);
    const other = await app.get(PostgresService).pool.connect();
    try {
      await other.query('BEGIN');
      const { rows } = await other.query<{ locked: boolean }>(
        `SELECT pg_try_advisory_xact_lock(hashtext('spark_maintenance_cycle_xact')) AS locked`,
      );
      expect(rows[0]?.locked).toBe(true);

      expect((await coordinator.runCycle()).skipped).toBe(true);

      await other.query('ROLLBACK');
      // O fim da transação alheia libera o lock — sem `unlock` explícito, sem sessão a reencontrar.
      expect((await coordinator.runCycle()).skipped).toBe(false);
      expect((await coordinator.runCycle()).skipped).toBe(false);
    } finally {
      other.release();
    }
  });

  it('um lock de SESSÃO vazado sob a chave antiga (T18.2) não bloqueia o ciclo', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }),
      new FakeAuthTokenVerifier(),
    );
    const coordinator = app.get(MaintenanceCoordinator);
    const leaked = await app.get(PostgresService).pool.connect();
    try {
      await leaked.query(`SELECT pg_advisory_lock(hashtext('spark_maintenance_cycle'))`);
      expect((await coordinator.runCycle()).skipped).toBe(false);
    } finally {
      await leaked.query('SELECT pg_advisory_unlock_all()').catch(() => undefined);
      leaked.release();
    }
  });

  it('o coordenador nunca usa lock consultivo de sessão — o endpoint pooled do Neon não o suporta', () => {
    const source = readFileSync(
      resolve(__dirname, '../src/maintenance/maintenance.coordinator.ts'),
      'utf8',
    );
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
    expect(code).not.toMatch(/pg_(try_)?advisory_lock\s*\(/);
    expect(code).not.toMatch(/pg_advisory_unlock(_all)?\s*\(/);
    expect(code).toMatch(/pg_try_advisory_xact_lock\s*\(/);
    expect(code).toMatch(/query\('BEGIN'\)/);
  });

  it('respeita SOCIAL_PUSH_ENABLED: sem ele, notificação nunca é despachada no ciclo', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled', SOCIAL_PUSH_ENABLED: 'false' }),
      new FakeAuthTokenVerifier(),
    );
    const result = await app.get(MaintenanceCoordinator).runCycle();
    expect(result.notificationsDispatched).toBe(false);
  });

  it('cadência: o segundo ciclo, logo depois do primeiro, não repete a varredura de mídia/backup (§38)', async () => {
    temp = createTempDb();
    const clock = new FakeClock(1_000_000);
    app = await createTestApp(
      configFor(temp.path, {
        BACKGROUND_JOBS_MODE: 'disabled',
        SOCIAL_MEDIA_CLEANUP_INTERVAL_MS: String(15 * 60 * 1000),
        BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS: String(6 * 60 * 60 * 1000),
      }),
      new FakeAuthTokenVerifier(),
      undefined,
      clock,
    );
    const coordinator = app.get(MaintenanceCoordinator);

    const first = await coordinator.runCycle();
    expect(first.socialMediaSweepRan).toBe(true);
    expect(first.backupPayloadSweepRan).toBe(true);

    clock.advance(1_000); // um segundo depois — bem dentro da janela de 15 min / 6 h.
    const second = await coordinator.runCycle();
    expect(second.socialMediaSweepRan).toBe(false);
    expect(second.backupPayloadSweepRan).toBe(false);
  });

  it('cadência: depois que o intervalo passa, a varredura roda de novo', async () => {
    temp = createTempDb();
    const clock = new FakeClock(1_000_000);
    app = await createTestApp(
      configFor(temp.path, {
        BACKGROUND_JOBS_MODE: 'disabled',
        SOCIAL_MEDIA_CLEANUP_INTERVAL_MS: String(15 * 60 * 1000),
        BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS: String(6 * 60 * 60 * 1000),
      }),
      new FakeAuthTokenVerifier(),
      undefined,
      clock,
    );
    const coordinator = app.get(MaintenanceCoordinator);

    await coordinator.runCycle();
    clock.advance(16 * 60 * 1000); // passou a janela de mídia (15 min), não a de backup (6 h).

    const second = await coordinator.runCycle();
    expect(second.socialMediaSweepRan).toBe(true);
    expect(second.backupPayloadSweepRan).toBe(false);
  });

  it('a cadência não impede a chamada direta de sweep() fora do coordenador', async () => {
    // O throttle é do ciclo de manutenção, não do worker: nada nele desliga `sweep()` para quem
    // chama diretamente (um teste do próprio worker, por exemplo).
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }),
      new FakeAuthTokenVerifier(),
    );
    await app.get(MaintenanceCoordinator).runCycle();
    await expect(app.get(SocialMediaCleaner).sweep()).resolves.toBe(0);
    await expect(app.get(BackupPayloadCleaner).sweep()).resolves.toBe(0);
  });

  // ----------------------------------------------------------------------------------------------
  // T19.H6 — o Scheduler passou de 1 para 15 minutos para o Neon voltar a dormir. Duas coisas
  // mudam para o ciclo: quase todo ciclo começa acordando o banco, e o ciclo passa a medir se o
  // banco de fato dorme (`pg_postmaster_start_time()`: o Neon reinicia o Postgres a cada retomada).
  // ----------------------------------------------------------------------------------------------

  describe('T19.H6 — cada ciclo começa acordando o banco', () => {
    async function boot(overrides: Record<string, string> = {}): Promise<void> {
      temp = createTempDb();
      app = await createTestApp(
        configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled', ...overrides }),
        new FakeAuthTokenVerifier(),
      );
    }

    /** A próxima aquisição do pool falha com `error`; as seguintes seguem para o pool real. */
    function failNextConnect(error: Error): jest.SpyInstance<Promise<unknown>, []> {
      // `Pool.connect` tem sobrecarga (promise e callback), e o spy tipa a de callback.
      const spy = jest.spyOn(
        app.get(PostgresService).pool,
        'connect',
      ) as unknown as jest.SpyInstance<Promise<unknown>, []>;
      return spy.mockRejectedValueOnce(error);
    }

    it('uma falha transitória ao conectar (o cold start do Neon) é absorvida pela segunda tentativa', async () => {
      await boot();
      const warn = jest.spyOn(app.get(SparkLogger), 'warn');
      // Só a PRIMEIRA aquisição falha — é a do lock do ciclo; as demais seguem para o pool real.
      const connect = failNextConnect(
        Object.assign(new Error('the database system is starting up'), { code: '57P03' }),
      );

      const result = await app.get(MaintenanceCoordinator).runCycle();

      expect(result.skipped).toBe(false);
      expect(connect.mock.calls.length).toBeGreaterThanOrEqual(2);
      expect(warn).toHaveBeenCalledWith(
        'database.connect.retry',
        expect.objectContaining({ delayMs: expect.any(Number) }),
      );
    });

    it('uma falha que não é de cold start não é repetida, e o ciclo diz por que falhou (stage=database)', async () => {
      await boot();
      const coordinator = app.get(MaintenanceCoordinator);
      const error = jest.spyOn(app.get(SparkLogger), 'error');
      const connect = failNextConnect(
        Object.assign(new Error('Your project has exceeded the compute time quota.'), {
          code: 'XX000',
        }),
      );

      await expect(coordinator.runCycle()).rejects.toThrow('compute time quota');

      expect(connect).toHaveBeenCalledTimes(1);
      expect(error).toHaveBeenCalledWith(
        'maintenance_failed',
        expect.objectContaining({
          operation: 'maintenance_cycle',
          status: 'FAILED',
          stage: 'database',
          errorCode: 'XX000',
        }),
      );
      // O código do servidor, nunca a mensagem.
      expect(JSON.stringify(error.mock.calls)).not.toContain('compute time quota');
      // O banco de volta, o ciclo seguinte roda normalmente.
      expect((await coordinator.runCycle()).skipped).toBe(false);
    });

    it('o ciclo informa o compute que o atendeu — o mesmo compute enquanto o banco não dorme', async () => {
      await boot();
      const coordinator = app.get(MaintenanceCoordinator);
      const info = jest.spyOn(app.get(SparkLogger), 'info');

      const first = await coordinator.runCycle();
      const second = await coordinator.runCycle();

      expect(first.databaseStartedAt).toEqual(expect.any(Number));
      expect(first.databaseStartedAt!).toBeLessThanOrEqual(Date.now());
      expect(first.databaseUptimeMs!).toBeGreaterThanOrEqual(0);
      // O PostgreSQL de teste não reinicia entre dois ciclos: mesmo compute, uptime que só cresce.
      // Em produção, compute novo a cada ciclo é o banco que dormiu entre eles.
      expect(second.databaseStartedAt).toBe(first.databaseStartedAt);
      expect(second.databaseUptimeMs!).toBeGreaterThanOrEqual(first.databaseUptimeMs!);
      expect(info).toHaveBeenCalledWith(
        'maintenance_completed',
        expect.objectContaining({
          databaseStartedAt: first.databaseStartedAt,
          databaseUptimeMs: first.databaseUptimeMs,
        }),
      );
    });

    it('compute acordado além de DATABASE_COMPUTE_UPTIME_WARN_MS: um aviso por compute, não por ciclo', async () => {
      // 1 ms: qualquer PostgreSQL de teste já está de pé há mais do que isso.
      await boot({ DATABASE_COMPUTE_UPTIME_WARN_MS: '1' });
      const coordinator = app.get(MaintenanceCoordinator);
      const error = jest.spyOn(app.get(SparkLogger), 'error');
      const warnings = () =>
        error.mock.calls.filter(([event]) => event === 'database_compute_long_uptime');

      const first = await coordinator.runCycle();
      expect(first.skipped).toBe(false);
      expect(warnings()).toHaveLength(1);
      expect(warnings()[0][1]).toMatchObject({
        operation: 'database_compute_check',
        databaseStartedAt: first.databaseStartedAt,
        warnAfterMs: 1,
      });

      await coordinator.runCycle();
      expect(warnings()).toHaveLength(1); // o mesmo compute não avisa de novo

      // Um compute novo volta a avisar — simulado trocando o compute já avisado.
      await app
        .get(PostgresService)
        .query(
          `UPDATE server_metadata SET value = '1' WHERE key = 'database_compute_uptime_warned_for'`,
        );
      await coordinator.runCycle();
      expect(warnings()).toHaveLength(2);
    });

    it.each([
      ['desligado (0)', '0'],
      ['abaixo do limite', String(30 * 24 * 60 * 60 * 1000)],
    ])(
      'aviso de compute %s: nada é lido nem escrito além do ciclo',
      async (_label, warnAfterMs) => {
        await boot({ DATABASE_COMPUTE_UPTIME_WARN_MS: warnAfterMs });
        const error = jest.spyOn(app.get(SparkLogger), 'error');

        const result = await app.get(MaintenanceCoordinator).runCycle();

        expect(result.skipped).toBe(false);
        expect(
          error.mock.calls.filter(([event]) => event === 'database_compute_long_uptime'),
        ).toEqual([]);
        const { rows } = await app
          .get(PostgresService)
          .query(`SELECT 1 FROM server_metadata WHERE key = 'database_compute_uptime_warned_for'`);
        expect(rows).toHaveLength(0);
      },
    );
  });
});
