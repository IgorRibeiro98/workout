import { writeFileSync } from 'node:fs';
import { Pool } from 'pg';
import { loadMigrations, runMigrations } from '../../src/database/postgres-migration-runner';
import { normalizeSslMode } from '../../src/database/postgres-url';
import { PgToolError, type PgTools } from '../../src/dr/pg-tools';
import { MIGRATIONS_DIR } from './temp-db';

/**
 * Um `PgTools` sem binários (T18.3).
 *
 * O `pg_dump`/`pg_restore` de verdade são exercitados no ensaio do CI com a imagem real
 * (`ops/gcp/dr-backup-drill.sh`). Aqui o que se testa é tudo o que **cerca** os binários: hash,
 * upload, releitura, manifesto, retenção, guardas de destino e verificação do restaurado — com um
 * dublê que honra o contrato: `dump` escreve bytes conhecidos no arquivo, `listToc` devolve um
 * índice, `restore` aplica um schema real (as migrations do repositório, até a versão pedida) no
 * banco de destino — o que um dump de verdade produziria.
 */
export class FakePgTools implements PgTools {
  dumpBytes: Buffer = Buffer.from('conteudo-de-dump-falso-'.repeat(64));
  toc: string[] = [
    '1; 0 0 TABLE public schema_migrations spark',
    '2; 0 0 TABLE DATA public schema_migrations spark',
  ];
  pgDumpVersion = 'pg_dump (PostgreSQL) 17.11 (fake)';
  /** Até que versão de migration o "dump" restaura. Ausente = todas. */
  restoreUpToVersion: number | undefined;
  failDump: Error | undefined;
  failListToc: Error | undefined;
  failRestore: Error | undefined;
  readonly calls: { dump: string[]; listToc: string[]; restore: string[] } = {
    dump: [],
    listToc: [],
    restore: [],
  };

  dump(connectionUrl: string, outputFile: string): Promise<{ pgDumpVersion: string }> {
    this.calls.dump.push(connectionUrl);
    if (this.failDump) {
      return Promise.reject(this.failDump);
    }
    writeFileSync(outputFile, this.dumpBytes);
    return Promise.resolve({ pgDumpVersion: this.pgDumpVersion });
  }

  listToc(dumpFile: string): Promise<readonly string[]> {
    this.calls.listToc.push(dumpFile);
    if (this.failListToc) {
      return Promise.reject(this.failListToc);
    }
    return Promise.resolve(this.toc);
  }

  async restore(connectionUrl: string, dumpFile: string): Promise<void> {
    this.calls.restore.push(`${connectionUrl} ${dumpFile}`);
    if (this.failRestore) {
      throw this.failRestore;
    }
    const migrations = loadMigrations(MIGRATIONS_DIR).filter(
      (migration) =>
        this.restoreUpToVersion === undefined || migration.version <= this.restoreUpToVersion,
    );
    const pool = new Pool({
      connectionString: normalizeSslMode(connectionUrl).connectionString,
      max: 1,
    });
    try {
      await runMigrations(pool, migrations);
    } finally {
      await pool.end();
    }
  }
}

export const fakeToolFailure = (tool: string): PgToolError =>
  new PgToolError(tool, 1, 'falha injetada pelo dublê');
