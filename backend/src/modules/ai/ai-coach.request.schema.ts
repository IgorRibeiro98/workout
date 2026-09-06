import { z } from 'zod';
import { AI_COACH_REQUEST_TYPES } from './ai-coach.contract';
import { CONTEXT_LIMITS } from './ai-coach.limits';

/**
 * O contrato de **entrada** do Coach, validado antes de qualquer coisa custar dinheiro.
 *
 * Ele espelha as projeções que o Android já monta (`AiCoachContext`,
 * `AiWorkoutGenerationContext`, `AiWorkoutAdaptationContext`, `AiCoachExplanationContext`) — o
 * backend **não** monta contexto e **não** lê dado sincronizado: nesta fase o contexto vem do
 * Room, que é a autoridade (§3 da T16.2).
 *
 * Todo campo de texto tem teto e todo array tem teto: o servidor não pode supor que só o APK
 * oficial faz requisições. `.strict()` recusa campo desconhecido em vez de ignorá-lo — um
 * contexto com campo inventado é sinal de contrato divergente, não de cliente esperto.
 */

const L = CONTEXT_LIMITS;

const exerciseId = z.string().trim().min(1).max(L.maxIdLength);
const displayName = z.string().max(L.maxNameLength);
const label = z.string().max(L.maxLabelLength);
const sentence = z.string().max(L.maxSentenceLength);

/** `null` explícito é o que o kotlinx serializa para campo ausente; `undefined` também vale. */
const nullableInt = z.number().int().nullish();
const nullableNumber = z.number().finite().nullish();

export const dataQualityLevelSchema = z.enum(['INSUFFICIENT', 'LIMITED', 'GOOD']);

export const plannedExerciseSchema = z
  .object({
    exerciseId,
    name: displayName,
    targetSets: nullableInt,
    minReps: nullableInt,
    maxReps: nullableInt,
    plannedWeightKg: nullableNumber,
    restSeconds: nullableInt,
  })
  .strict();

export const workoutContextSchema = z
  .object({
    templateName: displayName.nullish(),
    exercises: z.array(plannedExerciseSchema).max(L.maxExercisesInContext).default([]),
  })
  .strict();

export const exerciseExecutionSchema = z
  .object({
    finishedAtEpochMs: nullableInt,
    completedSets: z.number().int(),
    maxWeightKg: nullableNumber,
    totalReps: nullableInt,
  })
  .strict();

export const exerciseHistorySchema = z
  .object({
    exerciseId,
    name: displayName,
    sessionsAnalyzed: z.number().int(),
    executions: z.array(exerciseExecutionSchema).max(L.maxExecutionsPerExercise).default([]),
  })
  .strict();

export const personalRecordSchema = z
  .object({
    exerciseId,
    name: displayName,
    type: label,
    value: z.number().finite(),
    achievedAtEpochMs: nullableInt,
  })
  .strict();

export const evidenceSchema = z
  .object({
    sessionsAnalyzed: z.number().int().default(0),
    exercisesWithHistory: z.number().int().default(0),
    maxDataQuality: dataQualityLevelSchema.default('INSUFFICIENT'),
  })
  .strict();

export const candidateExerciseSchema = z
  .object({
    exerciseId,
    name: displayName,
    muscleGroup: label,
    equipment: label.nullish(),
  })
  .strict();

export const loadEvidenceSchema = z
  .object({
    exerciseId,
    lastWeightKg: nullableNumber,
    lastReps: nullableInt,
    sessionsWithHistory: z.number().int().default(0),
  })
  .strict();

/** Contexto de `ANALYZE_WORKOUT` — espelha `AiCoachContext`. */
export const analysisContextSchema = z
  .object({
    athlete: z
      .object({
        weeklyGoal: nullableInt,
        completedSessionsInWindow: z.number().int().default(0),
      })
      .strict(),
    currentWorkout: workoutContextSchema.nullish(),
    exerciseHistory: z.array(exerciseHistorySchema).max(L.maxExercisesInContext).default([]),
    personalRecords: z.array(personalRecordSchema).max(L.maxPersonalRecords).default([]),
    evidence: evidenceSchema.default({
      sessionsAnalyzed: 0,
      exercisesWithHistory: 0,
      maxDataQuality: 'INSUFFICIENT',
    }),
  })
  .strict();

/** Contexto de `GENERATE_WORKOUT` — espelha `AiWorkoutGenerationContext`. */
export const generationContextSchema = z
  .object({
    goal: label,
    goalGuidance: sentence,
    durationMinutes: z.number().int().min(1).max(600),
    focusMuscleGroups: z.array(label).max(L.maxFocusMuscleGroups).default([]),
    availableEquipment: z.array(label).max(L.maxEquipment).default([]),
    notes: z.string().max(L.maxNotesLength).nullish(),
    candidateExercises: z.array(candidateExerciseSchema).max(L.maxCandidateExercises).default([]),
    loadEvidence: z.array(loadEvidenceSchema).max(L.maxLoadEvidenceExercises).default([]),
  })
  .strict();

