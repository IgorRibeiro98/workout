import { join } from 'node:path';
import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import { Pool, type PoolClient, type QueryResult, type QueryResultRow, types } from 'pg';
export type { PoolClient, QueryResult, QueryResultRow } from 'pg';

export interface DbClient {
  query<R extends QueryResultRow = any>(
    sql: string,
    params?: any[],
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

// Configura o parser do driver pg para retornar BIGINT (INT8) como número JavaScript.
// No Spark Backend, timestamps em milissegundos e server_sequence estão bem dentro de Number.MAX_SAFE_INTEGER.
types.setTypeParser(types.builtins.INT8, (val: string) => Number.parseInt(val, 10));

@Injectable()
export class PostgresService implements OnApplicationShutdown, DbClient {
  private poolInstance?: Pool;
  private directPoolInstance?: Pool;
  private migrations: Migration[] = [];

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

    // Se search_path foi especificado na connection string (ex: em testes com esquemas isolados),
    // garante que o schema exista antes de instanciar o pool principal
    const searchPathMatch = /search_path(?:%3D|=)([^&]+)/i.exec(this.config.databaseUrl);
    if (searchPathMatch) {
      const schemaName = decodeURIComponent(searchPathMatch[1]).trim().split(',')[0].trim();
      if (schemaName && /^[a-zA-Z0-9_]+$/.test(schemaName)) {
        const cleanUrl = this.config.databaseUrl.replace(/[?&]options=[^&]+/g, '');
        const adminPool = new Pool({ connectionString: cleanUrl, max: 1 });
        try {
          await adminPool.query(`CREATE SCHEMA IF NOT EXISTS "${schemaName}"`);
        } finally {
          await adminPool.end().catch(() => undefined);
        }
      }
    }

    const pool = new Pool({
      connectionString: this.config.databaseUrl,
      min: this.config.databasePoolMin,
      max: this.config.databasePoolMax,
      connectionTimeoutMillis: this.config.databaseConnectionTimeoutMs,
      idleTimeoutMillis: this.config.databaseIdleTimeoutMs,
      statement_timeout: this.config.databaseStatementTimeoutMs,
    });

    pool.on('error', (error) => {
      this.logger.error('database.pool.error', {
        errorMessage: error instanceof Error ? error.message : 'Unknown pool error',
      });
    });

    this.poolInstance = pool;

    // Se DATABASE_URL_DIRECT foi configurada diferente da pooled, cria pool dedicado para migrations
    let migrationPool = pool;
    if (this.config.databaseUrlDirect !== this.config.databaseUrl) {
      this.directPoolInstance = new Pool({
        connectionString: this.config.databaseUrlDirect,
        max: 2,
        connectionTimeoutMillis: this.config.databaseConnectionTimeoutMs,
      });
      migrationPool = this.directPoolInstance;
    }

    this.migrations = loadMigrations(migrationsDirectory);

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
      migrationsApplied: applied.length,
      schemaVersion,
    });
  }

  get pool(): Pool {
    if (!this.poolInstance) {
      throw new Error('PostgreSQL não foi inicializado.');
    }
    return this.poolInstance;
  }

  get isOpen(): boolean {
    return this.poolInstance !== undefined && !this.poolInstance.ended;
  }

  /**
   * Executa uma query SQL com parâmetros no pool.
   */
  async query<R extends QueryResultRow = any, I extends unknown[] = any[]>(
    sql: string,
    params?: I,
  ): Promise<QueryResult<R>> {
    if (params !== undefined) {
      return this.pool.query<R>(sql, params);
    }
    return this.pool.query<R>(sql);
  }

  /**
   * Executa uma função dentro de uma transação PostgreSQL atômica.
   * Faz ROLLBACK automático em caso de erro e libera o client de volta ao pool.
   */
  async transaction<T>(work: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
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

  expectedVersions(): number[] {
    return this.migrations.map((migration) => migration.version);
  }

  async appliedVersions(): Promise<number[]> {
    if (!this.poolInstance) return [];
    return appliedVersions(this.poolInstance);
  }

  /**
   * Verificação de saúde usada pelo readiness probe.
   */
  async checkHealth(): Promise<{ reachable: boolean; migrationsUpToDate: boolean }> {
    try {
      if (!this.poolInstance) {
        return { reachable: false, migrationsUpToDate: false };
      }
      await this.poolInstance.query('SELECT 1');
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
    if (this.directPoolInstance && !this.directPoolInstance.ended) {
      await this.directPoolInstance.end().catch(() => undefined);
      this.directPoolInstance = undefined;
    }
    if (this.poolInstance && !this.poolInstance.ended) {
      await this.poolInstance.end().catch(() => undefined);
      this.poolInstance = undefined;
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.close();
  }
}
