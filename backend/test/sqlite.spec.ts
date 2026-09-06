import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import BetterSqlite3 from 'better-sqlite3';
import { runMigrations, loadMigrations } from '../src/database/migration-runner';
import { configFor, createTempDb, MIGRATIONS_DIR, sqliteFor, type TempDb } from './support/temp-db';

describe('SQLite (PRAGMAs, migrations, transações, persistência)', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  it('cria o banco em arquivo e aplica as migrations', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);

    expect(existsSync(temp.path)).toBe(true);
    expect(sqlite.appliedVersions()).toEqual(sqlite.expectedVersions());
    expect(sqlite.expectedVersions().length).toBeGreaterThan(0);

    sqlite.close();
  });

  it('ativa WAL quando o sistema de arquivos suporta', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);

    // Em filesystem que recusa WAL o SQLite mantém o modo anterior; o teste exige WAL onde ele é
    // suportado e documenta o fallback em vez de aceitar qualquer valor silenciosamente.
    expect(sqlite.pragmas().journalMode.toLowerCase()).toBe('wal');

    sqlite.close();
  });

  it('mantém foreign_keys ativo', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);

    expect(sqlite.pragmas().foreignKeys).toBe(true);

    // Prova comportamental: uma FK órfã é recusada de verdade, não só o PRAGMA reportado.
    const db = sqlite.connection;
    db.exec(`
      CREATE TABLE fk_parent (id INTEGER PRIMARY KEY) STRICT;
      CREATE TABLE fk_child (
        id INTEGER PRIMARY KEY,
        parent_id INTEGER NOT NULL REFERENCES fk_parent(id)
      ) STRICT;
    `);
    expect(() => db.prepare('INSERT INTO fk_child (id, parent_id) VALUES (1, 999)').run()).toThrow(
      /FOREIGN KEY constraint failed/i,
    );

    sqlite.close();
  });

  it('configura busy_timeout a partir da configuração centralizada', () => {
    const sqlite = sqliteFor(configFor(temp.path, { SQLITE_BUSY_TIMEOUT_MS: '7500' }));
    sqlite.initialize(MIGRATIONS_DIR);

    expect(sqlite.pragmas().busyTimeoutMs).toBe(7500);

    sqlite.close();
  });

  it('roda migrations novamente sem reaplicar nem corromper estado', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);
    const firstRun = sqlite.appliedVersions();
    const db = sqlite.connection;
    db.prepare('INSERT INTO server_metadata (key, value, updated_at) VALUES (?, ?, ?)').run(
      'teste_idempotencia',
      'preservado',
      1,
    );

    // Segunda passagem de migrations sobre o mesmo banco já migrado.
    const applied = runMigrations(db, loadMigrations(MIGRATIONS_DIR));

    expect(applied).toEqual([]);
    expect(sqlite.appliedVersions()).toEqual(firstRun);
    expect(
      db.prepare('SELECT value FROM server_metadata WHERE key = ?').get('teste_idempotencia'),
    ).toEqual({ value: 'preservado' });

    sqlite.close();
  });

  it('recusa reescrita do histórico de migrations', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);
    const db = sqlite.connection;

    expect(() => runMigrations(db, [{ version: 1, name: 'outro_nome', sql: 'SELECT 1;' }])).toThrow(
      /não pode ser reescrito/,
    );

    sqlite.close();
  });

  it('faz rollback de uma transação com erro no meio', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);
    const db = sqlite.connection;

    const insert = db.prepare(
      'INSERT INTO server_metadata (key, value, updated_at) VALUES (?, ?, ?)',
    );
    const transaction = db.transaction(() => {
      insert.run('rollback_a', 'a', 1);
      insert.run('rollback_b', 'b', 2);
      throw new Error('falha deliberada no meio da transação');
    });

    expect(() => transaction()).toThrow('falha deliberada no meio da transação');
    expect(
      db.prepare("SELECT COUNT(*) AS total FROM server_metadata WHERE key LIKE 'rollback_%'").get(),
    ).toEqual({ total: 0 });

    sqlite.close();
  });

  it('uma migration que falha no meio não deixa schema parcialmente aplicado', () => {
    const db = new BetterSqlite3(temp.path);
    db.pragma('foreign_keys = ON');

    expect(() =>
      runMigrations(db, [
        {
          version: 1,
          name: 'quebrada',
          sql: 'CREATE TABLE ok_ate_aqui (id INTEGER PRIMARY KEY); ISTO NAO E SQL;',
        },
      ]),
    ).toThrow();

    const table = db
      .prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='ok_ate_aqui'")
      .get();
    expect(table).toBeUndefined();
    expect(db.prepare('SELECT COUNT(*) AS total FROM schema_migrations').get()).toEqual({
      total: 0,
    });

    db.close();
  });

  it('persiste o dado através de um ciclo completo de fechamento e reabertura', () => {
    const first = sqliteFor(configFor(temp.path));
    first.initialize(MIGRATIONS_DIR);
    first.connection
      .prepare('INSERT INTO server_metadata (key, value, updated_at) VALUES (?, ?, ?)')
      .run('teste_persistencia', 'sobreviveu', 42);
    first.close();

    expect(first.isOpen).toBe(false);

    // Nova instância da camada de banco sobre o mesmo arquivo: é o que um restart de processo faz.
    const second = sqliteFor(configFor(temp.path));
    second.initialize(MIGRATIONS_DIR);

    expect(
      second.connection
        .prepare('SELECT value, updated_at FROM server_metadata WHERE key = ?')
        .get('teste_persistencia'),
    ).toEqual({ value: 'sobreviveu', updated_at: 42 });

    second.close();
  });

  it('checkHealth reporta banco alcançável e schema na versão esperada', () => {
    const sqlite = sqliteFor(configFor(temp.path));
    sqlite.initialize(MIGRATIONS_DIR);

    expect(sqlite.checkHealth()).toEqual({ reachable: true, migrationsUpToDate: true });

    sqlite.close();
    expect(sqlite.checkHealth()).toEqual({ reachable: false, migrationsUpToDate: false });
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
