/**
 * Contrato de Compartilhamento de Treinos entre Amigos (T17.7).
 *
 * ## Regras e Fronteiras
 * 1. O compartilhamento é de snapshots portáteis e imutáveis (V1).
 * 2. Somente amigos com relacionamento ativo podem compartilhar.
 * 3. Bloqueio mútuo cancela/invalida ofertas.
 * 4. Cargas, histórico, notas e UIDs privados são estritamente excluídos.
 */

export const WORKOUT_SHARE_STATUSES = [
  'PENDING',
  'ACCEPTED',
  'IMPORTED',
  'DECLINED',
  'CANCELLED',
  'EXPIRED',
] as const;

export type WorkoutShareStatus = (typeof WORKOUT_SHARE_STATUSES)[number];

export const WorkoutShareErrorCodes = {
  SHARE_NOT_FOUND: 'WORKOUT_SHARE_NOT_FOUND',
  CANNOT_SHARE_WITH_SELF: 'CANNOT_SHARE_WITH_SELF',
  FRIENDSHIP_REQUIRED: 'FRIENDSHIP_REQUIRED',
  BLOCKED_USER: 'BLOCKED_USER',
  SHARE_NOT_AVAILABLE: 'SHARE_NOT_AVAILABLE',
  INVALID_SNAPSHOT: 'INVALID_SNAPSHOT',
  RATE_LIMITED: 'WORKOUT_SHARE_RATE_LIMITED',
  CONFLICT: 'WORKOUT_SHARE_CONFLICT',
  RECIPIENT_NOT_FOUND: 'RECIPIENT_NOT_FOUND',
  SOCIAL_NOT_ENABLED: 'SOCIAL_NOT_ENABLED',
} as const;

export interface SharedExerciseV1 {
  readonly canonicalExerciseId: string;
  readonly sortOrder: number;
  readonly targetSets: number;
  readonly minReps: number;
  readonly maxReps: number;
  readonly restDurationSeconds: number;
}

export interface WorkoutTemplateShareSnapshotV1 {
  readonly snapshotVersion: 1;
  readonly name: string;
  readonly shortIdentifier?: string | null;
  readonly exercises: SharedExerciseV1[];
}

export interface CreateWorkoutShareRequest {
  readonly recipientSocialId: string;
  readonly clientRequestId: string;
  readonly snapshot: WorkoutTemplateShareSnapshotV1;
}

export interface WorkoutSharePartyDto {
  readonly socialId: string;
  readonly displayName: string;
}

export interface WorkoutShareItemDto {
  readonly shareId: string;
  readonly status: WorkoutShareStatus;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly templateName: string;
  readonly exerciseCount: number;
  readonly otherUser: WorkoutSharePartyDto;
}

export interface WorkoutShareDetailDto {
  readonly shareId: string;
  readonly status: WorkoutShareStatus;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly sender: WorkoutSharePartyDto;
  readonly recipient: WorkoutSharePartyDto;
  readonly snapshot?: WorkoutTemplateShareSnapshotV1;
}
