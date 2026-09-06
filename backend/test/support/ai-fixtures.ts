/**
 * Contextos e respostas de referência do Coach.
 *
 * Eles espelham o que o Android realmente monta (`AiCoachContextProjector`,
 * `ExerciseCandidateBuilder`) — com dois exercícios, um histórico curto e um candidato — porque
 * um fixture que não parece com o contexto real não prova nada sobre o contexto real.
 */

export const SUPINO = 'canonical:supino-reto-barra';
export const AGACHAMENTO = 'canonical:agachamento-livre';
export const REMADA_CANDIDATA = 'canonical:remada-curvada';
export const INVENTADO = 'canonical:exercicio-que-nao-existe';

export const analysisContext = () => ({
  athlete: { weeklyGoal: 4, completedSessionsInWindow: 8 },
  currentWorkout: {
    templateName: 'Treino A',
    exercises: [
      {
        exerciseId: SUPINO,
        name: 'Supino reto com barra',
        targetSets: 4,
        minReps: 8,
        maxReps: 12,
        plannedWeightKg: 60,
        restSeconds: 90,
      },
    ],
  },
  exerciseHistory: [
    {
      exerciseId: SUPINO,
      name: 'Supino reto com barra',
      sessionsAnalyzed: 3,
      executions: [
        { finishedAtEpochMs: 1_700_000_000_000, completedSets: 4, maxWeightKg: 60, totalReps: 38 },
      ],
    },
  ],
  personalRecords: [],
  evidence: { sessionsAnalyzed: 3, exercisesWithHistory: 1, maxDataQuality: 'GOOD' as const },
});

export const analysisOutput = () => ({
  summary: 'A carga do supino está estável nas últimas três sessões registradas.',
  positiveSignals: [
    {
      exerciseId: SUPINO,
      title: 'Constância',
      description: 'Quatro séries concluídas em todas as sessões.',
    },
  ],
  attentionPoints: [],
  recommendations: [
    {
      type: 'REVIEW_LOAD',
      exerciseId: SUPINO,
      reason: 'A carga não mudou nas execuções registradas.',
      confidence: 0.6,
      evidence: '60 kg nas 3 execuções concluídas.',
    },
  ],
  dataQuality: { level: 'GOOD', description: 'Três sessões concluídas.' },
});

export const generationContext = () => ({
  goal: 'HIPERTROFIA',
  goalGuidance: 'Priorize volume moderado com descanso curto.',
  durationMinutes: 60,
  focusMuscleGroups: ['PEITO'],
  availableEquipment: [],
  notes: null as string | null,
  candidateExercises: [
    { exerciseId: SUPINO, name: 'Supino reto com barra', muscleGroup: 'PEITO', equipment: 'BARRA' },
    {
      exerciseId: REMADA_CANDIDATA,
      name: 'Remada curvada',
      muscleGroup: 'COSTAS',
      equipment: 'BARRA',
    },
  ],
  loadEvidence: [{ exerciseId: SUPINO, lastWeightKg: 60, lastReps: 10, sessionsWithHistory: 3 }],
});

export const generationOutput = () => ({
  name: 'Peito e costas',
  exercises: [
    {
      exerciseId: SUPINO,
      order: 1,
      sets: 4,
      minReps: 8,
      maxReps: 12,
      restSeconds: 90,
      weightKg: 60,
      reason: 'Base do treino de peito.',
    },
  ],
  explanation: 'Um exercício composto para o foco pedido.',
  insufficientCandidates: false,
});

export const adaptationContext = () => ({
  templateName: 'Treino A',
  template: {
    templateName: 'Treino A',
    exercises: [
      {
        exerciseId: SUPINO,
        name: 'Supino reto com barra',
        targetSets: 4,
        minReps: 8,
        maxReps: 12,
        plannedWeightKg: 60,
        restSeconds: 90,
      },
    ],
  },
  exerciseHistory: [
    {
      exerciseId: SUPINO,
      name: 'Supino reto com barra',
      sessionsAnalyzed: 3,
      executions: [
        { finishedAtEpochMs: 1_700_000_000_000, completedSets: 4, maxWeightKg: 60, totalReps: 40 },
      ],
    },
  ],
  personalRecords: [],
  replacementCandidates: [
    {
      exerciseId: REMADA_CANDIDATA,
      name: 'Remada curvada',
      muscleGroup: 'COSTAS',
      equipment: 'BARRA',
    },
  ],
  allowedChangeTypes: ['ADJUST_LOAD', 'ADJUST_REST', 'REPLACE_EXERCISE'],
  evidence: { sessionsAnalyzed: 3, exercisesWithHistory: 1, maxDataQuality: 'GOOD' as const },
});

export const adaptationOutput = () => ({
  summary: 'Dá para subir a carga do supino.',
  changes: [
    {
      type: 'ADJUST_LOAD',
      exerciseId: SUPINO,
      currentWeightKg: 60,
      suggestedWeightKg: 62.5,
      reason: 'Todas as séries foram concluídas na faixa alta.',
      evidence: '4 séries concluídas com 60 kg nas últimas execuções.',
      confidence: 0.7,
    },
  ],
  dataQuality: { level: 'GOOD', description: 'Três sessões concluídas.' },
});

export const explanationContext = () => ({
  origin: 'WORKOUT_ADAPTATION',
  contextId: 'ADJUST_LOAD:' + SUPINO,
  subject: 'Aumentar a carga do supino de 60 kg para 62,5 kg',
  facts: [{ label: 'Execuções concluídas', value: '3' }],
  exerciseId: SUPINO,
  exerciseName: 'Supino reto com barra',
  currentValue: '60 kg',
  suggestedValue: '62,5 kg',
  reason: 'Todas as séries foram concluídas.',
  evidence: '4 séries com 60 kg.',
  exerciseHistory: null,
  workoutExercises: [],
  dataQuality: 'GOOD' as const,
  knownLimitations: ['O app não registra RPE.'],
});

export const explanationOutput = () => ({
  title: 'Por que subir a carga',
  explanation: 'As três execuções registradas fecharam as quatro séries com 60 kg.',
  limitations: ['O app não registra RPE.'],
  referencedExerciseIds: [SUPINO],
});

/** O corpo HTTP completo, no formato que o Android envia. */
export function requestBody(
  requestType: string,
  context: unknown,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    clientRequestId: 'cli-11111111-2222-3333',
    requestType,
    schemaVersion: 1,
    context,
    ...overrides,
  };
}
