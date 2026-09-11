import { INestApplication } from '@nestjs/common';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { MaintenanceCoordinator } from '../src/maintenance/maintenance.coordinator';
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
});