/** Contexto de `ADAPT_WORKOUT` — espelha `AiWorkoutAdaptationContext`. */
export const adaptationContextSchema = z
  .object({
    templateName: displayName,
    template: workoutContextSchema,
    exerciseHistory: z.array(exerciseHistorySchema).max(L.maxExercisesInContext).default([]),
    personalRecords: z.array(personalRecordSchema).max(L.maxPersonalRecords).default([]),
    replacementCandidates: z
      .array(candidateExerciseSchema)
      .max(L.maxCandidateExercises)
      .default([]),
    allowedChangeTypes: z.array(label).max(16).default([]),
    evidence: evidenceSchema.default({
      sessionsAnalyzed: 0,
      exercisesWithHistory: 0,
      maxDataQuality: 'INSUFFICIENT',
    }),
  })
  .strict();

/** Contexto dos quatro `EXPLAIN_*` — espelha `AiCoachExplanationContext`. */
export const explanationContextSchema = z
  .object({
    origin: label,
    contextId: z.string().max(L.maxIdLength),
    subject: sentence,
    facts: z
      .array(z.object({ label: label, value: sentence }).strict())
      .max(L.maxExplanationFacts)
      .default([]),
    exerciseId: exerciseId.nullish(),
    exerciseName: displayName.nullish(),
    currentValue: label.nullish(),
    suggestedValue: label.nullish(),
    reason: sentence.nullish(),
    evidence: sentence.nullish(),
    exerciseHistory: exerciseHistorySchema.nullish(),
    workoutExercises: z.array(plannedExerciseSchema).max(L.maxExercisesInContext).default([]),
    dataQuality: dataQualityLevelSchema.nullish(),
    knownLimitations: z.array(sentence).max(L.maxKnownLimitations).default([]),
  })
  .strict();

/**
 * O envelope da requisição.
 *
 * `clientRequestId` é gerado pelo cliente e serve a três coisas ao mesmo tempo: correlacionar
 * log, deduplicar toque repetido e diagnosticar. O formato é deliberadamente opaco e curto — ele
 * aparece em log, então texto livre aqui seria vetor de injeção de log (mesma regra do
 * `X-Request-Id` da T16.0).
 */
export const clientRequestIdSchema = z
  .string()
  .regex(/^[A-Za-z0-9._-]{8,128}$/, 'clientRequestId deve ser opaco (8-128, [A-Za-z0-9._-])');

const envelope = {
  clientRequestId: clientRequestIdSchema,
  schemaVersion: z.number().int(),
};

/**
 * O corpo completo, discriminado por `requestType`.
 *
 * Um contrato só, com quatro formatos de contexto — e não cinco endpoints. O gateway do Android
 * já trabalha com request discriminado por tipo; espelhar isso mantém uma fronteira, um guard,
 * um lugar de quota e um lugar de log.
 */
export const aiCoachRequestSchema = z.discriminatedUnion('requestType', [
  z
    .object({
      ...envelope,
      requestType: z.literal('ANALYZE_WORKOUT'),
      context: analysisContextSchema,
    })
    .strict(),
  z
    .object({
      ...envelope,
      requestType: z.literal('GENERATE_WORKOUT'),
      context: generationContextSchema,
    })
    .strict(),
  z
    .object({
      ...envelope,
      requestType: z.literal('ADAPT_WORKOUT'),
      context: adaptationContextSchema,
    })
    .strict(),
  ...(
    ['EXPLAIN_RECOMMENDATION', 'EXPLAIN_WORKOUT', 'EXPLAIN_ADAPTATION', 'EXPLAIN_PROGRESS'] as const
  ).map((type) =>
    z
      .object({ ...envelope, requestType: z.literal(type), context: explanationContextSchema })
      .strict(),
  ),
]);

export type AiCoachRequestBody = z.infer<typeof aiCoachRequestSchema>;
export type AnalysisContext = z.infer<typeof analysisContextSchema>;
export type GenerationContext = z.infer<typeof generationContextSchema>;
export type AdaptationContext = z.infer<typeof adaptationContextSchema>;
export type ExplanationContext = z.infer<typeof explanationContextSchema>;

/** Só para o contrato não sair daqui incompleto se um tipo novo aparecer. */
const declaredTypes = new Set(AI_COACH_REQUEST_TYPES);
export function isDeclaredRequestType(value: unknown): boolean {
  return typeof value === 'string' && declaredTypes.has(value as never);
}
