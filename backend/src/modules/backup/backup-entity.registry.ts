import { z } from 'zod';
import { BACKUP_ENTITY_TYPES, type BackupEntityType } from './backup.contract';
import { BACKUP_LIMITS } from './backup.limits';

/**
 * O registry de agregados de backup (T16.4).
 *
 * O servidor **conhece** o que aceita. Não existe caminho onde `entityType` seja um texto qualquer
 * e `payload` um JSON arbitrário: cada tipo declara a versão que sabe ler, o schema estrito do
 * conteúdo e a forma da identidade portátil.
 *
 * `.strict()` em todo objeto é decisão, não detalhe: um campo desconhecido é sinal de contrato
 * divergente entre app e servidor, e guardá-lo em silêncio significaria escrever no backup um dado
 * que ninguém sabe restaurar.
 *
 * Os seis primeiros tipos espelham os DTOs de `com.example.data.sync.dto` (T16.3) — o Android usa
 * o **mesmo** `SyncAggregateSnapshotBuilder` para montá-los, então não há um segundo serializador
 * de treino no projeto.
 */

const L = BACKUP_LIMITS;

const text = z.string().max(L.maxStringLength);
const identifier = z.string().trim().min(1).max(L.maxIdLength);
const epochMillis = z.number().int();
const nullableEpochMillis = epochMillis.nullish();
const nullableNumber = z.number().finite().nullish();
const nullableInt = z.number().int().nullish();
const nullableText = text.nullish();

/** UUID em qualquer caixa. `syncId` nasce v4 minúsculo, mas comparar caixa seria fragilidade. */
const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value);
}

/**
 * Como uma entidade pessoal referencia um exercício: `canonicalId` do catálogo ou `syncId` do
 * exercício criado pelo usuário. Nunca `localId`, nunca nome.
 */
const exerciseRefSchema = z
  .object({
    kind: z.enum(['CANONICAL', 'CUSTOM']),
    id: identifier,
  })
  .strict();

export type ExerciseRef = z.infer<typeof exerciseRefSchema>;

const workoutProgramSchema = z
  .object({
    syncId: identifier,
    name: text,
    description: nullableText,
    isCurrent: z.boolean(),
    externalId: nullableText,
    contentVersion: z.number().int(),
  })
  .strict();

const workoutTemplateExerciseSchema = z
  .object({
    position: z.number().int().min(0),
    exercise: exerciseRefSchema,
    targetSets: z.number().int(),
    minReps: z.number().int(),
    maxReps: z.number().int(),
    restDurationSeconds: z.number().int(),
    plannedWeight: nullableNumber,
    machineLabel: nullableText,
    notes: nullableText,
  })
  .strict();

const workoutTemplateSchema = z
  .object({
    syncId: identifier,
    programSyncId: identifier.nullish(),
    name: text,
    shortIdentifier: nullableText,
    orderInProgram: z.number().int(),
    dayOfWeek: nullableText,
    exercises: z.array(workoutTemplateExerciseSchema).max(L.maxCollectionSize),
  })
  .strict();

const setLogSchema = z
  .object({
    setNumber: z.number().int(),
    type: text,
    weight: z.number().finite(),
    repetitions: z.number().int(),
    completed: z.boolean(),
    startedAt: nullableEpochMillis,
    finishedAt: nullableEpochMillis,
    rpe: nullableNumber,
    rir: nullableInt,
    durationSeconds: nullableInt,
  })
  .strict();

const exerciseSessionSchema = z
  .object({
    plannedOrder: z.number().int(),
    executionOrder: z.number().int(),
    exerciseNameSnapshot: text,
    plannedExercise: exerciseRefSchema.nullish(),
    actualExercise: exerciseRefSchema.nullish(),
    machineLabelSnapshot: nullableText,
    primaryMuscleSnapshot: nullableText,
    restDurationSecondsSnapshot: nullableInt,
    startedAt: nullableEpochMillis,
    finishedAt: nullableEpochMillis,
    notes: nullableText,
    replacementReason: nullableText,
    sets: z.array(setLogSchema).max(L.maxCollectionSize),
  })
  .strict();

