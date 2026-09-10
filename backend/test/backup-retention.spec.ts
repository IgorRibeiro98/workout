import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AppConfig } from '../src/config/app-config';
import { BackupRepository } from '../src/modules/backup/backup.repository';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { withClientBackupId } from './support/backup-fixtures';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

/**
 * Retenção de snapshots (T16.4).
 *
 * O invariante que importa não é "sobram N": é **a ordem**. O backup novo é gravado e confirmado
 * antes de qualquer limpeza, para que uma falha na limpeza deixe o usuário com backup a mais — e
 * nunca com backup a menos.
 */
describe('Retenção de backups', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const start = async (retention: string): Promise<void> => {
    app = await createTestApp(
      configFor(temp.path, { BACKUP_RETENTION_COUNT: retention }),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
    );
  };

  const post = (clientBackupId: string) =>
    request(app.getHttpServer())
      .post('/v1/backups')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(JSON.stringify(withClientBackupId('backup-v1-minimal', clientBackupId)));

  const id = (index: number) => `0000000${index}-0000-4000-8000-000000000000`;

  it('a política é configuração central, não número mágico', () => {
    expect(configFor(temp.path).backupRetentionCount).toBe(5);
    expect(configFor(temp.path, { BACKUP_RETENTION_COUNT: '3' }).backupRetentionCount).toBe(3);
    // Zero significaria "guarde nenhum" — apagar o backup logo depois de criá-lo.
    expect(() => configFor(temp.path, { BACKUP_RETENTION_COUNT: '0' })).toThrow();
  });

  it('backups além da política são removidos, do mais antigo para o mais novo', async () => {
    await start('3');
    const created = [];
    for (let index = 1; index <= 5; index += 1) {
      created.push(await post(id(index)));
    }

    const repository = app.get(BackupRepository);
    expect(await repository.countFor(UID)).toBe(3);
    expect(await repository.findByClientBackupId(UID, id(1))).toBeNull();
    expect(await repository.findByClientBackupId(UID, id(2))).toBeNull();
    expect(await repository.findByClientBackupId(UID, id(3))).not.toBeNull();

    const latest = await request(app.getHttpServer())
      .get('/v1/backups/latest')
      .set('Authorization', `Bearer ${TOKEN}`);
    expect(latest.body.backupId).toBe(created.at(-1)?.body.backupId);
  });

  it('o backup recém-criado nunca é o removido', async () => {
    await start('1');

    for (let index = 1; index <= 4; index += 1) {
      const response = await post(id(index));
      expect(response.status).toBe(201);

      // Depois de cada POST, o backup que acabou de ser criado é o que sobrou.
      const latest = await request(app.getHttpServer())
        .get('/v1/backups/latest')
        .set('Authorization', `Bearer ${TOKEN}`);
      expect(latest.status).toBe(200);
      expect(latest.body.backupId).toBe(response.body.backupId);
      expect(await app.get(BackupRepository).countFor(UID)).toBe(1);
    }
  });

  it('uma limpeza que falha não invalida o backup novo', async () => {
    await start('1');
    const first = await post(id(1));
    expect(first.status).toBe(201);

    // A limpeza quebra; o backup já foi confirmado antes dela.
    const repository = app.get(BackupRepository);
    const prune = jest.spyOn(repository, 'pruneOlderThan').mockImplementation(() => {
      throw new Error('falha simulada na limpeza');
    });

    const second = await post(id(2));

    expect(prune).toHaveBeenCalled();
    // A requisição continua sendo um sucesso: o problema virou limpeza pendente, não perda.
    expect(second.status).toBe(201);
    const latest = await request(app.getHttpServer())
      .get('/v1/backups/latest')
      .set('Authorization', `Bearer ${TOKEN}`);
    expect(latest.body.backupId).toBe(second.body.backupId);
    expect(await repository.countFor(UID)).toBe(2);

    prune.mockRestore();
  });

  it('a configuração é validada no startup, como todo o resto', () => {
    expect(() =>
      AppConfig.fromEnv({ DATABASE_PATH: ':memory:', BACKUP_RETENTION_COUNT: 'muitos' }),
    ).toThrow();
  });
});
