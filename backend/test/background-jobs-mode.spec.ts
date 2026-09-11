import { INestApplication } from '@nestjs/common';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { NotificationDispatcher } from '../src/modules/social/notification.dispatcher';
import { SocialMediaCleaner } from '../src/modules/social/social-media.cleaner';
import { BackupPayloadCleaner } from '../src/modules/backup/backup-payload.cleaner';
import { AccountDeletionReconciler } from '../src/modules/account-deletion/account-deletion.reconciler';

/**
 * T18.2 §32 — `BACKGROUND_JOBS_MODE=disabled`.
 *
 * O que esta suíte prova é negativo por natureza (nenhum timer nasce), então ela prova pelo lado
 * observável: com `disabled`, `(worker as any).timer` continua `undefined` depois do boot — em
 * `interval` (default), o mesmo campo está definido. Os quatro workers compartilham esse campo
 * privado com o mesmo nome (`timer`), o que os testes exploram deliberadamente via cast, em vez de
 * esperar um ciclo de `setInterval` de verdade transcorrer (lento e não-determinístico).
 *
 * Os métodos de uma passagem (`runDispatchCycle`, `processDueJobs`, `sweep`) continuam chamáveis
 * diretamente nos dois modos — é o que as suítes de cada worker já provam há tarefas; esta suíte
 * só prova que o **agendamento automático** desliga.
 */
describe('T18.2 — BACKGROUND_JOBS_MODE', () => {
  let temp: TempDb;
  let app: INestApplication;

  afterEach(async () => {
    await app?.close();
    temp?.cleanup();
  });

  const timerOf = (instance: object): unknown => (instance as { timer?: unknown }).timer;

  it('interval (default): os quatro workers agendam timer no boot', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_PUSH_ENABLED: 'true' }),
      new FakeAuthTokenVerifier(),
    );

    expect(timerOf(app.get(NotificationDispatcher))).toBeDefined();
    expect(timerOf(app.get(SocialMediaCleaner))).toBeDefined();
    expect(timerOf(app.get(BackupPayloadCleaner))).toBeDefined();
    expect(timerOf(app.get(AccountDeletionReconciler))).toBeDefined();
  });

  it('disabled: nenhum dos quatro workers agenda timer, mesmo com SOCIAL_PUSH_ENABLED=true', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, {
        SOCIAL_PUSH_ENABLED: 'true',
        BACKGROUND_JOBS_MODE: 'disabled',
      }),
      new FakeAuthTokenVerifier(),
    );

    expect(timerOf(app.get(NotificationDispatcher))).toBeUndefined();
    expect(timerOf(app.get(SocialMediaCleaner))).toBeUndefined();
    expect(timerOf(app.get(BackupPayloadCleaner))).toBeUndefined();
    expect(timerOf(app.get(AccountDeletionReconciler))).toBeUndefined();
  });

  it('disabled: os métodos de uma passagem continuam chamáveis diretamente', async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, { BACKGROUND_JOBS_MODE: 'disabled' }),
      new FakeAuthTokenVerifier(),
    );

    await expect(app.get(NotificationDispatcher).runDispatchCycle()).resolves.toBeUndefined();
    await expect(app.get(SocialMediaCleaner).sweep()).resolves.toBe(0);
    await expect(app.get(BackupPayloadCleaner).sweep()).resolves.toBe(0);
    await expect(app.get(AccountDeletionReconciler).processDueJobs()).resolves.toBe(0);
  });
});
