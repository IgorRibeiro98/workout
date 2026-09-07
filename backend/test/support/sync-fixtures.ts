import { randomUUID } from 'node:crypto';

/**
 * Corpos de push do sync incremental (T16.6), montados como o Android os monta.
 *
 * Os payloads seguem os DTOs de `com.example.data.sync.dto` — os mesmos que o registry de backup
 * valida no servidor. Não existe um segundo formato de treino em teste: se o contrato mudar, estes
 * helpers quebram junto com a produção, que é o ponto.
 */

export interface MutationInput {
  clientMutationId?: string;
  entityType: string;
  entitySyncId: string;
  entitySchemaVersion?: number;
  operation?: string;
  baseRevision?: number | null;
  payload?: unknown;
}

export function pushBody(mutations: MutationInput[], deviceId = 'device-a'): string {
  return JSON.stringify({
    deviceId,
    mutations: mutations.map((mutation) => ({
      clientMutationId: mutation.clientMutationId ?? randomUUID(),
      entityType: mutation.entityType,
      entitySyncId: mutation.entitySyncId,
      entitySchemaVersion: mutation.entitySchemaVersion ?? 1,
      operation: mutation.operation ?? 'UPSERT',
      baseRevision: mutation.baseRevision ?? null,
      ...(mutation.payload === undefined ? {} : { payload: mutation.payload }),
    })),
  });
}

export function uuid(): string {
  return randomUUID();
}

export function programPayload(syncId: string, name = 'Programa A'): Record<string, unknown> {
  return {
    syncId,
    name,
    description: null,
    isCurrent: false,
    externalId: null,
    contentVersion: 0,
  };
}

export function templatePayload(
  syncId: string,
  name = 'Treino A',
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    syncId,
    programSyncId: null,
    name,
    shortIdentifier: null,
    orderInProgram: 0,
    dayOfWeek: null,
    exercises: [
      {
        position: 0,
        exercise: { kind: 'CANONICAL', id: 'supino-reto-barra' },
        targetSets: 3,
        minReps: 8,
        maxReps: 12,
        restDurationSeconds: 90,
        plannedWeight: 60.0,
        machineLabel: null,
        notes: null,
      },
    ],
    ...overrides,
  };
}

export function sessionPayload(
  syncId: string,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    syncId,
    templateSyncId: null,
    templateNameSnapshot: 'Treino A',
    status: 'COMPLETED',
    startedAt: 1_700_000_000_000,
    finishedAt: 1_700_000_600_000,
    notes: null,
    exercises: [
      {
        plannedOrder: 0,
        executionOrder: 0,
        exerciseNameSnapshot: 'Supino reto',
        plannedExercise: { kind: 'CANONICAL', id: 'supino-reto-barra' },
        actualExercise: { kind: 'CANONICAL', id: 'supino-reto-barra' },
        machineLabelSnapshot: null,
        primaryMuscleSnapshot: null,
        restDurationSecondsSnapshot: 90,
        startedAt: 1_700_000_000_000,
        finishedAt: 1_700_000_300_000,
        notes: null,
        replacementReason: null,
        sets: [
          {
            setNumber: 1,
            type: 'WORK',
            weight: 60.0,
            repetitions: 10,
            completed: true,
            startedAt: null,
            finishedAt: null,
            rpe: null,
            rir: null,
            durationSeconds: null,
          },
        ],
      },
    ],
    ...overrides,
  };
}

export function measurementPayload(
  syncId: string,
  weightKg: number | null = 80.5,
): Record<string, unknown> {
  return {
    syncId,
    date: 1_700_000_000_000,
    createdAt: 1_700_000_000_000,
    weightKg,
    heightCm: null,
    bodyFatPercentage: null,
    waistCm: null,
    abdomenCm: null,
    chestCm: null,
    leftArmCm: null,
    rightArmCm: null,
    leftThighCm: null,
    rightThighCm: null,
    calfCm: null,
    hipCm: null,
  };
}

export function customExercisePayload(syncId: string, name = 'Meu exercício') {
  return {
    syncId,
    name,
    primaryMuscle: null,
    equipment: null,
    description: null,
    isBodyweight: false,
    rirEnabled: false,
    active: true,
  };
}
