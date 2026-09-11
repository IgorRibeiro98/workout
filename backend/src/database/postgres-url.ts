/**
 * A connection string PostgreSQL como **identidade** e como **política**, num lugar só (T18.3).
 *
 * ## Três perguntas, e por que elas moram juntas
 *
 * 1. **Qual banco esta URL manipula?** Backup, restore e migration são operações administrativas:
 *    cada uma precisa saber, sem ambiguidade, sobre qual database vai agir. `postgres://host` e
 *    `postgres://host/` são URLs válidas para o driver — ele cai no banco default do papel — e
 *    exatamente por isso são recusadas aqui: "o banco default de quem estiver logado" não é uma
 *    identidade, é uma surpresa. Falha fechada (`PostgresUrlIdentityError`), nunca um fallback.
 *
 * 2. **Qual é a política de TLS?** O `pg` 8.x trata `sslmode=require|prefer|verify-ca` como
 *    `verify-full` e avisa (`SECURITY WARNING`, visto nos logs reais de produção) que a próxima
 *    major (`pg` 9 / `pg-connection-string` 3) vai adotar a semântica libpq — em que `require`
 *    **não** verifica certificado nem hostname. A política do Spark para o Neon é `verify-full`:
 *    o certificado do Neon é emitido por CA pública presente na cadeia do Node, então a
 *    verificação completa funciona sem `sslrootcert`. [normalizeSslMode] torna essa intenção
 *    **explícita** na string efetiva, para que uma atualização de major do driver não enfraqueça a
 *    conexão em silêncio — e para que o aviso desapareça porque a intenção foi declarada, não
 *    porque foi silenciada.
 *
 * 3. **Como a mesma URL chega ao `pg_dump`/`pg_restore`/`psql`?** Nunca pelo argv (`ps` da máquina
 *    inteira vê a senha), sempre pelas variáveis de ambiente que a libpq lê (`PGHOST`, `PGUSER`,
 *    `PGPASSWORD`, ...). [libpqEnvironment] faz essa tradução, aplicando a mesma política de TLS:
 *    `PGSSLMODE=verify-full` com `PGSSLROOTCERT=system` (libpq ≥ 16 usa a cadeia do sistema).
 *
 * A senha nunca aparece em mensagem de erro, em log, nem em [PostgresUrlIdentity].
 */

export class PostgresUrlIdentityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PostgresUrlIdentityError';
  }
}

/** O que identifica um banco — sem credencial. Seguro para manifesto e para log. */
export interface PostgresUrlIdentity {
  readonly host: string;
  readonly port: number;
  readonly database: string;
}

export interface ParsedPostgresUrl extends PostgresUrlIdentity {
  readonly user?: string;
  readonly password?: string;
  /** Os parâmetros da query string, já decodificados. `sslmode` é o que importa aqui. */
  readonly params: ReadonlyMap<string, string>;
}

const SCHEMES = new Set(['postgres:', 'postgresql:']);
const DEFAULT_PORT = 5432;

/** Nome de banco aceitável para uma operação administrativa: um identificador PostgreSQL simples. */
const DATABASE_NAME = /^[A-Za-z_][A-Za-z0-9_$-]{0,62}$/;

/**
 * Faz o parse e exige identidade explícita. Lança [PostgresUrlIdentityError] quando a URL não
 * diz, sem ambiguidade, qual database ela manipula.
 */
