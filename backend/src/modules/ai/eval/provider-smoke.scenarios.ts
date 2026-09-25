import { loadCoachEvalDataset, type CoachEvalScenario } from './coach-eval.dataset';

/**
 * Os cenários do `ai:provider-smoke` (T19.H4 §71): pequenos, sintéticos e fixos — um por operação.
 *
 * O default é `ANALYZE_WORKOUT` de propósito: o schema de análise é o que exercita **todas** as
 * construções de schema que o Coach usa — `maxItems`, campos anuláveis, `enum`, `minimum`/`maximum`,
 * objetos dentro de arrays. Um provider que recusa uma delas recusa o schema inteiro com `400`,
 * antes de chegar ao modelo (foi assim com o `maxItems` do ADAPT na Gemini API); o smoke de uma
 * chamada só precisa pegar isso. `all` roda as quatro operações (o smoke completo de uma troca de
 * provider).
 *
 * Sem dado de usuário: ids reais do catálogo público do app, números inventados, timestamps
 * relativos a uma âncora sintética. Passam pelo mesmo contrato do endpoint (`loadCoachEvalDataset`).
 */

const T0 = 1_780_000_000_000;
const DAY = 86_400_000;

const execution = (
  daysAgo: number,
  completedSets: number,
  maxWeightKg: number,
  totalReps: number,
) => ({
  finishedAtEpochMs: T0 - daysAgo * DAY,
  completedSets,
  maxWeightKg,
  totalReps,
});

const RAW = {
  version: 1,
  scenarios: [
    {
      id: 'smoke-analyze',
      requestType: 'ANALYZE_WORKOUT',
      tags: ['smoke'],
      context: {
        athlete: { weeklyGoal: 4, completedSessionsInWindow: 3 },
        currentWorkout: {
          templateName: 'Treino A',
          exercises: [
            {
              exerciseId: 'supino-reto-barra',
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
            exerciseId: 'supino-reto-barra',
            name: 'Supino reto com barra',
            sessionsAnalyzed: 3,
            executions: [
              execution(2, 4, 60, 48),
              execution(5, 4, 60, 47),
              execution(9, 4, 57.5, 48),
            ],
          },
        ],
        personalRecords: [],
        evidence: { sessionsAnalyzed: 3, exercisesWithHistory: 1, maxDataQuality: 'GOOD' },
      },
    },
    {
      id: 'smoke-generate',
      requestType: 'GENERATE_WORKOUT',
      tags: ['smoke'],
      context: {
        goal: 'HYPERTROPHY',
        goalGuidance:
          'volume moderado a alto, faixas de repetição médias e descansos intermediários',
        durationMinutes: 30,
        focusMuscleGroups: ['Peitoral'],
        availableEquipment: [],
        notes: null,
        candidateExercises: [
          {
            exerciseId: 'supino-reto-barra',
            name: 'Supino reto com barra',
            muscleGroup: 'Peitoral',
            equipment: 'Barra',
          },
          {
            exerciseId: 'crucifixo-halteres',
            name: 'Crucifixo com halteres',
            muscleGroup: 'Peitoral',
            equipment: 'Halteres',
          },
        ],
        loadEvidence: [
          {
            exerciseId: 'supino-reto-barra',
            lastWeightKg: 60,
            lastReps: 10,
            sessionsWithHistory: 3,
          },
        ],
      },
    },
    {
      id: 'smoke-adapt',
      requestType: 'ADAPT_WORKOUT',
      tags: ['smoke'],
      context: {
        templateName: 'Treino A',
        template: {
          templateName: 'Treino A',
          exercises: [
            {
              exerciseId: 'supino-reto-barra',
              name: 'Supino reto com barra',
              targetSets: 4,
              minReps: 8,
              maxReps: 12,
              plannedWeightKg: 60,
              restSeconds: 90,
            },
            {
              exerciseId: 'triceps-corda',
              name: 'Tríceps corda',
              targetSets: 3,
              minReps: 10,
              maxReps: 12,
              plannedWeightKg: 25,
              restSeconds: 60,
            },
          ],
        },
        exerciseHistory: [
          {
            exerciseId: 'supino-reto-barra',
            name: 'Supino reto com barra',
            sessionsAnalyzed: 3,
            executions: [execution(2, 4, 60, 48), execution(5, 4, 60, 48), execution(9, 4, 60, 48)],
          },
          {
            exerciseId: 'triceps-corda',
            name: 'Tríceps corda',
            sessionsAnalyzed: 3,
            executions: [execution(2, 3, 25, 33), execution(5, 3, 25, 32), execution(9, 3, 25, 33)],
          },
        ],
        personalRecords: [],
        replacementCandidates: [
          {
            exerciseId: 'supino-reto-halteres',
            name: 'Supino reto com halteres',
            muscleGroup: 'Peitoral',
            equipment: 'Halteres',
          },
        ],
        allowedChangeTypes: [
          'ADJUST_LOAD',
          'ADJUST_REPS',
          'ADJUST_SETS',
          'ADJUST_REST',
          'REPLACE_EXERCISE',
        ],
        evidence: { sessionsAnalyzed: 3, exercisesWithHistory: 2, maxDataQuality: 'GOOD' },
      },
    },
    {
      id: 'smoke-explain',
      requestType: 'EXPLAIN_PROGRESS',
      tags: ['smoke'],
      context: {
        origin: 'PROFILE_PROGRESS',
        contextId: 'profile-progress',
        subject: 'O que os números de progressão do Perfil querem dizer',
        facts: [
          { label: 'Nível atual', value: '4' },
          { label: 'Sequência semanal', value: '2 semana(s)' },
          { label: 'Treinos concluídos no total', value: '18' },
        ],
        knownLimitations: [
          'Estes números são calculados pelo Spark; o Coach apenas os lê e explica, sem recalcular nível, XP, sequência ou conquistas.',
        ],
      },
    },
  ],
};

export const PROVIDER_SMOKE_TYPES = ['analyze', 'generate', 'adapt', 'explain'] as const;

export type ProviderSmokeType = (typeof PROVIDER_SMOKE_TYPES)[number];

export function providerSmokeScenario(type: ProviderSmokeType): CoachEvalScenario {
  const scenarios = loadCoachEvalDataset(RAW);
  const scenario = scenarios.find((candidate) => candidate.id === `smoke-${type}`);
  if (!scenario) throw new Error(`cenário de smoke desconhecido: ${type}`);
  return scenario;
}

/** As quatro operações, na ordem do enunciado — o smoke completo de uma troca de provider. */
export function allProviderSmokeScenarios(): CoachEvalScenario[] {
  return PROVIDER_SMOKE_TYPES.map((type) => providerSmokeScenario(type));
}
