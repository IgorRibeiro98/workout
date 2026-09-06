/**
 * Os tetos do Coach IA no servidor.
 *
 * Duas famílias, com origens diferentes:
 *
 * - **entrada** (`CONTEXT_LIMITS`): espelham os tetos que o `AiModelConfig` do Android já aplica
 *   ao montar contexto. Aqui eles existem de novo porque o backend não pode supor que só o APK
 *   oficial faz requisições — um cliente qualquer poderia mandar mil exercícios (§46);
 * - **saída** (`RESPONSE_LIMITS`): espelham `AiCoachResponseValidator` do Android. A resposta do
 *   modelo é validada nos dois lados de propósito, e os números precisam ser os mesmos para que
 *   uma resposta aceita aqui não seja recusada lá por um teto diferente.
 *
 * Mudar um número aqui sem mudar o correspondente no Android cria divergência silenciosa: os dois
 * arquivos são um contrato só, escrito em duas linguagens.
 */

/** Tetos do contexto que chega do Android. */
export const CONTEXT_LIMITS = {
  /** `AiModelConfig.MAX_EXERCISES_IN_CONTEXT` */
  maxExercisesInContext: 12,
  /** `AiModelConfig.HISTORY_PER_EXERCISE_LIMIT` */
  maxExecutionsPerExercise: 6,
  /** `AiModelConfig.PERSONAL_RECORDS_LIMIT` */
  maxPersonalRecords: 10,
  /** `AiModelConfig.MAX_CANDIDATE_EXERCISES` */
  maxCandidateExercises: 40,
  /** `AiModelConfig.MAX_FOCUS_MUSCLE_GROUPS` */
  maxFocusMuscleGroups: 4,
  /** `AiModelConfig.MAX_LOAD_EVIDENCE_EXERCISES` */
  maxLoadEvidenceExercises: 12,
  /** Equipamentos disponíveis: a lista do app é pequena e fechada. */
  maxEquipment: 16,
  /** `AiCoachExplanationContextBuilder`: os fatos que sustentam uma explicação. */
  maxExplanationFacts: 12,
  /** `AiCoachResponseValidator.MAX_LIMITATIONS` */
  maxKnownLimitations: 4,

  /** Identificador de exercício: `canonicalId` do catálogo ou `local:<rowId>`. */
  maxIdLength: 120,
  /** Nome legível de exercício ou treino. */
  maxNameLength: 160,
  /** `WorkoutGenerationPreferences.MAX_NOTES_LENGTH` — o único texto livre do usuário. */
  maxNotesLength: 280,
  /** Rótulos curtos: grupo muscular, equipamento, tipo de PR, objetivo. */
  maxLabelLength: 120,
  /** Frases montadas pelo app: `subject`, `reason`, `evidence`, `facts`, limitações. */
  maxSentenceLength: 400,
} as const;

/**
 * Tetos da resposta do modelo — os mesmos de `AiCoachResponseValidator` no Android.
 */
export const RESPONSE_LIMITS = {
  maxSummaryLength: 800,
  maxReasonLength: 400,
  maxEvidenceLength: 240,
  maxTitleLength: 80,
  maxDescriptionLength: 400,
  maxRecommendations: 5,
  maxObservations: 5,

  maxWorkoutNameLength: 60,
  maxExplanationLength: 600,
  maxExerciseReasonLength: 240,
  minGeneratedExercises: 1,
  maxGeneratedExercises: 12,

  maxAdaptationChanges: 12,

  maxExplanationTextLength: 900,
  maxLimitations: 4,

  minSets: 1,
  maxSets: 10,
  minReps: 1,
  maxReps: 100,
  minRestSeconds: 0,
  maxRestSeconds: 600,
  maxWeightKg: 500,
  /** Tolerância ao comparar carga: `Float` no Android não fecha em igualdade exata. */
  weightToleranceKg: 0.001,
} as const;

/**
 * Teto do corpo HTTP.
 *
 * Um contexto do Coach com todos os tetos acima preenchidos não passa de dezenas de kilobytes;
 * 128 KB deixa folga larga e ainda recusa um payload absurdo antes de ele virar trabalho.
 */
export const MAX_AI_REQUEST_BODY_BYTES = 128 * 1024;