export function parsePostgresUrl(raw: string): ParsedPostgresUrl {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new PostgresUrlIdentityError('connection string PostgreSQL malformada');
  }
  if (!SCHEMES.has(url.protocol)) {
    throw new PostgresUrlIdentityError(
      `esquema inesperado numa connection string PostgreSQL: ${url.protocol.replace(':', '')}`,
    );
  }
  if (!url.hostname) {
    throw new PostgresUrlIdentityError('connection string PostgreSQL sem host');
  }

  const database = decodeURIComponent(url.pathname.replace(/^\//, ''));
  if (!database) {
    throw new PostgresUrlIdentityError(
      'connection string PostgreSQL sem database explícito no path: operações administrativas ' +
        'exigem saber qual banco manipulam (nunca o banco default do papel)',
    );
  }
  if (database.includes('/') || !DATABASE_NAME.test(database)) {
    throw new PostgresUrlIdentityError('nome de database fora do formato aceito');
  }

  const params = new Map<string, string>();
  url.searchParams.forEach((value, key) => {
    params.set(key, value);
  });

  return {
    host: url.hostname,
    port: url.port ? Number.parseInt(url.port, 10) : DEFAULT_PORT,
    database,
    user: url.username ? decodeURIComponent(url.username) : undefined,
    password: url.password ? decodeURIComponent(url.password) : undefined,
    params,
  };
}

/** Só a identidade (host, porta, database) — o que um manifesto pode carregar. */
export function postgresUrlIdentity(raw: string): PostgresUrlIdentity {
  const { host, port, database } = parsePostgresUrl(raw);
  return { host, port, database };
}

/** Os modos que o `pg` 8 hoje trata como `verify-full` — e que a próxima major vai enfraquecer. */
const SSL_MODES_TREATED_AS_VERIFY_FULL = new Set(['prefer', 'require', 'verify-ca']);

/** Os modos que **não** verificam a identidade do servidor. Produção recusa (T18.3 §20). */
export const WEAK_SSL_MODES: ReadonlySet<string> = new Set(['disable', 'no-verify', 'allow']);

export interface SslNormalization {
  /** A connection string efetiva, com a intenção de TLS explícita. */
  readonly connectionString: string;
  /** O `sslmode` que vai valer, ou `undefined` quando a URL não declara TLS (dev/CI local). */
  readonly effectiveSslMode: string | undefined;
  /** `true` quando a string foi reescrita — para o log de `database.ready` dizer o que aconteceu. */
  readonly normalized: boolean;
}

/**
 * Torna explícita a política de TLS que o `pg` 8 aplica implicitamente.
 *
 * ```text
 * sslmode ausente                       → sem mudança (banco local sem TLS: dev, CI)
 * sslmode=verify-full                   → sem mudança (já é a política)
 * sslmode=require|prefer|verify-ca      → reescrito para verify-full (o que já valia; agora dito)
 * sslmode=disable|no-verify|allow       → sem mudança aqui; produção recusa em missingRequirements
 * uselibpqcompat=true                   → sem mudança aqui; produção recusa em missingRequirements
 * ```
 *
 * `uselibpqcompat=true` é o opt-in do driver para a semântica fraca **agora** — é exatamente o
 * oposto da política, e por isso nunca é normalizado por cima: quem o escreveu quis dizê-lo, e
 * produção precisa ver a recusa, não uma correção silenciosa.
 */
export function normalizeSslMode(raw: string): SslNormalization {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    // Uma string que o `URL` não entende segue intacta: o driver vai recusá-la com a própria
    // mensagem, que é melhor do que uma mensagem inventada aqui.
    return { connectionString: raw, effectiveSslMode: undefined, normalized: false };
  }
  const sslmode = url.searchParams.get('sslmode') ?? undefined;
  const libpqCompat = url.searchParams.get('uselibpqcompat') === 'true';

  if (sslmode !== undefined && !libpqCompat && SSL_MODES_TREATED_AS_VERIFY_FULL.has(sslmode)) {
    url.searchParams.set('sslmode', 'verify-full');
    return { connectionString: url.toString(), effectiveSslMode: 'verify-full', normalized: true };
  }
  return { connectionString: raw, effectiveSslMode: sslmode, normalized: false };
}

/**
 * A política de TLS de uma URL é aceitável em produção?
 *
 * Devolve a razão da recusa, ou `undefined` quando a URL é aceitável. Uma URL sem `sslmode` é
 * aceita: a topologia VPS pode ter o PostgreSQL na mesma rede privada, e não cabe a este módulo
 * decidir por ela; o que ele recusa é a declaração **explícita** de não verificar o servidor.
 */
export function productionSslViolation(raw: string): string | undefined {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return undefined;
  }
  const sslmode = url.searchParams.get('sslmode');
  if (sslmode !== null && WEAK_SSL_MODES.has(sslmode)) {
    return `sslmode=${sslmode} não verifica a identidade do servidor; produção exige verify-full`;
  }
  if (url.searchParams.get('uselibpqcompat') === 'true' && sslmode !== 'verify-full') {
    return 'uselibpqcompat=true adota a semântica libpq (sem verificação); produção exige verify-full';
  }
  return undefined;
}

/**
 * As variáveis de ambiente que a libpq lê, a partir da URL — para `pg_dump`, `pg_restore` e
 * `psql` **sem** connection string no argv.
 *
 * Só as variáveis derivadas da URL: quem chama decide com o que mesclar (`PATH`, `LANG`, ...).
 * `PGSSLROOTCERT=system` vale para libpq ≥ 16 e usa a cadeia de CAs do sistema — o equivalente
 * ao que o Node faz por padrão com `verify-full`. A senha entra por `PGPASSWORD`, que a libpq
 * lê da própria memória do processo filho — nunca do argv.
 */
export function libpqEnvironment(raw: string): Record<string, string> {
  const parsed = parsePostgresUrl(raw);
  const env: Record<string, string> = {
    PGHOST: parsed.host,
    PGPORT: String(parsed.port),
    PGDATABASE: parsed.database,
  };
  if (parsed.user !== undefined) {
    env.PGUSER = parsed.user;
  }
  if (parsed.password !== undefined) {
    env.PGPASSWORD = parsed.password;
  }

  const sslmode = parsed.params.get('sslmode');
  if (sslmode !== undefined) {
    if (SSL_MODES_TREATED_AS_VERIFY_FULL.has(sslmode) || sslmode === 'verify-full') {
      env.PGSSLMODE = 'verify-full';
      env.PGSSLROOTCERT = 'system';
    } else {
      env.PGSSLMODE = sslmode;
    }
  }
  const channelBinding = parsed.params.get('channel_binding');
  if (channelBinding !== undefined) {
    env.PGCHANNELBINDING = channelBinding;
  }
  return env;
}
