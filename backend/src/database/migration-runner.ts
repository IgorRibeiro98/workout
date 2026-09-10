import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

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

export interface MigrationSyncDb {
  exec(sql: string): void;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  prepare(sql: string): any;
  transaction<T>(fn: () => T): () => T;
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
 */
export function runMigrations(db: MigrationSyncDb, migrations: Migration[]): AppliedMigration[] {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version    INTEGER NOT NULL PRIMARY KEY,
      name       TEXT    NOT NULL,
      applied_at BIGINT  NOT NULL,
      checksum   TEXT
    );
  `);

  const alreadyApplied = new Map(
    (
      db.prepare('SELECT version, name, checksum FROM schema_migrations').all() as Array<{
        version: number;
        name: string;
        checksum: string | null;
      }>
    ).map((row) => [row.version, row]),
  );

  const applied: AppliedMigration[] = [];

  for (const migration of migrations) {
    const previous = alreadyApplied.get(migration.version);
    if (previous !== undefined) {
      if (previous.name !== migration.name) {
        throw new Error(
          `Migration ${migration.version} já aplicada como "${previous.name}", mas o repositório ` +
            `agora traz "${migration.name}". Histórico de migrations não pode ser reescrito.`,
        );
      }

      const checksum = checksumOf(migration.sql);
      if (previous.checksum === null) {
        db.prepare('UPDATE schema_migrations SET checksum = ? WHERE version = ?').run(
          checksum,
          migration.version,
        );
      } else if (previous.checksum !== checksum) {
        throw new Error(
          `Migration ${migration.version} ("${migration.name}") foi editada depois de aplicada. ` +
            `Histórico de migrations não pode ser reescrito: crie uma migration nova.`,
        );
      }
      continue;
    }

    const appliedAt = Date.now();
    const apply = db.transaction(() => {
      db.exec(migration.sql);
      db.prepare(
        'INSERT INTO schema_migrations (version, name, applied_at, checksum) VALUES (?, ?, ?, ?)',
      ).run(migration.version, migration.name, appliedAt, checksumOf(migration.sql));
    });
    apply();

    applied.push({ version: migration.version, name: migration.name, appliedAt });
  }

  return applied;
}

/** SHA-256 do texto da migration. Identidade do conteúdo, nunca segredo. */
function checksumOf(sql: string): string {
  return createHash('sha256').update(sql, 'utf8').digest('hex');
}

export function appliedVersions(db: MigrationSyncDb): number[] {
  try {
    return db
      .prepare('SELECT version FROM schema_migrations ORDER BY version')
      .all()
      .map((row: { version: number }) => row.version);
  } catch {
    return [];
  }
}
