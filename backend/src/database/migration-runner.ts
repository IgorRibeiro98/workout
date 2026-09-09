import { createHash } from 'node:crypto';
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
 * - **imutabilidade do histórico**: uma migration já aplicada que muda de **nome** ou de
 *   **conteúdo** é um erro, não uma reaplicação silenciosa.
 *
 * O checksum (T17.10 §111) fecha a metade que faltava. A verificação por nome pegava um arquivo
 * renomeado, mas não um arquivo **editado**: trocar o corpo de `0015_social_workout_checkins.sql`
 * depois de ele já ter rodado em produção passava despercebido, e o servidor seguia com um schema
 * que nenhuma migration descreve — enquanto uma instalação nova nasceria diferente da antiga. Esse
 * é o defeito mais caro possível num sistema com migrations, porque ele só aparece muito depois,
 * como uma diferença inexplicável entre dois ambientes.
 *
 * Bancos que já existiam antes desta coluna têm `checksum` nulo. O primeiro arranque **grava** o
 * valor atual em vez de recusar: não há como saber retroativamente qual era o conteúdo aplicado, e
 * derrubar o servidor de quem já estava em produção para provar um ponto seria pior do que
 * começar a proteger a partir de agora.
 */
export function runMigrations(db: Database, migrations: Migration[]): AppliedMigration[] {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version    INTEGER NOT NULL PRIMARY KEY,
      name       TEXT    NOT NULL,
      applied_at INTEGER NOT NULL
    ) STRICT;
  `);
  ensureChecksumColumn(db);

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
        // Banco anterior ao checksum: confia no que já está aplicado e passa a proteger daqui
        // em diante.
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

/**
 * Acrescenta `checksum` a um `schema_migrations` que nasceu sem ela.
 *
 * Feito aqui, e não como uma migration numerada: `schema_migrations` é a tabela que o próprio
 * runner cria e mantém — uma migration que alterasse a tabela de controle das migrations
 * dependeria de si mesma para ser registrada.
 */
function ensureChecksumColumn(db: Database): void {
  const columns = db
    .prepare(`SELECT name FROM pragma_table_info('schema_migrations')`)
    .all() as Array<{ name: string }>;
  if (!columns.some((column) => column.name === 'checksum')) {
    db.exec('ALTER TABLE schema_migrations ADD COLUMN checksum TEXT;');
  }
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
