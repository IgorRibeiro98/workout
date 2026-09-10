import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Pool, PoolClient } from 'pg';
import { MIGRATION_ADVISORY_LOCK_KEY } from './database.constants';

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
 * Aplica as migrations pendentes no PostgreSQL.
 *
 * Propriedades garantidas:
 * - **idempotência**: rodar de novo não reaplica nada nem corrompe estado;
 * - **atomicidade**: cada migration e seu registro em `schema_migrations` rodam na mesma transação;
 * - **concorrência segura**: protegido por advisory lock (`pg_advisory_lock`);
 * - **imutabilidade do histórico**: checksum SHA-256 e nome imutáveis.
 */
export async function runMigrations(
  client: PoolClient | Pool,
  migrations: Migration[],
): Promise<AppliedMigration[]> {
  // Advisory lock para serializar instâncias concorrentes
  await client.query('SELECT pg_advisory_lock($1)', [MIGRATION_ADVISORY_LOCK_KEY]);

  try {
    await client.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version    INTEGER NOT NULL PRIMARY KEY,
        name       TEXT    NOT NULL,
        applied_at BIGINT  NOT NULL,
        checksum   TEXT
      );
    `);

    const result = await client.query<{
      version: number;
      name: string;
      checksum: string | null;
    }>('SELECT version, name, checksum FROM schema_migrations ORDER BY version');

    const alreadyApplied = new Map(result.rows.map((row) => [row.version, row]));
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
          await client.query('UPDATE schema_migrations SET checksum = $1 WHERE version = $2', [
            checksum,
            migration.version,
          ]);
        } else if (previous.checksum !== checksum) {
          throw new Error(
            `Migration ${migration.version} ("${migration.name}") foi editada depois de aplicada. ` +
              `Histórico de migrations não pode ser reescrito: crie uma migration nova.`,
          );
        }
        continue;
      }

      const appliedAt = Date.now();
      const checksum = checksumOf(migration.sql);

      // Cada migration é aplicada dentro de uma transação explícita
      await client.query('BEGIN');
      try {
        await client.query(migration.sql);
        await client.query(
          'INSERT INTO schema_migrations (version, name, applied_at, checksum) VALUES ($1, $2, $3, $4)',
          [migration.version, migration.name, appliedAt, checksum],
        );
        await client.query('COMMIT');
      } catch (error) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw error;
      }

      applied.push({ version: migration.version, name: migration.name, appliedAt });
    }

    return applied;
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [MIGRATION_ADVISORY_LOCK_KEY]).catch(() => undefined);
  }
}

/** SHA-256 do texto da migration. Identidade do conteúdo, nunca segredo. */
function checksumOf(sql: string): string {
  return createHash('sha256').update(sql, 'utf8').digest('hex');
}

export async function appliedVersions(client: PoolClient | Pool): Promise<number[]> {
  const tableCheck = await client.query<{ exists: boolean }>(`
    SELECT EXISTS (
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = current_schema() AND table_name = 'schema_migrations'
    ) AS exists
  `);

  if (!tableCheck.rows[0]?.exists) {
    return [];
  }

  const rows = await client.query<{ version: number }>(
    'SELECT version FROM schema_migrations ORDER BY version',
  );
  return rows.rows.map((row) => row.version);
}
