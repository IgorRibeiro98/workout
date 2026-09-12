import { join } from 'node:path';
import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import { Pool, type PoolClient, type QueryResult, type QueryResultRow, types } from 'pg';
export type { PoolClient, QueryResult, QueryResultRow } from 'pg';

export interface DbClient {
  query<R extends QueryResultRow = QueryResultRow>(
    sql: string,
    params?: unknown[],
  ): Promise<QueryResult<R>>;
}

import { APP_CONFIG, AppConfig } from '../config/app-config';
import { SparkLogger } from '../common/logger';
import { MIGRATIONS_DIRNAME } from './database.constants';
import {
  appliedVersions,
  loadMigrations,
  runMigrations,
  type Migration,
} from './postgres-migration-runner';
import { migrationEndpointViolation, normalizeSslMode } from './postgres-url';

/**
 * O banco não está disponível para esta chamada: pool inexistente, encerrado ou encerrando.
 *
 * Tipo próprio para que quem precise distinguir "sem banco" de "erro do SQL" consiga — o guard de
 * autenticação e o readiness são os dois lugares que se importam.
 */
export class PostgresUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PostgresUnavailableError';
  }
}

/** `pg` marca o pool como `ending` assim que `end()` começa; o campo não é público na tipagem. */
function isEnding(pool: Pool): boolean {
  return Boolean((pool as unknown as { ending?: boolean }).ending);
}

/**
 * Falhas de **conexão** que somem sozinhas numa segunda tentativa (T18.3.2).
 *
 * O caso real: Cloud Run com `min-instances=0` e Neon com autosuspend. A primeira requisição depois
 * de um período sem tráfego acorda os dois ao mesmo tempo, e o Neon leva alguns segundos para
 * aceitar conexão — tempo em que ele responde `57P03 cannot_connect_now`, derruba o socket
 * (`ECONNRESET`) ou simplesmente não responde até o pool desistir. Nada disso é o banco estar fora
 * do ar; é o banco acordando.
 *
 * `57P01` (`admin_shutdown`) entra pelo outro lado da mesma moeda: o Neon encerra conexões ociosas
 * ao suspender, e o pool pode descobrir isso só na hora de reusar uma.
 */
const TRANSIENT_CONNECT_CODES: ReadonlySet<string> = new Set([
  '57P01',
  '57P03',
  'ECONNRESET',
  'ETIMEDOUT',
]);

/** O intervalo antes da única retentativa. Curto: é espera de cold start, não de recuperação. */
const CONNECT_RETRY_DELAY_MS = 1_000;

function isTransientConnectError(error: unknown): boolean {
  const code = (error as { code?: unknown }).code;
  if (typeof code === 'string' && TRANSIENT_CONNECT_CODES.has(code)) {
    return true;
  }
  // O estouro de `connectionTimeoutMillis` do próprio `pg` não tem `code` — só a mensagem. É o
  // sintoma mais provável de um cold start do Neon, e é justamente o que precisa de segunda chance.
  return (
    error instanceof Error && error.message.includes('timeout exceeded when trying to connect')
  );
}

// Configura o parser do driver pg para retornar BIGINT (INT8) como número JavaScript.
// No Spark Backend, timestamps em milissegundos e server_sequence estão bem dentro de Number.MAX_SAFE_INTEGER.
types.setTypeParser(types.builtins.INT8, (val: string) => Number.parseInt(val, 10));

