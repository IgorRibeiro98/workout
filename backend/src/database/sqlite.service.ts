import { existsSync, mkdirSync, statSync } from 'node:fs';
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
  readonly synchronous: number;
  readonly walAutocheckpointPages: number;
}

/**
 * O tamanho ocupado em disco pelo banco e pelos artefatos do WAL (T16.8 §74/§76).
 *
 * Só tamanho — nunca conteúdo. É a informação que responde "o disco vai encher?" sem que nada do
 * dado do usuário apareça em log ou em diagnóstico.
 */
export interface StorageSnapshot {
  readonly databaseBytes: number;
  readonly walBytes: number;
  readonly shmBytes: number;
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
    // `synchronous` é escolhido explicitamente (T16.8 §18), e não herdado do default do driver.
    // O porquê de `FULL` ser o default está em `env.schema.ts`: uma transação confirmada e depois
    // perdida é dado que o aparelho já considera salvo — a Outbox local só é liberada com a
    // confirmação deste servidor.
    db.pragma(`synchronous = ${this.config.sqliteSynchronous}`);
    // Teto de crescimento do WAL. O valor é o default do SQLite; declará-lo torna a política
    // visível em vez de implícita, e permite baixá-la numa VPS com pouco disco.
    db.pragma(`wal_autocheckpoint = ${this.config.sqliteWalAutocheckpointPages}`);

    this.db = db;
    this.migrations = loadMigrations(migrationsDirectory);
    const applied = runMigrations(db, this.migrations);

    db.prepare(
      `INSERT INTO server_metadata (key, value, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
    ).run('migrations_applied_at', String(Date.now()), Date.now());

    const pragmas = this.pragmas();
    const storage = this.storage();
    this.logger.info('database.ready', {
      journalMode: pragmas.journalMode,
      foreignKeys: pragmas.foreignKeys,
      busyTimeoutMs: pragmas.busyTimeoutMs,
      synchronous: pragmas.synchronous,
      walAutocheckpointPages: pragmas.walAutocheckpointPages,
      migrationsApplied: applied.length,
      schemaVersion: this.expectedVersions().at(-1) ?? 0,
      // Tamanho, nunca conteúdo: é o que permite acompanhar crescimento sem registrar dado.
      databaseBytes: storage.databaseBytes,
      walBytes: storage.walBytes,
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
    const [synchronous] = db.pragma('synchronous') as Array<{ synchronous: number }>;
    const [autocheckpoint] = db.pragma('wal_autocheckpoint') as Array<{
      wal_autocheckpoint: number;
    }>;
    return {
      journalMode: journal.journal_mode,
      foreignKeys: foreignKeys.foreign_keys === 1,
      busyTimeoutMs: busyTimeout.timeout,
      synchronous: synchronous.synchronous,
      walAutocheckpointPages: autocheckpoint.wal_autocheckpoint,
    };
  }

  /**
   * Quanto o banco ocupa em disco, agora.
   *
   * Existe para o diagnóstico operacional da T16.8: disco cheio é o modo de falha mais provável
   * de um SQLite em VPS pequena, e ele é silencioso até o momento em que deixa de ser. Devolve
   * zeros para `:memory:` e para arquivo ainda inexistente — ausência não é erro aqui.
   */
  storage(): StorageSnapshot {
    const path = this.config.databasePath;
    if (path === ':memory:') {
      return { databaseBytes: 0, walBytes: 0, shmBytes: 0 };
    }
    return {
      databaseBytes: sizeOf(path),
      walBytes: sizeOf(`${path}-wal`),
      shmBytes: sizeOf(`${path}-shm`),
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

/** Tamanho de um arquivo, ou `0` quando ele ainda não existe. */
function sizeOf(path: string): number {
  try {
    return statSync(path).size;
  } catch {
    return 0;
  }
}
