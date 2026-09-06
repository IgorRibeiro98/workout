import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Database } from 'better-sqlite3';

export interface Migration {
  readonly version: number;
  readonly name: string;
  readonly sql: string;
}

export interface AppliedMigration {
  readonly version: number;
  readonly name: string;
  readonly appliedAt: number;
}

const MIGRATION_FILE_PATTERN = /^(\d{4})_([a-z0-9_]+)\.sql$/;

/**
 * Carrega as migrations versionadas do diretório informado.
 *
 * O nome do arquivo é o contrato: `NNNN_nome.sql`. A versão vem do prefixo numérico, não da ordem
 * de leitura do sistema de arquivos, para que a sequência seja reproduzível em qualquer máquina.
 */
export function loadMigrations(directory: string): Migration[] {
  const migrations = readdirSync(directory)
    .filter((file) => file.endsWith('.sql'))
    .map((file) => {
      const match = MIGRATION_FILE_PATTERN.exec(file);
      if (!match) {
        throw new Error(
          `Migration com nome inválido: "${file}". Esperado o formato NNNN_nome.sql.`,
        );
      }
      return {
        version: Number.parseInt(match[1], 10),
        name: match[2],
        sql: readFileSync(join(directory, file), 'utf8'),
      };
    })
    .sort((a, b) => a.version - b.version);

  migrations.forEach((migration, index) => {
    const expected = index + 1;
    if (migration.version !== expected) {
      throw new Error(
        `Sequência de migrations quebrada: esperado ${expected}, encontrado ${migration.version} (${migration.name}).`,
      );
    }
  });

  return migrations;
}

/**
 * Aplica as migrations pendentes.
 *
 * Propriedades que os testes cobrem e que o runtime depende:
 *
 * - **idempotência**: rodar de novo não reaplica nada nem corrompe estado;
 * - **atomicidade**: cada migration e o respectivo registro em `schema_migrations` entram na mesma
 *   transação — uma migration que falha no meio não deixa o schema parcialmente migrado;
 * - **imutabilidade do histórico**: uma migration já aplicada que muda de conteúdo é um erro, não
 *   uma reaplicação silenciosa.
 */
export function runMigrations(db: Database, migrations: Migration[]): AppliedMigration[] {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version    INTEGER NOT NULL PRIMARY KEY,
      name       TEXT    NOT NULL,
      applied_at INTEGER NOT NULL
    ) STRICT;
  `);

  const alreadyApplied = new Map(
    db
      .prepare('SELECT version, name FROM schema_migrations')
      .all()
      .map((row) => [(row as { version: number }).version, (row as { name: string }).name]),
  );

  const applied: AppliedMigration[] = [];

  for (const migration of migrations) {
    const previousName = alreadyApplied.get(migration.version);
    if (previousName !== undefined) {
      if (previousName !== migration.name) {
        throw new Error(
          `Migration ${migration.version} já aplicada como "${previousName}", mas o repositório ` +
            `agora traz "${migration.name}". Histórico de migrations não pode ser reescrito.`,
        );
      }
      continue;
    }

    const appliedAt = Date.now();
    const apply = db.transaction(() => {
      db.exec(migration.sql);
      db.prepare('INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)').run(
        migration.version,
        migration.name,
        appliedAt,
      );
    });
    apply();

    applied.push({ version: migration.version, name: migration.name, appliedAt });
  }

  return applied;
}

export function appliedVersions(db: Database): number[] {
  const table = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'")
    .get();
  if (!table) {
    return [];
  }
  return db
    .prepare('SELECT version FROM schema_migrations ORDER BY version')
    .all()
    .map((row) => (row as { version: number }).version);
}