@Injectable()
export class PostgresService implements OnApplicationShutdown, DbClient {
  private poolInstance?: Pool;
  private directPoolInstance?: Pool;
  private migrations: Migration[] = [];
  /** `true` depois de `close()`: distingue "nunca abriu" de "já fechou" na mensagem de erro. */
  private closed = false;

  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
  ) {}

  /**
   * Inicializa o pool e aplica as migrations pendentes.
   *
   * Chamado no bootstrap antes de o HTTP subir: se a conexão falhar ou as
   * migrations não aplicarem, o processo morre com código 1.
   */
  async initialize(
    migrationsDirectory = join(__dirname, '..', '..', MIGRATIONS_DIRNAME),
  ): Promise<void> {
    if (this.poolInstance) {
      return;
    }

    // T18.3 §20 — a política de TLS é explícita na string efetiva, nunca implícita no driver:
    // `sslmode=require` (o que o Neon entrega) vira `verify-full` — exatamente o que o `pg` 8 já
    // fazia por baixo dos panos com um SECURITY WARNING —, para que a próxima major do driver não
    // enfraqueça a conexão em silêncio. Ver `postgres-url.ts`.
    const ssl = normalizeSslMode(this.config.databaseUrl);

    // Se search_path foi especificado na connection string (ex: em testes com esquemas isolados),
    // garante que o schema exista antes de instanciar o pool principal
    const searchPathMatch = /search_path(?:%3D|=)([^&]+)/i.exec(ssl.connectionString);
    if (searchPathMatch) {
      const schemaName = decodeURIComponent(searchPathMatch[1]).trim().split(',')[0].trim();
      if (schemaName && /^[a-zA-Z0-9_]+$/.test(schemaName)) {
        const cleanUrl = ssl.connectionString.replace(/[?&]options=[^&]+/g, '');
        const adminPool = new Pool({ connectionString: cleanUrl, max: 1 });
        try {
          await adminPool.query(`CREATE SCHEMA IF NOT EXISTS "${schemaName}"`);
        } finally {
          await adminPool.end().catch(() => undefined);
        }
      }
    }

    const pool = new Pool({
      connectionString: ssl.connectionString,
      min: this.config.databasePoolMin,
      max: this.config.databasePoolMax,
      connectionTimeoutMillis: this.config.databaseConnectionTimeoutMs,
      idleTimeoutMillis: this.config.databaseIdleTimeoutMs,
      // Parâmetro de **startup** do `pg`: é assim que o teto vale numa conexão direta (VPS, CI,
      // desenvolvimento). Atrás do pooler do Neon ele não chega à conexão de servidor — quem
      // garante o teto ali é o `SET LOCAL` de `beginStatement()`, em toda transação (T18.3.2).
      statement_timeout: this.config.databaseStatementTimeoutMs,
    });

    pool.on('error', (error) => {
      this.logger.error('database.pool.error', {
        errorMessage: error instanceof Error ? error.message : 'Unknown pool error',
      });
    });

    this.poolInstance = pool;
    this.closed = false;

    this.migrations = loadMigrations(migrationsDirectory);

    // T18.2 §6 — `verify` nunca aplica migration e nunca abre o pool direto: o schema já precisa
    // estar no nível esperado quando este modo está ativo (Cloud Run API). `checkHealth()` — a
    // mesma verificação que `/health/ready` já fazia — é quem confere isso, agora também para
    // quem chama `initialize()`. Um schema pendente não derruba o processo: ele sobe e
    // `/health/ready` responde `unavailable`, exatamente como uma dependência externa fora do ar.
    if (this.config.databaseMigrationMode === 'verify') {
      this.logger.info('database.ready', {
        engine: 'PostgreSQL',
        poolMin: this.config.databasePoolMin,
        poolMax: this.config.databasePoolMax,
        migrationMode: 'verify',
        schemaVersion: this.expectedVersions().at(-1) ?? 0,
        // Só o modo — nunca a string. `sslNormalized: true` significa que a URL dizia
        // `require`/`prefer`/`verify-ca` e a política a tornou `verify-full` explícita.
        sslMode: ssl.effectiveSslMode ?? 'none',
        sslNormalized: ssl.normalized,
      });
      return;
    }

    // T18.3.2 — migration nunca sai pelo endpoint pooled.
    //
    // Sem `DATABASE_URL_DIRECT`, `databaseUrlDirect` cai em `DATABASE_URL` — que em produção é o
    // pooler do Neon. O runner usa lock consultivo de SESSÃO e `set_config(..., false)`, e atrás de
    // um PgBouncer em modo transação nenhum dos dois tem dono estável: é a mesma classe do lock
    // preso da T18.3. Recusa aqui, antes de qualquer DDL, e a mensagem nomeia o conserto.
    const migrationViolation = migrationEndpointViolation(
      this.config.databaseUrlDirect,
      this.config.databaseUrlDirectExplicit !== undefined,
    );
    if (migrationViolation !== undefined) {
      throw new Error(`DATABASE_MIGRATION_MODE=apply recusado: ${migrationViolation}.`);
    }

    // Se DATABASE_URL_DIRECT foi configurada diferente da pooled, cria pool dedicado para migrations
    let migrationPool = pool;
    if (this.config.databaseUrlDirect !== this.config.databaseUrl) {
      this.directPoolInstance = new Pool({
        connectionString: normalizeSslMode(this.config.databaseUrlDirect).connectionString,
        max: 2,
        connectionTimeoutMillis: this.config.databaseConnectionTimeoutMs,
      });
      migrationPool = this.directPoolInstance;
    }

    // Uma conexão antes de migrar, com a segunda chance de `connectWithRetry`: é aqui que o cold
    // start do Neon aparece (Cloud Run acordando um banco suspenso), e absorvê-lo neste ponto evita
    // que a primeira recusa derrube o processo antes de qualquer migration. A conexão é devolvida
    // imediatamente — `runMigrations` pega a sua própria, com os timeouts que só ele altera.
    (await this.connectWithRetry(migrationPool)).release();

    // Executa as migrations
    const applied = await runMigrations(migrationPool, this.migrations);

    // Atualiza metadados do servidor
    await pool.query(
      `INSERT INTO server_metadata (key, value, updated_at) VALUES ($1, $2, $3)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at`,
      ['migrations_applied_at', String(Date.now()), Date.now()],
    );

    const schemaVersion = this.expectedVersions().at(-1) ?? 0;

    // Metadata operacional segura: NUNCA loga connection string, senhas ou credenciais
    this.logger.info('database.ready', {
      engine: 'PostgreSQL',
      poolMin: this.config.databasePoolMin,
      poolMax: this.config.databasePoolMax,
      migrationMode: 'apply',
      migrationsApplied: applied.length,
      schemaVersion,
      sslMode: ssl.effectiveSslMode ?? 'none',
      sslNormalized: ssl.normalized,
    });
  }

  get pool(): Pool {
    return this.requireOpenPool();
  }

  get isOpen(): boolean {
    return this.poolInstance !== undefined && !isEnding(this.poolInstance);
  }

  /**
   * O pool, ou um erro explícito (T18.0.2).
   *
   * "Pool não inicializado", "pool encerrado" e "pool encerrando" são **indisponibilidade de
   * infraestrutura**, e precisam falhar de forma visível. A versão anterior de `query()` devolvia
   * `{ rows: [], rowCount: 0 }` nesses casos — o que misturava dois estados que nenhum repositório
   * consegue distinguir depois: "a consulta rodou e não achou nada" e "não havia banco". Um
   * `findById` que devolve `null` porque o pool fechou vira `NOT_FOUND` para o cliente; um guard
   * que lê tombstones vira "conta não excluída". Nenhum dos dois é aceitável, e `transaction()`
   * já lançava — a semântica agora é a mesma nos dois caminhos.
   */
  private requireOpenPool(): Pool {
    if (!this.poolInstance) {
      throw new PostgresUnavailableError(
        this.closed ? 'o pool do PostgreSQL já foi encerrado.' : 'PostgreSQL não foi inicializado.',
      );
    }
    if (isEnding(this.poolInstance)) {
      throw new PostgresUnavailableError('o pool do PostgreSQL está encerrando.');
    }
    return this.poolInstance;
  }

  /**
   * Executa uma query SQL com parâmetros no pool.
   *
   * Lança `PostgresUnavailableError` se o pool não existe ou está encerrando — nunca devolve um
   * resultado vazio sintético.
   */
  async query<R extends QueryResultRow = QueryResultRow, I extends unknown[] = unknown[]>(
    sql: string,
    params?: I,
  ): Promise<QueryResult<R>> {
    const pool = this.requireOpenPool();
    if (params !== undefined) {
      return pool.query<R>(sql, params);
    }
    return pool.query<R>(sql);
  }

  /**
   * Executa uma função dentro de uma transação PostgreSQL atômica.
   * Faz ROLLBACK automático em caso de erro e libera o client de volta ao pool.
   */
  async transaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.connectWithRetry();
    try {
      await client.query(this.beginStatement());
      const result = await work(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw error;
    } finally {
      client.release();
    }
  }

  /**
   * `BEGIN` com o teto de tempo aplicado de um jeito que sobrevive ao pooler (T18.3.2).
   *
   * `statement_timeout` também é passado como parâmetro de **startup** do `pg` (ver `initialize`),
   * e é assim que ele vale numa conexão direta — VPS, CI, desenvolvimento. Atrás do PgBouncer em
   * modo transação, porém, o parâmetro de startup é do cliente que fala com o *pooler*: ele não
   * chega à conexão de servidor que realmente executa a consulta, e produção acabava rodando **sem
   * teto nenhum** enquanto o teste local o respeitava.
   *
   * `SET LOCAL` é de transação: vale exatamente para a mesma conexão de servidor que vai rodar o
   * trabalho, seja ela qual for, e some no COMMIT/ROLLBACK sem deixar resíduo para o próximo
   * cliente do pool — o oposto do `SET` de sessão, que é o mecanismo do incidente do lock preso.
   *
   * As duas instruções vão num `query` só (protocolo simples, várias instruções): o teto não custa
   * um round-trip a mais por transação. O valor vem de `DATABASE_STATEMENT_TIMEOUT_MS`, um inteiro
   * já validado pelo schema — não há entrada externa nesta string.
   */
  private beginStatement(): string {
    return `BEGIN; SET LOCAL statement_timeout = ${this.config.databaseStatementTimeoutMs}`;
  }

  /**
   * Adquire uma conexão do pool, com **uma** segunda tentativa para falha transitória de conexão.
   *
   * Uma só, e só na aquisição: aqui nada foi executado ainda, então repetir não pode duplicar
   * escrita. É o que cobre o cold start do Neon com Cloud Run em `min-instances=0` — o caso em que
   * a primeira conexão depois de um período ocioso falha porque o banco ainda está acordando.
   * `query()` deliberadamente **não** tem retry: um `INSERT` que falhou depois de sair pode ter
   * sido aplicado, e repeti-lo seria escrever duas vezes.
   */
  private async connectWithRetry(target?: Pool): Promise<PoolClient> {
    const acquire = (): Promise<PoolClient> => (target ?? this.requireOpenPool()).connect();
    try {
      return await acquire();
    } catch (error) {
      if (!isTransientConnectError(error)) {
        throw error;
      }
      this.logger.warn('database.connect.retry', {
        errorName: error instanceof Error ? error.name : 'UnknownError',
        delayMs: CONNECT_RETRY_DELAY_MS,
      });
      await new Promise((resolve) => setTimeout(resolve, CONNECT_RETRY_DELAY_MS));
      return acquire();
    }
  }

  expectedVersions(): number[] {
    return this.migrations.map((migration) => migration.version);
  }

  /** As versões registradas em `schema_migrations`. Lança com o pool indisponível, como `query()`. */
  async appliedVersions(): Promise<number[]> {
    return appliedVersions(this.requireOpenPool());
  }

  /**
   * Verificação de saúde usada pelo readiness probe.
   *
   * É o **único** lugar em que indisponibilidade vira valor em vez de erro: `reachable: false` é
   * exatamente a resposta que o readiness existe para dar.
   */
  async checkHealth(): Promise<{ reachable: boolean; migrationsUpToDate: boolean }> {
    try {
      if (!this.isOpen) {
        return { reachable: false, migrationsUpToDate: false };
      }
      await this.requireOpenPool().query('SELECT 1');
      const expected = this.expectedVersions();
      const applied = new Set(await this.appliedVersions());
      return {
        reachable: true,
        migrationsUpToDate: expected.every((version) => applied.has(version)),
      };
    } catch {
      return { reachable: false, migrationsUpToDate: false };
    }
  }

  async close(): Promise<void> {
    if (this.directPoolInstance && !isEnding(this.directPoolInstance)) {
      await this.directPoolInstance.end().catch(() => undefined);
      this.directPoolInstance = undefined;
    }
    if (this.poolInstance && !isEnding(this.poolInstance)) {
      await this.poolInstance.end().catch(() => undefined);
      this.poolInstance = undefined;
    }
    this.closed = true;
  }

  async onApplicationShutdown(): Promise<void> {
    await this.close();
  }
}
