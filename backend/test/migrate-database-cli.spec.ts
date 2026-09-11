import { Client } from 'pg';
import { createTempDb, DEFAULT_TEST_DATABASE_URL, type TempDb } from './support/temp-db';
import { runDatabaseMigration } from '../src/cli/migrate-database';

/**
 * T18.2 §7 — `migrate:database`.
 *
 * O CLI é deliberadamente independente de `AppConfig`: ele só lê `DATABASE_URL_DIRECT` do
 * ambiente, sem o fallback de `AppConfig.databaseUrlDirect` — cair silenciosamente no endpoint
 * pooled é exatamente o defeito que a Service Account dedicada (`spark-backend-migrator`) existe
 * para impedir. Estes testes rodam contra o Postgres real de teste (o mesmo do resto da suíte),
 * numa schema isolada criada manualmente aqui — `runDatabaseMigration()` não cria schema por
 * conta própria, ao contrário de `PostgresService.initialize()`: em produção o destino é sempre o
 * schema `public` de um banco que já existe.
 */
describe('T18.2 — CLI migrate:database', () => {
  const originalDirectUrl = process.env.DATABASE_URL_DIRECT;

  afterEach(() => {
    if (originalDirectUrl === undefined) {
      delete process.env.DATABASE_URL_DIRECT;
    } else {
      process.env.DATABASE_URL_DIRECT = originalDirectUrl;
    }
  });

  it('sem DATABASE_URL_DIRECT: falha com código 1, e nunca cai no pooled em silêncio', async () => {
    delete process.env.DATABASE_URL_DIRECT;
    await expect(runDatabaseMigration()).resolves.toBe(1);
  });

  it('DATABASE_URL_DIRECT vazio conta como ausente, como no resto do backend', async () => {
    process.env.DATABASE_URL_DIRECT = '   ';
    await expect(runDatabaseMigration()).resolves.toBe(1);
  });

  describe('com um schema isolado de verdade', () => {
    let temp: TempDb;

    beforeEach(async () => {
      temp = createTempDb();
      // `runDatabaseMigration()` não cria schema — cria-se aqui, do mesmo jeito que
      // `PostgresService.initialize()` faz internamente para os outros testes.
      const match = /search_path(?:%3D|=)([^&]+)/i.exec(temp.databaseUrl);
      const schema = decodeURIComponent(match![1]);
      const cleanUrl = (process.env.DATABASE_URL || DEFAULT_TEST_DATABASE_URL).replace(
        /[?&]options=[^&]+/g,
        '',
      );
      const client = new Client({ connectionString: cleanUrl });
      await client.connect();
      await client.query(`CREATE SCHEMA IF NOT EXISTS "${schema}"`);
      await client.end();
    });

    afterEach(() => {
      temp.cleanup();
    });

    it('aplica as migrations pendentes e sai com código 0', async () => {
      process.env.DATABASE_URL_DIRECT = temp.databaseUrl;
      await expect(runDatabaseMigration()).resolves.toBe(0);
    });

    it('rodar duas vezes converge: a segunda execução também sai com código 0', async () => {
      process.env.DATABASE_URL_DIRECT = temp.databaseUrl;
      await expect(runDatabaseMigration()).resolves.toBe(0);
      await expect(runDatabaseMigration()).resolves.toBe(0);
    });
  });
});
