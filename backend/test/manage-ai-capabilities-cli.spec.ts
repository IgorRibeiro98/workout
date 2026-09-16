import { AppConfig } from '../src/config/app-config';
import { SparkLogger } from '../src/common/logger';
import { PostgresService } from '../src/database/postgres.service';
import { runManageAiCapabilities } from '../src/cli/manage-ai-capabilities';
import { AiEntitlementRepository } from '../src/modules/ai/entitlement/ai-entitlement.repository';
import { createTempDb, type TempDb } from './support/temp-db';

/**
 * `capabilities:ai` — o mecanismo operacional de grant/revoke/list (T19.0 §22/§23), sem editar o
 * banco manualmente. Roda contra o Postgres real de teste, numa schema isolada por teste — cada
 * chamada de `runManageAiCapabilities` abre e fecha o próprio `PostgresService`, exatamente como a
 * execução real via `npm run capabilities:ai` faz.
 */
describe('CLI capabilities:ai', () => {
  let temp: TempDb;
  const originalDatabaseUrl = process.env.DATABASE_URL;

  beforeEach(() => {
    temp = createTempDb();
    process.env.DATABASE_URL = temp.databaseUrl;
  });

  afterEach(() => {
    temp.cleanup();
    if (originalDatabaseUrl === undefined) {
      delete process.env.DATABASE_URL;
    } else {
      process.env.DATABASE_URL = originalDatabaseUrl;
    }
  });

  it('sem argumentos, uso incorreto sai com código 2', async () => {
    await expect(runManageAiCapabilities([])).resolves.toBe(2);
  });

  it('ação desconhecida sai com código 2', async () => {
    await expect(runManageAiCapabilities(['delete', 'uid-a', 'AI_EXPLAIN'])).resolves.toBe(2);
  });

  it('grant sem uid sai com código 2', async () => {
    await expect(runManageAiCapabilities(['grant'])).resolves.toBe(2);
  });

  it('grant sem capability sai com código 2', async () => {
    await expect(runManageAiCapabilities(['grant', 'uid-a'])).resolves.toBe(2);
  });

  it('capability desconhecida sai com código 2, sem gravar nada', async () => {
    await expect(runManageAiCapabilities(['grant', 'uid-a', 'AI_SOMETHING_NEW'])).resolves.toBe(2);

    const rows = await withRepository((repo) => repo.listFor('uid-a'));
    expect(rows).toHaveLength(0);
  });

  it('list de uma conta sem entitlement sai com código 0 e não grava nada', async () => {
    await expect(runManageAiCapabilities(['list', 'uid-a'])).resolves.toBe(0);
  });

  it('grant grava GRANTED, e list reflete', async () => {
    await expect(runManageAiCapabilities(['grant', 'uid-a', 'AI_EXPLAIN'])).resolves.toBe(0);

    const rows = await withRepository((repo) => repo.listFor('uid-a'));
    expect(rows).toEqual([expect.objectContaining({ capability: 'AI_EXPLAIN', state: 'GRANTED' })]);
  });

  it('revoke é idempotente — repetir não duplica linha nem falha', async () => {
    await expect(runManageAiCapabilities(['revoke', 'uid-a', 'AI_EXPLAIN'])).resolves.toBe(0);
    await expect(runManageAiCapabilities(['revoke', 'uid-a', 'AI_EXPLAIN'])).resolves.toBe(0);

    const rows = await withRepository((repo) => repo.listFor('uid-a'));
    expect(rows).toEqual([expect.objectContaining({ capability: 'AI_EXPLAIN', state: 'REVOKED' })]);
  });

  it('grant depois revoke depois grant converge para GRANTED sem duplicar linha', async () => {
    await runManageAiCapabilities(['grant', 'uid-a', 'AI_EXPLAIN']);
    await runManageAiCapabilities(['revoke', 'uid-a', 'AI_EXPLAIN']);
    await runManageAiCapabilities(['grant', 'uid-a', 'AI_EXPLAIN']);

    const rows = await withRepository((repo) => repo.listFor('uid-a'));
    expect(rows).toEqual([expect.objectContaining({ capability: 'AI_EXPLAIN', state: 'GRANTED' })]);
  });

  it('grant/revoke de uma conta não grava linha para outra conta', async () => {
    await runManageAiCapabilities(['revoke', 'uid-a', 'AI_EXPLAIN']);

    const rowsForB = await withRepository((repo) => repo.listFor('uid-b'));
    expect(rowsForB).toHaveLength(0);
  });

  async function withRepository<T>(
    fn: (repository: AiEntitlementRepository) => Promise<T>,
  ): Promise<T> {
    const config = AppConfig.fromEnv();
    const postgres = new PostgresService(config, new SparkLogger(config));
    await postgres.initialize();
    try {
      return await fn(new AiEntitlementRepository(postgres));
    } finally {
      await postgres.onApplicationShutdown();
    }
  }
});