/**
 * Só sessão `COMPLETED` entra no backup.
 *
 * `IN_PROGRESS`/`PAUSED` são execução **deste** aparelho, `PLANNED` é derivável do template e da
 * agenda, e `CANCELLED` teve a decisão adiada para a T16.6 pela matriz de dados. Aceitar qualquer
 * status aqui seria o servidor guardar estado vivo de execução — que o protocolo de sync recusa
 * explicitamente.
 */
const workoutSessionSchema = z
  .object({
    syncId: identifier,
    templateSyncId: identifier.nullish(),
    templateNameSnapshot: nullableText,
    status: z.literal('COMPLETED'),
    startedAt: epochMillis,
    finishedAt: nullableEpochMillis,
    notes: nullableText,
    exercises: z.array(exerciseSessionSchema).max(L.maxCollectionSize),
  })
  .strict();

const customExerciseSchema = z
  .object({
    syncId: identifier,
    name: text,
    primaryMuscle: nullableText,
    equipment: nullableText,
    description: nullableText,
    isBodyweight: z.boolean(),
    rirEnabled: z.boolean(),
    active: z.boolean(),
  })
  .strict();

const bodyMeasurementSchema = z
  .object({
    syncId: identifier,
    date: epochMillis,
    createdAt: epochMillis,
    weightKg: nullableNumber,
    heightCm: nullableNumber,
    bodyFatPercentage: nullableNumber,
    waistCm: nullableNumber,
    abdomenCm: nullableNumber,
    chestCm: nullableNumber,
    leftArmCm: nullableNumber,
    rightArmCm: nullableNumber,
    leftThighCm: nullableNumber,
    rightThighCm: nullableNumber,
    calfCm: nullableNumber,
    hipCm: nullableNumber,
  })
  .strict();

const checkInSchema = z
  .object({
    syncId: identifier,
    checkInTime: epochMillis,
    checkOutTime: nullableEpochMillis,
    gymName: nullableText,
    sessionSyncId: identifier.nullish(),
  })
  .strict();

/**
 * Customização de um exercício.
 *
 * `customPhotoUri` **não existe aqui**, de propósito: é um `content://` do aparelho, não uma
 * referência portátil. Mídia continua local na T16.4 (§6 da tarefa) e a UI diz isso ao usuário.
 */
const exerciseOverrideSchema = z
  .object({
    exercise: exerciseRefSchema,
    displayName: nullableText,
    notes: nullableText,
    defaultRestSeconds: nullableInt,
    updatedAt: epochMillis,
  })
  .strict();

const weeklyGoalSchema = z
  .object({
    effectiveFromWeekStartEpochDay: z.number().int(),
    goal: z.number().int(),
    createdAt: epochMillis,
  })
  .strict();

/**
 * As preferências que descrevem o **atleta**, não o aparelho.
 *
 * A matriz de dados já separava as duas famílias: tema, vibração, som, tela ligada e estado do
 * timer de descanso descrevem este aparelho e não entram. Nada foi movido de DataStore para Room
 * para poder entrar no backup — o snapshot tem DTO próprio.
 */
const userPreferencesSchema = z
  .object({
    weeklyGoal: z.number().int(),
    useKg: z.boolean(),
    defaultRestSeconds: z.number().int(),
    defaultExerciseRestSeconds: z.number().int(),
    rirRpeEnabled: z.boolean(),
    autoRestTimerOnSet: z.boolean(),
  })
  .strict();

/** Como a identidade portátil daquele tipo é formada. */
export type IdentityKind = 'UUID' | 'EXERCISE_REF' | 'WEEK' | 'SINGLETON';

export interface BackupEntityDefinition {
  readonly entityType: BackupEntityType;
  readonly supportedSchemaVersions: readonly number[];
  readonly schema: z.ZodType;
  readonly identity: IdentityKind;
}

