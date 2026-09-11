import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';

/**
 * T18.2 §6 — `DATABASE_MIGRATION_MODE`.
 *
 * `apply` (default) é o comportamento de sempre: `PostgresService.initialize()` aplica as
 * migrations pendentes. `verify` é o modo Cloud Run API: `initialize()` nunca chama
 * `runMigrations()` — ele só abre o pool e confia no schema já estar no nível esperado. A prova de
 * que isso é suficiente é a mesma verificação que `/health/ready` já fazia
 * (`PostgresService.checkHealth()`): com schema atual, ela reporta `migrationsUpToDate: true`; com
 * schema pendente, `false` — nunca um crash de bootstrap, porque a dependência externa (o banco
 * "atrasado") tem que se comportar como qualquer outra dependência externa fora do ar.
 */
describe('T18.2 — modo de migration do PostgreSQL', () => {
  let temp: TempDb;

  afterEach(() => {
    temp?.cleanup();
  });

  it('apply (default) migra o schema, como sempre', async () => {
    temp = createTempDb();
    const config = configFor(temp.path, { DATABASE_MIGRATION_MODE: 'apply' });
    const postgres = postgresFor(config);
    try {
      await postgres.initialize();
      const health = await postgres.checkHealth();
      expect(health.reachable).toBe(true);
      expect(health.migrationsUpToDate).toBe(true);
    } finally {
      await postgres.close();
    }
  });

  it('verify com schema já aplicado: abre o pool e não falha (Cloud Run cold start comum)', async () => {
    temp = createTempDb();
    // Primeiro processo aplica — simula o Job `spark-db-migrate` já ter rodado.
    const applyConfig = configFor(temp.path, { DATABASE_MIGRATION_MODE: 'apply' });
    const applier = postgresFor(applyConfig);
    await applier.initialize();
    await applier.close();

    // Segundo processo — a API, com `verify` — nunca chama runMigrations.
    const verifyConfig = configFor(temp.path, { DATABASE_MIGRATION_MODE: 'verify' });
    const verifier = postgresFor(verifyConfig);
    try {
      await expect(verifier.initialize()).resolves.toBeUndefined();
      const health = await verifier.checkHealth();
      expect(health.reachable).toBe(true);
      expect(health.migrationsUpToDate).toBe(true);
    } finally {
      await verifier.close();
    }
  });

  it('verify com schema pendente: initialize() não lança, mas checkHealth()/readiness reportam indisponível', async () => {
    temp = createTempDb();
    // Nenhum `apply` roda neste schema — ele nunca viu uma migration.
    const config = configFor(temp.path, { DATABASE_MIGRATION_MODE: 'verify' });
    const postgres = postgresFor(config);
    try {
      // O contrato central do §6: o processo SOBE (não é `process.exit`), e é o readiness que
      // reporta o problema — como qualquer outra dependência externa fora do ar.
      await expect(postgres.initialize()).resolves.toBeUndefined();

      const health = await postgres.checkHealth();
      expect(health.reachable).toBe(true);
      expect(health.migrationsUpToDate).toBe(false);
    } finally {
      await postgres.close();
    }
  });

  it('verify nunca abre um pool direto — mesmo com DATABASE_URL_DIRECT configurada', async () => {
    temp = createTempDb();
    const applyConfig = configFor(temp.path, { DATABASE_MIGRATION_MODE: 'apply' });
    const applier = postgresFor(applyConfig);
    await applier.initialize();
    await applier.close();

    // Uma URL direta claramente inválida: se `verify` tentasse abrir esse pool, a conexão
    // falharia — e o teste falharia junto. `verify` nunca toca `DATABASE_URL_DIRECT`.
    const config = configFor(temp.path, {
      DATABASE_MIGRATION_MODE: 'verify',
      DATABASE_URL_DIRECT:
        'postgresql://usuario-que-nao-existe:senha@host-que-nao-existe:5432/nada',
    });
    const postgres = postgresFor(config);
    try {
      await expect(postgres.initialize()).resolves.toBeUndefined();
    } finally {
      await postgres.close();
    }
  });
});
