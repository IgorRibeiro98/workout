import { spawn } from 'node:child_process';
import { libpqEnvironment, parsePostgresUrl } from '../database/postgres-url';

/**
 * As ferramentas nativas do PostgreSQL, atrás de uma interface (T18.3 §2/§5).
 *
 * ## Por que uma interface
 *
 * `pg_dump` e `pg_restore` são binários: um dump consistente e um restore em transação única não
 * têm equivalente em `pg` (o driver). A imagem do backend os traz (`postgresql-client-18`, ver o
 * Dockerfile), e os dois Jobs de DR os invocam por processo. A suíte Jest, porém, roda onde eles
 * podem não existir — ou existir numa major menor que a do servidor —, então a lógica que **cerca**
 * os binários (hash, upload, manifesto, retenção, verificação, guardas de destino) é testada com
 * um dublê que honra o mesmo contrato, e os binários de verdade são exercitados no ensaio do CI
 * (`ops/gcp/dr-backup-drill.sh`), com a imagem real.
 *
 * ## A senha nunca passa pelo argv
 *
 * Toda conexão entra pelas variáveis que a libpq lê (`PGHOST`, `PGPASSWORD`, ..., via
 * `libpqEnvironment`). O argv de `pg_dump`/`pg_restore` carrega só flags e caminhos de arquivo —
 * `ps` na máquina inteira não vê a connection string. É a mesma regra de `ops/lib.sh#pg_run`.
 */
export interface PgTools {
  /** `pg_dump --format=custom` para [outputFile]. Devolve a versão do `pg_dump`. */
  dump(connectionUrl: string, outputFile: string): Promise<{ readonly pgDumpVersion: string }>;
  /** `pg_restore --list`: as entradas do índice (sem linhas de comentário). Falha em arquivo corrompido. */
  listToc(dumpFile: string): Promise<readonly string[]>;
  /** `pg_restore` em transação única, sem `--clean`: o destino precisa estar limpo. */
  restore(connectionUrl: string, dumpFile: string): Promise<void>;
}

export class PgToolError extends Error {
  constructor(
    readonly tool: string,
    readonly exitCode: number | null,
    detail: string,
  ) {
    super(`${tool} falhou (código ${exitCode ?? 'sinal'}): ${detail}`);
    this.name = 'PgToolError';
  }
}

/** Quantos bytes de stderr entram numa mensagem de erro. Nunca a saída inteira. */
const STDERR_TAIL = 2_000;

function run(
  tool: string,
  args: readonly string[],
  env: NodeJS.ProcessEnv,
): Promise<{ stdout: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(tool, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    const out: Buffer[] = [];
    let err = '';
    child.stdout.on('data', (chunk: Buffer) => out.push(chunk));
    child.stderr.on('data', (chunk: Buffer) => {
      err = (err + chunk.toString('utf8')).slice(-STDERR_TAIL);
    });
    child.on('error', (error) => {
      reject(new PgToolError(tool, null, error.message));
    });
    child.on('close', (code) => {
      if (code === 0) {
        resolve({ stdout: Buffer.concat(out).toString('utf8') });
      } else {
        reject(new PgToolError(tool, code, err.trim() || 'sem saída de erro'));
      }
    });
  });
}

/** O ambiente do processo filho: o mínimo do host (`PATH`, locale) mais a conexão. */
function toolEnvironment(connectionUrl: string | undefined): NodeJS.ProcessEnv {
  const base: NodeJS.ProcessEnv = {
    PATH: process.env.PATH,
    LANG: process.env.LANG ?? 'C.UTF-8',
    LC_ALL: process.env.LC_ALL ?? 'C.UTF-8',
    HOME: process.env.HOME,
  };
  return connectionUrl === undefined ? base : { ...base, ...libpqEnvironment(connectionUrl) };
}

export class ProcessPgTools implements PgTools {
  async dump(connectionUrl: string, outputFile: string): Promise<{ pgDumpVersion: string }> {
    const version = await run('pg_dump', ['--version'], toolEnvironment(undefined));
    // `--no-owner --no-privileges`: dono e GRANT são configuração do destino, não conteúdo do
    // backup (o papel do Neon não existe num PostgreSQL local, e vice-versa). Custom: comprimido,
    // com índice (`--list` lê sem restaurar) e o único formato que aceita `--single-transaction`.
    await run(
      'pg_dump',
      ['--format=custom', '--no-owner', '--no-privileges', `--file=${outputFile}`],
      toolEnvironment(connectionUrl),
    );
    return { pgDumpVersion: version.stdout.trim() };
  }

  async listToc(dumpFile: string): Promise<readonly string[]> {
    const { stdout } = await run('pg_restore', ['--list', dumpFile], toolEnvironment(undefined));
    return stdout
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0 && !line.startsWith(';'));
  }

  async restore(connectionUrl: string, dumpFile: string): Promise<void> {
    // Sem `--clean`/`--if-exists` de propósito (T18.3 §5): o destino é um banco recém-criado, e
    // "limpar" um destino que já tem objetos é exatamente o mecanismo que deixa sobreviver o que
    // o dump não conhece (`ops/tests/restore-old-snapshot-risk.test.sh`).
    //
    // `--dbname` recebe só o **nome** do banco (não é segredo); host, porta, papel e senha entram
    // pelo ambiente. Sem `--dbname`, `pg_restore` não restaura nada — ele imprime SQL na saída.
    const { database } = parsePostgresUrl(connectionUrl);
    await run(
      'pg_restore',
      [
        '--no-owner',
        '--no-privileges',
        '--single-transaction',
        '--exit-on-error',
        `--dbname=${database}`,
        dumpFile,
      ],
      toolEnvironment(connectionUrl),
    );
  }
}
