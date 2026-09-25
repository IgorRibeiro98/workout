import { RESPONSE_LIMITS } from '../ai-coach.limits';
import type { AiCoachRequestBody } from '../ai-coach.request.schema';
import type { CoachEvalExpectation } from './coach-eval.dataset';

/**
 * O oráculo das invariantes críticas do benchmark (T19.H4 §38).
 *
 * Roda **só sobre respostas que o validador aceitou**, e é escrito de propósito sem reaproveitar
 * nada de `ai-coach.validator.ts` além dos tetos numéricos (que são a especificação, não a
 * lógica). A pergunta que ele responde não é "o modelo errou?" — errar é permitido, o validador
 * recusa —, e sim "algo inválido **passou** pelo validador?". Duas implementações independentes
 * da mesma regra discordando é exatamente o sinal que se quer ver.
 *
 * Qualquer violação aqui desclassifica o candidato no relatório, independentemente de latência ou
 * custo (§42): uma resposta aceita que inventa exercício chegaria ao usuário.
 */

export const CRITICAL_INVARIANTS = [
  'INVENTED_EXERCISE_ID',
  'REPLACEMENT_OUTSIDE_CANDIDATES',
  'SETS_OUT_OF_BOUNDS',
  'REPS_OUT_OF_BOUNDS',
  'REST_OUT_OF_BOUNDS',
  'LOAD_WITHOUT_EVIDENCE',
  'DATA_QUALITY_INFLATED',
] as const;

export type CriticalInvariant = (typeof CRITICAL_INVARIANTS)[number];

const R = RESPONSE_LIMITS;
const QUALITY_ORDER = ['INSUFFICIENT', 'LIMITED', 'GOOD'];

type Loose = Record<string, unknown>;

export function criticalViolations(
  request: AiCoachRequestBody,
  accepted: unknown,
): CriticalInvariant[] {
  const found = new Set<CriticalInvariant>();
  const output = asObject(accepted);

  switch (request.requestType) {
    case 'ANALYZE_WORKOUT': {
      const context = request.context;
      const known = new Set<string>([
        ...(context.currentWorkout?.exercises ?? []).map((exercise) => exercise.exerciseId),
        ...context.exerciseHistory.map((history) => history.exerciseId),
        ...context.personalRecords.map((record) => record.exerciseId),
      ]);
      for (const field of ['positiveSignals', 'attentionPoints', 'recommendations']) {
        for (const item of asArray(output[field])) {
          const id = idOf(asObject(item).exerciseId);
          if (id !== null && !known.has(id)) found.add('INVENTED_EXERCISE_ID');
        }
      }
      if (inflated(output.dataQuality, context.evidence.maxDataQuality)) {
        found.add('DATA_QUALITY_INFLATED');
      }
      break;
    }

    case 'GENERATE_WORKOUT': {
      const context = request.context;
      const candidates = new Set(context.candidateExercises.map((c) => c.exerciseId));
      const withLoad = new Set(
        context.loadEvidence
          .filter((evidence) => typeof evidence.lastWeightKg === 'number')
          .map((evidence) => evidence.exerciseId),
      );
      for (const item of asArray(output.exercises)) {
        const exercise = asObject(item);
        const id = idOf(exercise.exerciseId);
        if (id === null || !candidates.has(id)) found.add('INVENTED_EXERCISE_ID');
        if (!intWithin(exercise.sets, R.minSets, R.maxSets)) found.add('SETS_OUT_OF_BOUNDS');
        if (!repsWithin(exercise.minReps, exercise.maxReps)) found.add('REPS_OUT_OF_BOUNDS');
        if (!intWithin(exercise.restSeconds, R.minRestSeconds, R.maxRestSeconds)) {
          found.add('REST_OUT_OF_BOUNDS');
        }
        if (exercise.weightKg !== null && exercise.weightKg !== undefined) {
          if (id === null || !withLoad.has(id)) found.add('LOAD_WITHOUT_EVIDENCE');
        }
      }
      break;
    }

    case 'ADAPT_WORKOUT': {
      const context = request.context;
      const template = new Set(context.template.exercises.map((exercise) => exercise.exerciseId));
      const replacements = new Set(context.replacementCandidates.map((c) => c.exerciseId));
      const withHistory = new Set(
        context.exerciseHistory
          .filter((history) => history.executions.length > 0)
          .map((history) => history.exerciseId),
      );
      for (const item of asArray(output.changes)) {
        const change = asObject(item);
        const id = idOf(change.exerciseId);
        if (id === null || !template.has(id)) found.add('INVENTED_EXERCISE_ID');

        const replacement = idOf(change.replacementExerciseId);
        if (replacement !== null && !replacements.has(replacement)) {
          found.add('REPLACEMENT_OUTSIDE_CANDIDATES');
        }
        if (
          present(change.suggestedSets) &&
          !intWithin(change.suggestedSets, R.minSets, R.maxSets)
        ) {
          found.add('SETS_OUT_OF_BOUNDS');
        }
        if (
          (present(change.suggestedMinReps) || present(change.suggestedMaxReps)) &&
          !repsWithin(change.suggestedMinReps, change.suggestedMaxReps)
        ) {
          found.add('REPS_OUT_OF_BOUNDS');
        }
        if (
          present(change.suggestedRestSeconds) &&
          !intWithin(change.suggestedRestSeconds, R.minRestSeconds, R.maxRestSeconds)
        ) {
          found.add('REST_OUT_OF_BOUNDS');
        }
        // Carga nova sem nenhuma execução registrada daquele exercício é carga sem evidência —
        // o texto em `evidence` não substitui o dado que o contexto não tem.
        if (change.type === 'ADJUST_LOAD' && (id === null || !withHistory.has(id))) {
          found.add('LOAD_WITHOUT_EVIDENCE');
        }
      }
      if (inflated(output.dataQuality, context.evidence.maxDataQuality)) {
        found.add('DATA_QUALITY_INFLATED');
      }
      break;
    }

    default: {
      const context = request.context;
      const known = new Set<string>(
        [
          context.exerciseId ?? null,
          context.exerciseHistory?.exerciseId ?? null,
          ...context.workoutExercises.map((exercise) => exercise.exerciseId),
        ].filter((id): id is string => typeof id === 'string'),
      );
      for (const raw of asArray(output.referencedExerciseIds)) {
        const id = idOf(raw);
        if (id === null || !known.has(id)) found.add('INVENTED_EXERCISE_ID');
      }
      break;
    }
  }

  return CRITICAL_INVARIANTS.filter((invariant) => found.has(invariant));
}

