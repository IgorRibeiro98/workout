/**
 * Contrato de Compartilhamento de Treinos e Programas entre Amigos (T17.7 / T19.3).
 *
 * ## Regras e Fronteiras
 * 1. O compartilhamento é de snapshots portáteis e imutáveis (V1).
 * 2. Somente amigos com relacionamento ativo podem compartilhar.
 * 3. Bloqueio mútuo cancela/invalida ofertas.
 * 4. Cargas, histórico, notas e UIDs privados são estritamente excluídos.
 *
 * ## Dois tipos, uma oferta (T19.3)
 *
 * ```text
 * WORKOUT_TEMPLATE   um treino            snapshot         (T17.7)
 * WORKOUT_PROGRAM    um programa inteiro  programSnapshot  (T19.3)
 * ```
 *
 * A oferta é a mesma linha, o mesmo ciclo de vida e a mesma idempotência; o que muda é a forma do
 * snapshot. Na criação, o campo presente (`snapshot` **ou** `programSnapshot`, nunca os dois) é o
 * discriminador — um `shareType` separado no corpo seria uma segunda afirmação sobre o mesmo
 * fato, e as duas poderiam discordar. Nas respostas o tipo é explícito (`shareType`), e o
 * snapshot volta no campo do seu tipo: um cliente anterior à T19.3 lê `snapshot` ausente numa
 * oferta de programa e não tem o que importar — em vez de decodificar um programa como se fosse
 * um treino sem exercícios.
 *
 * Nos dois tipos, aceitar cria no aparelho do destinatário uma **cópia independente**: nada aqui
 * é vínculo vivo, e o servidor nunca é autoridade do programa ou do treino depois do aceite.
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

export const WORKOUT_SHARE_TYPES = ['WORKOUT_TEMPLATE', 'WORKOUT_PROGRAM'] as const;

export type WorkoutShareType = (typeof WORKOUT_SHARE_TYPES)[number];

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

/**
 * Um treino **dentro** de um programa compartilhado (T19.3).
 *
 * É o mesmo conteúdo portável do treino avulso, mais a posição no programa e os dias da semana —
 * os dois campos estruturais que o `WorkoutTemplate` tem por pertencer a um programa. Nada de
 * carga, nota, máquina, `localId`, `syncId` ou `programId`.
 *
 * `scheduledDays` (T19.8) são **0..N** dias, nomes canônicos de `java.time.DayOfWeek`, sem
 * repetição; vazio é "sem dia fixo". `dayOfWeek` é a forma anterior à T19.8 — um dia só, como
 * rótulo — que um app ainda não atualizado continua enviando; o servidor aceita **uma** das duas
 * formas por treino, nunca as duas, e guarda o snapshot verbatim.
 */
export interface SharedProgramTemplateV1 {
  readonly name: string;
  readonly shortIdentifier?: string | null;
  readonly orderInProgram: number;
  readonly scheduledDays?: readonly string[];
  readonly dayOfWeek?: string | null;
  readonly exercises: SharedExerciseV1[];
}

export interface WorkoutProgramShareSnapshotV1 {
  readonly snapshotVersion: 1;
  readonly name: string;
  readonly description?: string | null;
  readonly templates: SharedProgramTemplateV1[];
}

export type WorkoutShareSnapshotV1 =
  | { readonly shareType: 'WORKOUT_TEMPLATE'; readonly snapshot: WorkoutTemplateShareSnapshotV1 }
  | { readonly shareType: 'WORKOUT_PROGRAM'; readonly snapshot: WorkoutProgramShareSnapshotV1 };

export interface CreateWorkoutShareRequest {
  readonly recipientSocialId: string;
  readonly clientRequestId: string;
  readonly content: WorkoutShareSnapshotV1;
}

export interface WorkoutSharePartyDto {
  readonly socialId: string;
  readonly displayName: string;
}

export interface WorkoutShareItemDto {
  readonly shareId: string;
  readonly shareType: WorkoutShareType;
  readonly status: WorkoutShareStatus;
  readonly createdAt: number;
  readonly expiresAt: number;
  /** O nome do treino — ou, numa oferta de programa, o nome do programa. */
  readonly templateName: string;
  /** Quantos treinos a oferta carrega: sempre 1 para um treino avulso. */
  readonly templateCount: number;
  /** Total de exercícios — somado sobre todos os treinos, numa oferta de programa. */
  readonly exerciseCount: number;
  readonly otherUser: WorkoutSharePartyDto;
}

export interface WorkoutShareDetailDto {
  readonly shareId: string;
  readonly shareType: WorkoutShareType;
  readonly status: WorkoutShareStatus;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly sender: WorkoutSharePartyDto;
  readonly recipient: WorkoutSharePartyDto;
  /** Presente só em `WORKOUT_TEMPLATE`. */
  readonly snapshot?: WorkoutTemplateShareSnapshotV1;
  /** Presente só em `WORKOUT_PROGRAM`. */
  readonly programSnapshot?: WorkoutProgramShareSnapshotV1;
}