const DEFINITIONS: Record<BackupEntityType, BackupEntityDefinition> = {
  WORKOUT_PROGRAM: {
    entityType: 'WORKOUT_PROGRAM',
    supportedSchemaVersions: [1],
    schema: workoutProgramSchema,
    identity: 'UUID',
  },
  WORKOUT_TEMPLATE: {
    entityType: 'WORKOUT_TEMPLATE',
    supportedSchemaVersions: [1],
    schema: workoutTemplateSchema,
    identity: 'UUID',
  },
  WORKOUT_SESSION: {
    entityType: 'WORKOUT_SESSION',
    supportedSchemaVersions: [1],
    schema: workoutSessionSchema,
    identity: 'UUID',
  },
  CUSTOM_EXERCISE: {
    entityType: 'CUSTOM_EXERCISE',
    supportedSchemaVersions: [1],
    schema: customExerciseSchema,
    identity: 'UUID',
  },
  BODY_MEASUREMENT: {
    entityType: 'BODY_MEASUREMENT',
    supportedSchemaVersions: [1],
    schema: bodyMeasurementSchema,
    identity: 'UUID',
  },
  CHECK_IN: {
    entityType: 'CHECK_IN',
    supportedSchemaVersions: [1],
    schema: checkInSchema,
    identity: 'UUID',
  },
  EXERCISE_OVERRIDE: {
    entityType: 'EXERCISE_OVERRIDE',
    supportedSchemaVersions: [1],
    schema: exerciseOverrideSchema,
    identity: 'EXERCISE_REF',
  },
  WEEKLY_GOAL: {
    entityType: 'WEEKLY_GOAL',
    supportedSchemaVersions: [1],
    schema: weeklyGoalSchema,
    identity: 'WEEK',
  },
  USER_PREFERENCES: {
    entityType: 'USER_PREFERENCES',
    supportedSchemaVersions: [1],
    schema: userPreferencesSchema,
    identity: 'SINGLETON',
  },
};

export const BackupEntityRegistry = {
  has(entityType: string): entityType is BackupEntityType {
    return (BACKUP_ENTITY_TYPES as readonly string[]).includes(entityType);
  },

  definitionOf(entityType: BackupEntityType): BackupEntityDefinition {
    return DEFINITIONS[entityType];
  },
};

/** Identidade portátil de um `EXERCISE_OVERRIDE`, derivada da referência do exercício alvo. */
export function exerciseRefIdentity(ref: ExerciseRef): string {
  return ref.kind === 'CANONICAL' ? `canonical:${ref.id}` : `custom:${ref.id}`;
}

export const WEEK_IDENTITY_PREFIX = 'week:';
export const PREFERENCES_IDENTITY = 'preferences';

/**
 * A identidade declarada no item bate com a forma exigida pelo tipo **e** com o payload?
 *
 * As duas metades importam. A forma impede `localId` disfarçado de identidade global; a
 * correspondência com o payload impede um item cujo cabeçalho diga uma coisa e cujo conteúdo diga
 * outra — que no restore viraria dado gravado no lugar errado. Não há *fuzzy matching*: ou casa,
 * ou o backup inteiro é recusado.
 */
export function identityMismatch(
  definition: BackupEntityDefinition,
  declaredSyncId: string,
  payload: unknown,
): string | null {
  const record = payload as Record<string, unknown>;

  switch (definition.identity) {
    case 'UUID': {
      if (!isUuid(declaredSyncId)) {
        return 'syncId não é uma identidade global válida';
      }
      if (record.syncId !== declaredSyncId) {
        return 'syncId do item não corresponde ao do payload';
      }
      return null;
    }
    case 'EXERCISE_REF': {
      const expected = exerciseRefIdentity(record.exercise as ExerciseRef);
      if (declaredSyncId !== expected) {
        return 'syncId do item não corresponde ao exercício customizado';
      }
      if (expected.startsWith('custom:') && !isUuid((record.exercise as ExerciseRef).id)) {
        return 'referência de exercício personalizado não é uma identidade global válida';
      }
      return null;
    }
    case 'WEEK': {
      const expected = `${WEEK_IDENTITY_PREFIX}${String(record.effectiveFromWeekStartEpochDay)}`;
      return declaredSyncId === expected ? null : 'syncId do item não corresponde à semana';
    }
    case 'SINGLETON':
      return declaredSyncId === PREFERENCES_IDENTITY
        ? null
        : 'syncId do item precisa ser a identidade singleton do tipo';
  }
}
