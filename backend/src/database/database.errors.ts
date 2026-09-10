/**
 * Utilitários centralizados para tratamento de erros do PostgreSQL.
 * Evita vazamento de detalhes internos do banco para a API pública.
 */

export interface PgErrorLike {
  code?: string;
  constraint?: string;
  message?: string;
  detail?: string;
}

/** Verifica se um erro qualquer é uma violação de integridade do PostgreSQL (classe 23). */
export function isPgConstraintError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const code = (error as PgErrorLike).code;
  return typeof code === 'string' && code.startsWith('23');
}

/**
 * Verifica se o erro é uma violação de unicidade (código 23505 - unique_violation).
 * Opcionalmente verifica se é relativo a uma constraint ou coluna específica.
 */
export function isPgUniqueViolation(error: unknown, constraintOrColumn?: string): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const pgError = error as PgErrorLike;
  if (pgError.code !== '23505') {
    return false;
  }
  if (!constraintOrColumn) {
    return true;
  }
  const target = constraintOrColumn.toLowerCase();
  const constraint = (pgError.constraint ?? '').toLowerCase();
  const detail = (pgError.detail ?? '').toLowerCase();
  const message = (pgError.message ?? '').toLowerCase();
  return constraint.includes(target) || detail.includes(target) || message.includes(target);
}

/** Verifica se o erro é uma violação de chave estrangeira (código 23503 - foreign_key_violation). */
export function isPgForeignKeyViolation(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  return (error as PgErrorLike).code === '23503';
}
