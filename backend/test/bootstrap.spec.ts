import { existsSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { type CreatedApp, createApp } from '../src/bootstrap/create-app';
import { AppConfig, ConfigValidationError } from '../src/config/app-config';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

describe('Bootstrap da aplicação', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  it('sobe com configuração válida e deixa o banco pronto antes do HTTP', async () => {
    const { app, postgres } = await createApp(configFor(temp.path));
    await app.init();

    expect(postgres.isOpen).toBe(true);
    expect(await postgres.appliedVersions()).toEqual(postgres.expectedVersions());

    await app.close();
  });

  it('fecha o PostgreSQL no shutdown da aplicação', async () => {
    const { app, postgres } = await createApp(configFor(temp.path));
    await app.init();

    await app.close();

    expect(postgres.isOpen).toBe(false);
  });

  it('configuração obrigatória inválida falha antes de qualquer montagem', () => {
    expect(() => AppConfig.fromEnv({ NODE_ENV: 'test' })).toThrow(ConfigValidationError);
  });

  it('falha no bootstrap quando a URL do banco não pode ser acessada', async () => {
    const invalidUrl =
      'postgresql://spark:wrongpassword@127.0.0.1:5432/spark_dev?connection_timeout=1';

    let created: CreatedApp | undefined;
    try {
      created = await createApp(
        configFor(invalidUrl, {
          DATABASE_URL: invalidUrl,
          DATABASE_CONNECTION_TIMEOUT_MS: '300',
        }),
      );
    } catch {
      return;
    }

    await created.app.close();
    throw new Error('bootstrap deveria ter falhado com DATABASE_URL inacessível');
  });
});
