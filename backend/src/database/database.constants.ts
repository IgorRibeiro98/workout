/** Diretório das migrations versionadas PostgreSQL, relativo à raiz do pacote `backend`. */
export const MIGRATIONS_DIRNAME = 'migrations/postgres';

/** ID do lock consultivo (advisory lock) para serializar execução de migrations concorrentes. */
export const MIGRATION_ADVISORY_LOCK_KEY = 482910384;
