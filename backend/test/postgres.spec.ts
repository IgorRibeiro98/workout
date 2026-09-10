import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { loadMigrations, runMigrations } from '../src/database/postgres-migration-runner';
import {
  configFor,
  createTempDb,
  MIGRATIONS_DIR,
  postgresFor,
  type TempDb,
} from './support/temp-db';

describe('PostgreSQL (pool, migrations, transações, persistência)', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  it('inicializa o pool e aplica as migrations', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    const applied = await postgres.appliedVersions();
    const expected = postgres.expectedVersions();
    expect(applied).toEqual(expected);
    expect(expected.length).toBeGreaterThan(0);

    await postgres.close();
  });

  it('mantém foreign_keys ativas', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    await postgres.query(`
      CREATE TABLE test_fk_parent (id INTEGER PRIMARY KEY);
      CREATE TABLE test_fk_child (
        id INTEGER PRIMARY KEY,
        parent_id INTEGER NOT NULL REFERENCES test_fk_parent(id)
      );
    `);

    await expect(
      postgres.query('INSERT INTO test_fk_child (id, parent_id) VALUES (1, 999)'),
    ).rejects.toThrow(/foreign key|violates foreign key constraint/i);

    await postgres.close();
  });

  it('configura pool e timeouts a partir da configuração centralizada', () => {
    const config = configFor(temp.path, {
      DATABASE_POOL_MAX: '20',
      DATABASE_POOL_MIN: '2',
      DATABASE_STATEMENT_TIMEOUT_MS: '8000',
    });
    expect(config.databasePoolMax).toBe(20);
    expect(config.databasePoolMin).toBe(2);
    expect(config.databaseStatementTimeoutMs).toBe(8000);
  });

  it('roda migrations novamente sem reaplicar nem corromper estado', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);
    const firstRun = await postgres.appliedVersions();

    await postgres.query(
      'INSERT INTO server_metadata (key, value, updated_at) VALUES ($1, $2, $3)',
      ['teste_idempotencia', 'preservado', 1],
    );

    // Segunda passagem de migrations sobre o mesmo banco já migrado.
    const applied = await runMigrations(postgres.pool, loadMigrations(MIGRATIONS_DIR));

    expect(applied).toEqual([]);
    expect(await postgres.appliedVersions()).toEqual(firstRun);
    const res = await postgres.query('SELECT value FROM server_metadata WHERE key = $1', [
      'teste_idempotencia',
    ]);
    expect(res.rows[0]?.value).toBe('preservado');

    await postgres.close();
  });

  it('recusa reescrita do histórico de migrations', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    await expect(
      runMigrations(postgres.pool, [{ version: 1, name: 'outro_nome', sql: 'SELECT 1;' }]),
    ).rejects.toThrow(/não pode ser reescrito/);

    await postgres.close();
  });

  it('faz rollback de uma transação com erro no meio', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    await expect(
      postgres.transaction(async (client) => {
        await client.query(
          'INSERT INTO server_metadata (key, value, updated_at) VALUES ($1, $2, $3)',
          ['rollback_a', 'a', 1],
        );
        await client.query(
          'INSERT INTO server_metadata (key, value, updated_at) VALUES ($1, $2, $3)',
          ['rollback_b', 'b', 2],
        );
        throw new Error('falha deliberada no meio da transação');
      }),
    ).rejects.toThrow('falha deliberada no meio da transação');

    const res = await postgres.query(
      "SELECT COUNT(*) AS total FROM server_metadata WHERE key LIKE 'rollback_%'",
    );
    expect(Number(res.rows[0]?.total)).toBe(0);

    await postgres.close();
  });

  it('uma migration que falha no meio não deixa schema parcialmente aplicado', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    await expect(
      runMigrations(postgres.pool, [
        ...loadMigrations(MIGRATIONS_DIR),
        {
          version: postgres.expectedVersions().length + 1,
          name: 'quebrada',
          sql: 'CREATE TABLE ok_ate_aqui (id INTEGER PRIMARY KEY); ISTO NAO E SQL;',
        },
      ]),
    ).rejects.toThrow();

    const res = await postgres.query(
      "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'ok_ate_aqui'",
    );
    expect(res.rows.length).toBe(0);

    await postgres.close();
  });

  it('persiste o dado através de um ciclo completo de fechamento e reabertura', async () => {
    const first = postgresFor(configFor(temp.path));
    await first.initialize(MIGRATIONS_DIR);
    await first.query('INSERT INTO server_metadata (key, value, updated_at) VALUES ($1, $2, $3)', [
      'teste_persistencia',
      'sobreviveu',
      42,
    ]);
    await first.close();

    expect(first.isOpen).toBe(false);

    // Nova instância da camada de banco sobre o mesmo schema: é o que um restart de processo faz.
    const second = postgresFor(configFor(temp.path));
    await second.initialize(MIGRATIONS_DIR);

    const res = await second.query('SELECT value, updated_at FROM server_metadata WHERE key = $1', [
      'teste_persistencia',
    ]);
    expect(res.rows[0]).toEqual({ value: 'sobreviveu', updated_at: 42 });

    await second.close();
  });

  it('checkHealth reporta banco alcançável e schema na versão esperada', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    expect(await postgres.checkHealth()).toEqual({ reachable: true, migrationsUpToDate: true });

    await postgres.close();
    expect(await postgres.checkHealth()).toEqual({ reachable: false, migrationsUpToDate: false });
  });

  it('recusa nome de migration fora do contrato NNNN_nome.sql', () => {
    writeFileSync(join(temp.directory, 'sem_prefixo.sql'), 'SELECT 1;');

    expect(() => loadMigrations(temp.directory)).toThrow(/nome inválido/);
  });

  it('recusa buraco na sequência de versões', () => {
    writeFileSync(join(temp.directory, '0002_pula_a_um.sql'), 'SELECT 1;');

    expect(() => loadMigrations(temp.directory)).toThrow(/Sequência de migrations quebrada/);
  });
});
