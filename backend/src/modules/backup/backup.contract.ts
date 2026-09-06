/**
 * O contrato de backup entre o Spark Android e o Spark Backend (T16.4).
 *
 * A definição legível — envelope, identidades, hash, idempotência, erros — vive em
 * `contracts/backup/v1/README.md`, junto das fixtures que os testes dos dois lados consomem.
 * Este arquivo é a metade TypeScript dela: os nomes que não podem divergir em silêncio.
 *
 * O espelho Kotlin é `com.example.data.backup.BackupContract`.
 */

/**
 * Versão do **formato de backup**.
 *
 * Não se confunde com nenhuma das outras versões do projeto: não é a versão do banco Room, não é
 * o `/v1` da API, não é `entitySchemaVersion` (que é por agregado) e não é a versão do app.
 */
export const BACKUP_SCHEMA_VERSION = 1;

export const SUPPORTED_BACKUP_SCHEMA_VERSIONS: readonly number[] = [1];

export function isSupportedBackupSchemaVersion(version: number): boolean {
  return SUPPORTED_BACKUP_SCHEMA_VERSIONS.includes(version);
}

/**
 * Os agregados que um backup pode conter. Registry **fechado**.
 *
 * Os seis primeiros são os `SyncEntityType` da T16.3. Os três últimos existem só no backup: eles
 * são dado pessoal do Grupo A da matriz, mas ainda não produzem mutação incremental — o snapshot
 * completo os cobre, e a T16.6 precisará lhes dar mutação própria.
 */
export const BACKUP_ENTITY_TYPES = [
  'WORKOUT_PROGRAM',
  'WORKOUT_TEMPLATE',
  'WORKOUT_SESSION',
  'CUSTOM_EXERCISE',
  'BODY_MEASUREMENT',
  'CHECK_IN',
  'EXERCISE_OVERRIDE',
  'WEEKLY_GOAL',
  'USER_PREFERENCES',
] as const;

export type BackupEntityType = (typeof BACKUP_ENTITY_TYPES)[number];

/**
 * Códigos de erro do backup, no envelope da T16.0 (`{ error: { code, message, requestId } }`).
 *
 * Nenhuma mensagem associada a estes códigos repete conteúdo do snapshot — nem nome de treino,
 * nem nota, nem medida (§50 da T16.2, §77 da T16.4).
 */
export const BACKUP_ERROR_CODES = {
  /** Envelope, item, identidade ou relação fora do contrato. */
  INVALID_BACKUP: 'INVALID_BACKUP',
  /** `backupSchemaVersion` que este servidor não sabe interpretar. */
  UNSUPPORTED_BACKUP_SCHEMA_VERSION: 'UNSUPPORTED_BACKUP_SCHEMA_VERSION',
  /** `entitySchemaVersion` desconhecida para aquele `entityType`. */
  UNSUPPORTED_ENTITY_SCHEMA_VERSION: 'UNSUPPORTED_ENTITY_SCHEMA_VERSION',
  /** Mesmo `clientBackupId` da mesma conta, com conteúdo diferente. */
  BACKUP_IDEMPOTENCY_CONFLICT: 'BACKUP_IDEMPOTENCY_CONFLICT',
  /** Corpo, número de itens ou item acima do teto. */
  BACKUP_TOO_LARGE: 'BACKUP_TOO_LARGE',
  /** Nenhum backup desta conta. */
  BACKUP_NOT_FOUND: 'BACKUP_NOT_FOUND',
} as const;

export type BackupErrorCode = (typeof BACKUP_ERROR_CODES)[keyof typeof BACKUP_ERROR_CODES];

/**
 * O que o Android recebe de volta: **metadata**, nunca o snapshot.
 *
 * Devolver o conteúdo depois do upload não serviria a nada nesta fase e já seria meio caminho do
 * download que a T16.5 vai desenhar com validação e preview.
 */
export interface BackupMetadataResponse {
  readonly backupId: string;
  readonly clientBackupId: string;
  readonly backupSchemaVersion: number;
  /** Relógio do **servidor**. É esta a autoridade de "quando" e de "qual é o mais recente". */
  readonly createdAt: number;
  readonly itemCount: number;
  readonly sizeBytes: number;
  readonly payloadHash: string;
}
