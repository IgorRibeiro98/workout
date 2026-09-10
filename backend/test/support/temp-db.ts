import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { AppConfig } from '../../src/config/app-config';
import { SparkLogger } from '../../src/common/logger';
import { PostgresService } from '../../src/database/postgres.service';
export { createPostgresSyncDb, type PostgresSyncDb } from './postgres-sync-db';

export const MIGRATIONS_DIR = join(__dirname, '..', '..', 'migrations', 'postgres');
export const DEFAULT_TEST_DATABASE_URL =
  process.env.DATABASE_URL || 'postgresql://spark:spark@localhost:5432/spark_dev';

export interface TempDb {
  readonly schema: string;
  readonly path: string;
  readonly databaseUrl: string;
  readonly directory: string;
  readonly sqlitePath: string;
  cleanup(): void;
}

/** A raiz de objetos de cada banco temporário, pelo schema — ver `objectRootFor`. */
const OBJECT_ROOTS = new Map<string, string>();

function objectRootFor(databaseUrl: string): string {
  const match = /search_path(?:%3D|=)([^&]+)/i.exec(databaseUrl);
  const schema = match ? decodeURIComponent(match[1]).trim() : undefined;
  const known = schema !== undefined ? OBJECT_ROOTS.get(schema) : undefined;
  return known ?? mkdtempSync(join(tmpdir(), 'spark-objects-'));
}

export function createTempDb(): TempDb {
  const directory = mkdtempSync(join(tmpdir(), 'spark-backend-test-'));
  const sqlitePath = join(directory, 'test.db');
  const schema =
    'test_' + Date.now().toString(36) + '_' + Math.random().toString(36).substring(2, 8);
  const baseDatabaseUrl = process.env.DATABASE_URL || DEFAULT_TEST_DATABASE_URL;
  OBJECT_ROOTS.set(schema, join(directory, 'objects'));

  const databaseUrl = baseDatabaseUrl.includes('?')
    ? `${baseDatabaseUrl}&options=-csearch_path%3D${schema}`
    : `${baseDatabaseUrl}?options=-csearch_path%3D${schema}`;

  return {
    schema,
    directory,
    databaseUrl,
    path: databaseUrl,
    sqlitePath,
    cleanup: () => {
      try {
        rmSync(directory, { recursive: true, force: true });
      } catch {
        // ignore
      }
      try {
        const dropSql = `DROP SCHEMA IF EXISTS "${schema}" CASCADE; DROP SCHEMA IF EXISTS "${schema}_snap" CASCADE;`;
        const cleanUrl = baseDatabaseUrl.replace(/[?&]options=[^&]+/g, '');
        try {
          const code = `const { Client } = require('pg'); const c = new Client({ connectionString: ${JSON.stringify(cleanUrl)} }); c.connect().then(() => c.query(${JSON.stringify(dropSql)})).then(() => c.end()).catch(() => process.exit(0));`;
          execFileSync(process.execPath, ['-e', code], { timeout: 5000, stdio: 'ignore' });
        } catch {
          try {
            execFileSync(
              'docker',
              [
                'exec',
                'spark-postgres-dev',
                'psql',
                '-U',
                'spark',
                '-d',
                'spark_dev',
                '-c',
                dropSql,
              ],
              { timeout: 5000, stdio: 'ignore' },
            );
          } catch {
            // ignore
          }
        }
      } catch {
        // ignore
      }
    },
  };
}

export function configFor(
  databasePathOrUrl?: string,
  overrides: Record<string, string> = {},
): AppConfig {
  const raw = databasePathOrUrl ?? (process.env.DATABASE_URL || DEFAULT_TEST_DATABASE_URL);
  const isUrl = raw.startsWith('postgres://') || raw.startsWith('postgresql://');
  const databaseUrl = isUrl ? raw : process.env.DATABASE_URL || DEFAULT_TEST_DATABASE_URL;
  return AppConfig.fromEnv({
    NODE_ENV: 'test',
    LOG_LEVEL: 'silent',
    DATABASE_URL: databaseUrl,
    // A raiz do Object Storage local (T18.1): o diretório temporário do banco de teste, e nunca o
    // `.spark-media` derivado do diretório de trabalho — desde a T18.1 todo backup grava um
    // objeto, e uma suíte que não declara a raiz deixaria documentos de backup na árvore do
    // repositório. A mesma raiz para toda configuração do mesmo banco temporário, para que um
    // teste que "reinicia" o app continue vendo os objetos que gravou antes.
    SOCIAL_MEDIA_ROOT: objectRootFor(databaseUrl),
    ...overrides,
  });
}

export function postgresFor(config: AppConfig): PostgresService {
  return new PostgresService(config, new SparkLogger(config));
}

// Alias de compatibilidade para suítes existentes:
export const sqliteFor = postgresFor as (config: AppConfig) => PostgresService;
