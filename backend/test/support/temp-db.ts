import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { AppConfig } from '../../src/config/app-config';
import { SparkLogger } from '../../src/common/logger';
import { SqliteService } from '../../src/database/sqlite.service';

export const MIGRATIONS_DIR = join(__dirname, '..', '..', 'migrations');

export interface TempDb {
  readonly path: string;
  readonly directory: string;
  cleanup(): void;
}

/** Cada teste de banco usa um arquivo real em diretório temporário — nunca `:memory:`. */
export function createTempDb(): TempDb {
  const directory = mkdtempSync(join(tmpdir(), 'spark-backend-test-'));
  return {
    directory,
    path: join(directory, 'spark.db'),
    cleanup: () => rmSync(directory, { recursive: true, force: true }),
  };
}

export function configFor(databasePath: string, overrides: Record<string, string> = {}): AppConfig {
  return AppConfig.fromEnv({
    NODE_ENV: 'test',
    LOG_LEVEL: 'silent',
    DATABASE_PATH: databasePath,
    ...overrides,
  });
}

export function sqliteFor(config: AppConfig): SqliteService {
  return new SqliteService(config, new SparkLogger(config));
}
