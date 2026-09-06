import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { Inject, Injectable, OnApplicationShutdown } from '@nestjs/common';
import BetterSqlite3, { type Database } from 'better-sqlite3';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { SparkLogger } from '../common/logger';
import { MIGRATIONS_DIRNAME } from './sqlite.constants';
import { appliedVersions, loadMigrations, runMigrations, type Migration } from './migration-runner';

export interface PragmaSnapshot {
  readonly journalMode: string;
  readonly foreignKeys: boolean;
  readonly busyTimeoutMs: number;
}

/**
 * Dono da conexão SQLite do processo.
 *
 * O Spark Backend é um monólito modular com um único banco embarcado: uma conexão, aberta no
 * bootstrap e fechada no shutdown. Nenhum módulo abre a sua própria.
 */
@Injectable()
export class SqliteService implements OnApplicationShutdown {
  private db?: Database;
  private migrations: Migration[] = [];

  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
  ) {}

  /**
   * Abre o arquivo, aplica os PRAGMAs e roda as migrations pendentes.
   *
   * Chamado explicitamente no bootstrap, antes de o HTTP subir: se o banco não abre ou as
   * migrations não aplicam, o processo morre em vez de servir requisições com estado inválido.
   */
  initialize(migrationsDirectory = join(__dirname, '..', '..', MIGRATIONS_DIRNAME)): void {
    if (this.db) {
      return;
    }

    const path = this.config.databasePath;
    if (path !== ':memory:') {
      const directory = dirname(path);
      if (!existsSync(directory)) {
        mkdirSync(directory, { recursive: true });
      }
    }

    const db = new BetterSqlite3(path);

    // WAL permite leitura concorrente com escrita e sobrevive a restart do processo. Em alguns
    // sistemas de arquivos (rede, alguns overlays) o SQLite recusa WAL e mantém o modo anterior —
    // por isso lemos de volta o valor efetivo em vez de assumir que a diretiva pegou.
    db.pragma('journal_mode = WAL');
    // Integridade referencial é decisão do servidor, não de cada query. O SQLite desliga FK por
    // padrão; deixar assim tornaria as futuras tabelas de sync incapazes de recusar um órfão.
    db.pragma('foreign_keys = ON');
    db.pragma(`busy_timeout = ${this.config.sqliteBusyTimeoutMs}`);

    this.db = db;
    this.migrations = loadMigrations(migrationsDirectory);
    const applied = runMigrations(db, this.migrations);

    db.prepare(
      `INSERT INTO server_metadata (key, value, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
    ).run('migrations_applied_at', String(Date.now()), Date.now());

    const pragmas = this.pragmas();
    this.logger.info('database.ready', {
      journalMode: pragmas.journalMode,
      foreignKeys: pragmas.foreignKeys,
      busyTimeoutMs: pragmas.busyTimeoutMs,
      migrationsApplied: applied.length,
      schemaVersion: this.expectedVersions().at(-1) ?? 0,
    });
  }

  get connection(): Database {
    if (!this.db) {
      throw new Error('SQLite não foi inicializado.');
    }
    return this.db;
  }

  get isOpen(): boolean {
    return this.db !== undefined && this.db.open;
  }

  pragmas(): PragmaSnapshot {
    const db = this.connection;
    const [journal] = db.pragma('journal_mode') as Array<{ journal_mode: string }>;
    const [foreignKeys] = db.pragma('foreign_keys') as Array<{ foreign_keys: number }>;
    const [busyTimeout] = db.pragma('busy_timeout') as Array<{ timeout: number }>;
    return {
      journalMode: journal.journal_mode,
      foreignKeys: foreignKeys.foreign_keys === 1,
      busyTimeoutMs: busyTimeout.timeout,
    };
  }

  expectedVersions(): number[] {
    return this.migrations.map((migration) => migration.version);
  }

  appliedVersions(): number[] {
    return appliedVersions(this.connection);
  }

  /** Verificação usada pelo readiness: o banco responde e o schema está na versão esperada. */
  checkHealth(): { reachable: boolean; migrationsUpToDate: boolean } {
    try {
      const db = this.connection;
      db.prepare('SELECT 1').get();
      const expected = this.expectedVersions();
      const applied = new Set(this.appliedVersions());
      return {
        reachable: true,
        migrationsUpToDate: expected.every((version) => applied.has(version)),
      };
    } catch {
      return { reachable: false, migrationsUpToDate: false };
    }
  }

  close(): void {
    if (this.db?.open) {
      // Compacta o WAL de volta no arquivo principal para que o dado sobreviva à remoção do
      // container mesmo se apenas `spark.db` for preservado.
      this.db.pragma('wal_checkpoint(TRUNCATE)');
      this.db.close();
    }
    this.db = undefined;
  }

  onApplicationShutdown(): void {
    this.close();
  }
}