/**
 * Aderência ao pedido — qualidade, não validade. `undefined` quando o cenário não declara
 * expectativa para este tipo.
 */
export function expectationMet(
  expect: CoachEvalExpectation | undefined,
  accepted: unknown,
): boolean | undefined {
  if (!expect) return undefined;
  const output = asObject(accepted);
  const checks: boolean[] = [];

  if (expect.insufficientCandidates !== undefined) {
    checks.push(output.insufficientCandidates === expect.insufficientCandidates);
  }
  const changes = asArray(output.changes).map(asObject);
  if (expect.changes === 'none') checks.push(changes.length === 0);
  if (expect.changes === 'some') checks.push(changes.length > 0);
  if (expect.changeTypesAnyOf) {
    const wanted = new Set(expect.changeTypesAnyOf);
    checks.push(
      changes.some((change) => typeof change.type === 'string' && wanted.has(change.type)),
    );
  }

  return checks.length === 0 ? undefined : checks.every(Boolean);
}

function inflated(raw: unknown, ceiling: string): boolean {
  const level = asObject(raw).level;
  const levelIndex = typeof level === 'string' ? QUALITY_ORDER.indexOf(level.trim()) : -1;
  // Sem nível reconhecível numa resposta aceita também é falha de oráculo: o validador exige um.
  if (levelIndex < 0) return true;
  return levelIndex > QUALITY_ORDER.indexOf(ceiling);
}

function intWithin(value: unknown, min: number, max: number): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

function repsWithin(min: unknown, max: unknown): boolean {
  return (
    intWithin(min, R.minReps, R.maxReps) &&
    intWithin(max, R.minReps, R.maxReps) &&
    (max as number) >= (min as number)
  );
}

function idOf(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function present(value: unknown): boolean {
  return value !== null && value !== undefined;
}

function asObject(value: unknown): Loose {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Loose)
    : {};
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}
