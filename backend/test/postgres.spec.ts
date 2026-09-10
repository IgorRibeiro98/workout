import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Pool } from 'pg';

import { loadMigrations, runMigrations } from '../src/database/postgres-migration-runner';
import { MIGRATION_ADVISORY_LOCK_KEY } from '../src/database/database.constants';
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

  // --- fail-fast com o pool indisponível (T18.0.2) --------------------------------------------
  //
  // "Consulta executada e não achou nada" e "não havia banco" são estados diferentes, e o
  // segundo precisa falhar de forma explícita. A versão anterior de `query()` devolvia
  // `{ rows: [] }` com o pool ausente — um `findById` viraria `NOT_FOUND`, um guard de tombstone
  // viraria "conta não excluída". Nenhum repositório pode receber um vazio sintético.

  it('query antes de initialize lança erro explícito, e não um resultado vazio', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await expect(postgres.query('SELECT 1')).rejects.toThrow(/não foi inicializado/);
    await expect(postgres.appliedVersions()).rejects.toThrow(/não foi inicializado/);
  });

  it('query após close lança erro explícito, e não um resultado vazio', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);
    await postgres.close();

    expect(postgres.isOpen).toBe(false);
    await expect(postgres.query('SELECT 1')).rejects.toThrow(/encerrado/);
    await expect(
      postgres.query('SELECT value FROM server_metadata WHERE key = $1', ['qualquer']),
    ).rejects.toThrow(/encerrado/);
    await expect(postgres.appliedVersions()).rejects.toThrow(/encerrado/);
  });

  it('transaction após close lança erro explícito, com a mesma semântica de query', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);
    await postgres.close();

    const work = jest.fn();
    await expect(postgres.transaction(work)).rejects.toThrow(/encerrado/);
    expect(work).not.toHaveBeenCalled();
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

  // --- timeouts do client não vazam para fora de runMigrations (T18.0.3 P1) --------------------
  //
  // `runMigrations` altera `statement_timeout`/`lock_timeout` na conexão para serializar o
  // advisory lock. Antes disso, essa conexão podia voltar ao pool com os timeouts de migration em
  // vez dos originais — e, se o `client` recebido fosse do pool principal (o caso comum: sem
  // `DATABASE_URL_DIRECT`), a próxima requisição HTTP a reutilizá-la herdaria um
  // `statement_timeout`/`lock_timeout` diferente do resto do pool, silenciosamente.

  async function currentTimeouts(
    queryable: Pick<Pool, 'query'>,
  ): Promise<{ statementTimeout: string; lockTimeout: string }> {
    const stmt = await queryable.query<{ statement_timeout: string }>('SHOW statement_timeout');
    const lock = await queryable.query<{ lock_timeout: string }>('SHOW lock_timeout');
    return {
      statementTimeout: stmt.rows[0].statement_timeout,
      lockTimeout: lock.rows[0].lock_timeout,
    };
  }

  it('runMigrations bem-sucedida restaura o statement_timeout/lock_timeout que a conexão tinha antes', async () => {
    // `max: 1`: só existe uma conexão física no pool, então a que aplica as migrations é,
    // necessariamente, a mesma que atende as queries seguintes — a mesma reutilização que o pool
    // principal de `PostgresService` faz quando não há `DATABASE_URL_DIRECT`.
    const config = configFor(temp.path);
    const pool = new Pool({ connectionString: config.databaseUrl, max: 1 });
    try {
      // Timeouts deliberadamente diferentes dos que o runner usa internamente (15000ms) e do
      // default do servidor (0/desabilitado), para que "restaurou o original" não possa ser
      // confundido com "voltou para um valor fixo qualquer".
      await pool.query("SET statement_timeout = '9000ms'");
      await pool.query("SET lock_timeout = '4000ms'");
      const before = await currentTimeouts(pool);

      const applied = await runMigrations(pool, loadMigrations(MIGRATIONS_DIR));
      expect(applied.length).toBeGreaterThan(0);

      const after = await currentTimeouts(pool);
      expect(after).toEqual(before);
    } finally {
      await pool.end();
    }
  });

  it('runMigrations que falha no meio também restaura os timeouts e libera o advisory lock', async () => {
    const config = configFor(temp.path);
    const pool = new Pool({ connectionString: config.databaseUrl, max: 1 });
    try {
      await pool.query("SET statement_timeout = '7000ms'");
      await pool.query("SET lock_timeout = '3000ms'");
      const before = await currentTimeouts(pool);

      const migrations = loadMigrations(MIGRATIONS_DIR);
      await expect(
        runMigrations(pool, [
          ...migrations,
          {
            version: migrations.length + 1,
            name: 'quebrada',
            sql: 'ISTO NAO E SQL;',
          },
        ]),
      ).rejects.toThrow();

      // Mesma conexão (max: 1), depois de devolvida ao pool pelo `finally` do runner.
      const after = await currentTimeouts(pool);
      expect(after).toEqual(before);

      // Nenhum advisory lock de migration sobrevive ao erro.
      const locks = await pool.query<{ total: string | number }>(
        `SELECT COUNT(*) AS total FROM pg_locks WHERE locktype = 'advisory' AND classid = $1`,
        [MIGRATION_ADVISORY_LOCK_KEY],
      );
      expect(Number(locks.rows[0].total)).toBe(0);
    } finally {
      await pool.end();
    }
  });

  it('uma conexão reutilizada pelo runtime mantém o timeout configurado por PostgresService, não o de migration', async () => {
    // Pool com exatamente uma conexão: a mesma que aplica as migrations no boot é,
    // necessariamente, a que atende a query seguinte — é essa reutilização que o teste prova.
    const config = configFor(temp.path, {
      DATABASE_POOL_MAX: '1',
      DATABASE_POOL_MIN: '1',
      DATABASE_STATEMENT_TIMEOUT_MS: '8000',
    });
    const postgres = postgresFor(config);
    await postgres.initialize(MIGRATIONS_DIR);

    const res = await postgres.query<{ statement_timeout: string }>('SHOW statement_timeout');
    expect(res.rows[0].statement_timeout).toBe('8s');

    await postgres.close();
  });
});
