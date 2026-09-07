import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixtureText } from './support/backup-fixtures';
import { programPayload, pushBody, uuid } from './support/sync-fixtures';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

/**
 * Backup, restore e sync são mecanismos **diferentes** sobre o mesmo dado (T16.6).
 *
 * ```text
 * T16.4  BACKUP    Android ──snapshot completo──▶ VPS   backup_snapshots / backup_items
 * T16.5  RESTORE   Android ◀──snapshot completo── VPS   as mesmas tabelas, só leitura
 * T16.6  SYNC      Android ⇄ mudanças ⇄ VPS             sync_entities / sync_changes / sync_mutations
 * ```
 *
 * Este arquivo existe para impedir a regressão mais provável desta fase: o sync "absorver" o
 * backup. Um snapshot completo não vira change log, um change log não vira snapshot, e um não
 * altera o outro.
 */
describe('Sync não substitui backup', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
    );
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  const push = (body: string) =>
    request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(body);

  const backup = () =>
    request(app.getHttpServer())
      .post('/v1/backups')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(fixtureText('backup-v1-complete'));

  it('o backup continua funcionando depois de o sync existir', async () => {
    const created = await backup();

    expect(created.status).toBe(201);
    expect(created.body.payloadHash).toMatch(/^[0-9a-f]{64}$/);
    expect(created.body.itemCount).toBeGreaterThan(0);
  });

  it('um push de sync não cria backup', async () => {
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_PROGRAM', entitySyncId: syncId, payload: programPayload(syncId) },
      ]),
    );

    const latest = await request(app.getHttpServer())
      .get('/v1/backups/latest')
      .set('Authorization', `Bearer ${TOKEN}`);

    expect(latest.status).toBe(404);
    expect(latest.body.error.code).toBe('BACKUP_NOT_FOUND');
  });

  it('um backup não cria mudanças no change log', async () => {
    await backup();

    const pulled = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN}`);

    // O snapshot inteiro não é interpretado como milhares de mudanças novas: ele é backup.
    expect(pulled.body.changes).toEqual([]);
  });

  it('o conteúdo do backup continua sendo devolvido verbatim depois de pushes de sync', async () => {
    const created = await backup();
    const syncId = uuid();
    await push(
      pushBody([
        { entityType: 'WORKOUT_PROGRAM', entitySyncId: syncId, payload: programPayload(syncId) },
      ]),
    );

    const content = await request(app.getHttpServer())
      .get(`/v1/backups/${created.body.backupId}/content`)
      .set('Authorization', `Bearer ${TOKEN}`);

    expect(content.status).toBe(200);
    // O documento do restore não é remontado a partir de `sync_entities`.
    expect(JSON.parse(content.text).clientBackupId).toBe(created.body.clientBackupId);
  });
});
