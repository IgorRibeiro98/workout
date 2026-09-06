import { RESPONSE_LIMITS } from './ai-coach.limits';
import {
  ADAPTATION_TYPE_NAMES,
  DATA_QUALITY_LEVEL_NAMES,
  RECOMMENDATION_TYPE_NAMES,
  type RawAdaptationOutput,
  type RawAnalysisOutput,
  type RawExplanationOutput,
  type RawGenerationOutput,
} from './ai-coach.output.schema';
import type {
  AdaptationContext,
  AnalysisContext,
  ExplanationContext,
  GenerationContext,
} from './ai-coach.request.schema';

/**
 * Structured output é entrada não confiável — também aqui.
 *
 * Este validador é o **gêmeo** do `AiCoachResponseValidator` do Android, com a mesma política:
 * uma violação invalida a resposta inteira; nada é adivinhado, nada é corrigido por aproximação e
 * nenhum `exerciseId` desconhecido é resolvido por nome. Não existe *fuzzy matching*.
 *
 * Ele não substitui o do Android (§16, §45): o backend valida o que consegue provar com o
 * contexto que recebeu, e o Android valida de novo contra o domínio real — que pode ter mudado
 * enquanto o modelo pensava. Defense in depth é a decisão, não redundância acidental.
 *
 * A saída, quando válida, é a resposta **crua** do modelo: o backend não a transforma em domínio.
 * Quem faz isso é o Android, com as autoridades dele.
 */

const R = RESPONSE_LIMITS;

export type AiCoachValidation = { ok: true } | { ok: false; reason: string };

const VALID = { ok: true } as const;

function invalid(reason: string): AiCoachValidation {
  return { ok: false, reason };
}

const RECOMMENDATION_TYPES = new Set(RECOMMENDATION_TYPE_NAMES);
const ADAPTATION_TYPES = new Set(ADAPTATION_TYPE_NAMES);
const DATA_QUALITY_ORDER = new Map(DATA_QUALITY_LEVEL_NAMES.map((name, index) => [name, index]));

/** Os tipos que só fazem sentido apontando para um exercício. Mesma regra do Android. */
const REQUIRES_EXERCISE = new Set(['REVIEW_LOAD', 'REVIEW_REPS', 'REVIEW_EXERCISE']);

/** Os tipos de adaptação cujos campos numéricos pertencem a cada um. */
const ADAPTATION_FIELD_OWNER = {
  load: 'ADJUST_LOAD',
  sets: 'ADJUST_SETS',
  reps: 'ADJUST_REPS',
  rest: 'ADJUST_REST',
  replacement: 'REPLACE_EXERCISE',
} as const;

// ---------------------------------------------------------------------------------------------
// ANALYZE_WORKOUT
// ---------------------------------------------------------------------------------------------

/** Todos os `exerciseId` que a análise tem permissão de citar. */
export function knownAnalysisExerciseIds(context: AnalysisContext): Set<string> {
  const ids = new Set<string>();
  context.currentWorkout?.exercises.forEach((exercise) => ids.add(exercise.exerciseId));
  context.exerciseHistory.forEach((history) => ids.add(history.exerciseId));
  context.personalRecords.forEach((record) => ids.add(record.exerciseId));
  return ids;
}

export function validateAnalysis(
  context: AnalysisContext,
  response: RawAnalysisOutput,
): AiCoachValidation {
  const summary = response.summary.trim();
  if (summary.length === 0) return invalid('summary vazio');
  if (summary.length > R.maxSummaryLength) {
    return invalid(`summary excede ${R.maxSummaryLength} caracteres`);
  }
  if (response.recommendations.length > R.maxRecommendations) {
    return invalid(`mais de ${R.maxRecommendations} recomendações`);
  }

  const known = knownAnalysisExerciseIds(context);

  for (const [field, observations] of [
    ['positiveSignals', response.positiveSignals],
    ['attentionPoints', response.attentionPoints],
  ] as const) {
    const result = validateObservations(field, observations, known);
    if (!result.ok) return result;
  }

  for (const [index, raw] of response.recommendations.entries()) {
    const type = raw.type.trim();
    if (!RECOMMENDATION_TYPES.has(type)) {
      return invalid(`tipo desconhecido em [${index}]: '${raw.type}'`);
    }

    const reason = raw.reason.trim();
    if (reason.length === 0) return invalid(`reason vazio em [${index}]`);
    if (reason.length > R.maxReasonLength) {
      return invalid(`reason excede ${R.maxReasonLength} caracteres em [${index}]`);
    }

    if (!Number.isFinite(raw.confidence)) return invalid(`confidence não numérico em [${index}]`);
    if (raw.confidence < 0 || raw.confidence > 1) {
      return invalid(`confidence fora de 0..1 em [${index}]: ${raw.confidence}`);
    }

    const exerciseId = trimmedOrNull(raw.exerciseId);
    if (exerciseId !== null && !known.has(exerciseId)) {
      return invalid(`exerciseId fora do contexto em [${index}]: '${exerciseId}'`);
    }
    if (exerciseId === null && REQUIRES_EXERCISE.has(type)) {
      return invalid(`${type} exige exerciseId em [${index}]`);
    }

    const evidence = trimmedOrNull(raw.evidence);
    if (exerciseId !== null && evidence === null) {
      return invalid(`recomendação sobre exercício sem evidence em [${index}]`);
    }
    if (evidence !== null && evidence.length > R.maxEvidenceLength) {
      return invalid(`evidence excede ${R.maxEvidenceLength} caracteres em [${index}]`);
    }
  }

  return validateDataQuality(context.evidence.maxDataQuality, response.dataQuality);
}

function validateObservations(
  field: string,
  observations: RawAnalysisOutput['positiveSignals'],
  known: Set<string>,
): AiCoachValidation {
  if (observations.length > R.maxObservations) {
    return invalid(`mais de ${R.maxObservations} itens em ${field}`);
  }
  for (const [index, item] of observations.entries()) {
    const title = item.title.trim();
    if (title.length === 0) return invalid(`title vazio em ${field}[${index}]`);
    if (title.length > R.maxTitleLength) {
      return invalid(`title excede ${R.maxTitleLength} caracteres em ${field}[${index}]`);
    }
    const description = item.description.trim();
    if (description.length === 0) return invalid(`description vazia em ${field}[${index}]`);
    if (description.length > R.maxDescriptionLength) {
      return invalid(
        `description excede ${R.maxDescriptionLength} caracteres em ${field}[${index}]`,
      );
    }
    const exerciseId = trimmedOrNull(item.exerciseId);
    if (exerciseId !== null && !known.has(exerciseId)) {
      return invalid(`exerciseId fora do contexto em ${field}[${index}]: '${exerciseId}'`);
    }
  }
  return VALID;
}

// ---------------------------------------------------------------------------------------------
// GENERATE_WORKOUT
// ---------------------------------------------------------------------------------------------

export function validateGeneration(
  context: GenerationContext,
  response: RawGenerationOutput,
): AiCoachValidation {
  if (response.insufficientCandidates) {
    // "Os candidatos não sustentam o pedido" é resposta legítima — desde que venha sem treino.
    return response.exercises.length === 0
      ? VALID
      : invalid(`insufficientCandidates com ${response.exercises.length} exercícios propostos`);
  }

  const name = response.name.trim();
  if (name.length === 0) return invalid('name vazio');
  if (name.length > R.maxWorkoutNameLength) {
    return invalid(`name excede ${R.maxWorkoutNameLength} caracteres`);
  }
  if (response.explanation.trim().length > R.maxExplanationLength) {
    return invalid(`explanation excede ${R.maxExplanationLength} caracteres`);
  }

  const exercises = response.exercises;
  if (exercises.length < R.minGeneratedExercises) return invalid('treino sem exercícios');
  if (exercises.length > R.maxGeneratedExercises) {
    return invalid(`mais de ${R.maxGeneratedExercises} exercícios`);
  }

  const candidates = new Set(context.candidateExercises.map((candidate) => candidate.exerciseId));
  const withLoadEvidence = new Set(
    context.loadEvidence
      .filter((evidence) => evidence.lastWeightKg !== null && evidence.lastWeightKg !== undefined)
      .map((evidence) => evidence.exerciseId),
  );
  const seenIds = new Set<string>();
  const seenOrders = new Set<number>();

  for (const [index, raw] of exercises.entries()) {
    const exerciseId = raw.exerciseId.trim();
    if (exerciseId.length === 0) return invalid(`exerciseId vazio em [${index}]`);
    // Id que o app não ofereceu é invenção — inclusive um id real do catálogo que não entrou
    // nos candidatos desta requisição.
    if (!candidates.has(exerciseId)) {
      return invalid(`exerciseId fora dos candidatos em [${index}]: '${exerciseId}'`);
    }
    if (seenIds.has(exerciseId)) {
      return invalid(`exercício repetido em [${index}]: '${exerciseId}'`);
    }
    seenIds.add(exerciseId);

    if (!Number.isInteger(raw.order) || raw.order < 1 || raw.order > exercises.length) {
      return invalid(`order fora de 1..${exercises.length} em [${index}]: ${raw.order}`);
    }
    if (seenOrders.has(raw.order)) return invalid(`order repetida em [${index}]: ${raw.order}`);
    seenOrders.add(raw.order);

    const range = validateIntRange(raw.sets, R.minSets, R.maxSets, 'sets', index);
    if (!range.ok) return range;
    const minReps = validateIntRange(raw.minReps, R.minReps, R.maxReps, 'minReps', index);
    if (!minReps.ok) return minReps;
    if (!Number.isInteger(raw.maxReps) || raw.maxReps < raw.minReps || raw.maxReps > R.maxReps) {
      return invalid(`maxReps inválido em [${index}]: ${raw.maxReps}`);
    }
    const rest = validateIntRange(
      raw.restSeconds,
      R.minRestSeconds,
      R.maxRestSeconds,
      'restSeconds',
      index,
    );
    if (!rest.ok) return rest;

    const weight = raw.weightKg;
    if (weight !== null && weight !== undefined) {
      if (!Number.isFinite(weight)) return invalid(`weightKg não numérico em [${index}]`);
      if (weight <= 0 || weight > R.maxWeightKg) {
        return invalid(`weightKg fora de 0..${R.maxWeightKg} em [${index}]: ${weight}`);
      }
      // Sem carga registrada no contexto, propor um número é invenção.
      if (!withLoadEvidence.has(exerciseId)) {
        return invalid(`weightKg sem carga registrada em [${index}]: '${exerciseId}'`);
      }
    }

    if (raw.reason.trim().length > R.maxExerciseReasonLength) {
      return invalid(`reason excede ${R.maxExerciseReasonLength} caracteres em [${index}]`);
    }
  }

  return VALID;
}

// ---------------------------------------------------------------------------------------------
// ADAPT_WORKOUT
// ---------------------------------------------------------------------------------------------

export function validateAdaptation(
  context: AdaptationContext,
  response: RawAdaptationOutput,
): AiCoachValidation {
  const summary = response.summary.trim();
  if (summary.length === 0) return invalid('summary vazio');
  if (summary.length > R.maxSummaryLength) {
    return invalid(`summary excede ${R.maxSummaryLength} caracteres`);
  }
  if (response.changes.length > R.maxAdaptationChanges) {
    return invalid(`mais de ${R.maxAdaptationChanges} mudanças`);
  }

  const quality = validateDataQuality(context.evidence.maxDataQuality, response.dataQuality);
  if (!quality.ok) return quality;

  const allowedTypes = new Set(context.allowedChangeTypes);
  const plannedById = new Map(
    context.template.exercises.map((exercise) => [exercise.exerciseId, exercise]),
  );
  const replacementsById = new Map(
    context.replacementCandidates.map((candidate) => [candidate.exerciseId, candidate]),
  );
  const seenChanges = new Set<string>();
  const seenReplacements = new Set<string>();

  for (const [index, raw] of response.changes.entries()) {
    const type = raw.type.trim();
    if (!ADAPTATION_TYPES.has(type)) {
      return invalid(`tipo desconhecido em [${index}]: '${raw.type}'`);
    }
    if (!allowedTypes.has(type)) {
      return invalid(`tipo não autorizado nesta adaptação em [${index}]: ${type}`);
    }

    const exerciseId = raw.exerciseId.trim();
    if (exerciseId.length === 0) return invalid(`exerciseId vazio em [${index}]`);
    const planned = plannedById.get(exerciseId);
    if (!planned) {
      return invalid(`exerciseId fora do treino em [${index}]: '${exerciseId}'`);
    }

    const changeId = `${type}:${exerciseId}`;
    if (seenChanges.has(changeId)) return invalid(`mudança repetida em [${index}]: ${changeId}`);
    seenChanges.add(changeId);

    const extraneous = extraneousField(type, raw);
    if (extraneous) {
      return invalid(`campo '${extraneous}' não pertence a ${type} em [${index}]`);
    }

    const reason = raw.reason.trim();
    if (reason.length === 0) return invalid(`reason vazio em [${index}]`);
    if (reason.length > R.maxReasonLength) {
      return invalid(`reason excede ${R.maxReasonLength} caracteres em [${index}]`);
    }
    // Evidência é obrigatória em toda mudança: alteração de treino sem o dado que a sustenta é
    // opinião, e opinião não altera plano.
    const evidence = raw.evidence.trim();
    if (evidence.length === 0) return invalid(`evidence vazia em [${index}]`);
    if (evidence.length > R.maxEvidenceLength) {
      return invalid(`evidence excede ${R.maxEvidenceLength} caracteres em [${index}]`);
    }
    if (!Number.isFinite(raw.confidence)) return invalid(`confidence não numérico em [${index}]`);
    if (raw.confidence < 0 || raw.confidence > 1) {
      return invalid(`confidence fora de 0..1 em [${index}]: ${raw.confidence}`);
    }

    const values = validateChangeValues(type, index, planned, raw, {
      replacementsById,
      templateIds: new Set(plannedById.keys()),
      seenReplacements,
    });
    if (!values.ok) return values;
  }

  return VALID;
}

type PlannedExercise = AdaptationContext['template']['exercises'][number];
type RawChange = RawAdaptationOutput['changes'][number];

/** O primeiro campo preenchido que não pertence ao tipo declarado, ou `null`. */
function extraneousField(type: string, raw: RawChange): string | null {
  const present = {
    load: isPresent(raw.currentWeightKg) || isPresent(raw.suggestedWeightKg),
    sets: isPresent(raw.currentSets) || isPresent(raw.suggestedSets),
    reps:
      isPresent(raw.currentMinReps) ||
      isPresent(raw.currentMaxReps) ||
      isPresent(raw.suggestedMinReps) ||
      isPresent(raw.suggestedMaxReps),
    rest: isPresent(raw.currentRestSeconds) || isPresent(raw.suggestedRestSeconds),
    replacement: isPresent(raw.replacementExerciseId),
  };
  const labels = {
    load: 'weightKg',
    sets: 'sets',
    reps: 'reps',
    rest: 'restSeconds',
    replacement: 'replacementExerciseId',
  } as const;

  for (const key of ['load', 'sets', 'reps', 'rest', 'replacement'] as const) {
    if (present[key] && ADAPTATION_FIELD_OWNER[key] !== type) {
      return labels[key];
    }
  }
  return null;
}

function validateChangeValues(
  type: string,
  index: number,
  planned: PlannedExercise,
  raw: RawChange,
  scope: {
    replacementsById: Map<string, { exerciseId: string; name: string }>;
    templateIds: Set<string>;
    seenReplacements: Set<string>;
  },
): AiCoachValidation {
  switch (type) {
    case 'ADJUST_LOAD': {
      const declared = raw.currentWeightKg ?? null;
      if (declared !== null && !Number.isFinite(declared)) {
        return invalid(`currentWeightKg não numérico em [${index}]`);
      }
      const current = planned.plannedWeightKg ?? null;
      if (!sameWeight(declared, current)) {
        return invalid(
          `currentWeightKg não corresponde ao treino em [${index}]: modelo=${declared ?? 'null'}, treino=${current ?? 'null'}`,
        );
      }
      const suggested = raw.suggestedWeightKg;
      if (suggested === null || suggested === undefined) {
        return invalid(`suggestedWeightKg ausente em [${index}]`);
      }
      if (!Number.isFinite(suggested))
        return invalid(`suggestedWeightKg não numérico em [${index}]`);
      if (suggested <= 0 || suggested > R.maxWeightKg) {
        return invalid(`suggestedWeightKg fora de 0..${R.maxWeightKg} em [${index}]: ${suggested}`);
      }
      if (sameWeight(suggested, current)) {
        return invalid(`suggestedWeightKg igual ao atual em [${index}]`);
      }
      return VALID;
    }

    case 'ADJUST_SETS': {
      const current = planned.targetSets ?? null;
      if (current === null) return invalid(`treino sem séries configuradas em [${index}]`);
      if (raw.currentSets !== current) {
        return invalid(
          `currentSets não corresponde ao treino em [${index}]: modelo=${raw.currentSets ?? 'null'}, treino=${current}`,
        );
      }
      const suggested = raw.suggestedSets;
      if (suggested === null || suggested === undefined) {
        return invalid(`suggestedSets ausente em [${index}]`);
      }
      const range = validateIntRange(suggested, R.minSets, R.maxSets, 'suggestedSets', index);
      if (!range.ok) return range;
      if (suggested === current) return invalid(`suggestedSets igual ao atual em [${index}]`);
      return VALID;
    }

    case 'ADJUST_REPS': {
      const currentMin = planned.minReps ?? null;
      const currentMax = planned.maxReps ?? null;
      if (currentMin === null || currentMax === null) {
        return invalid(`treino sem faixa de repetições em [${index}]`);
      }
      if (raw.currentMinReps !== currentMin || raw.currentMaxReps !== currentMax) {
        return invalid(
          `repetições atuais não correspondem ao treino em [${index}]: modelo=${raw.currentMinReps ?? 'null'}-${raw.currentMaxReps ?? 'null'}, treino=${currentMin}-${currentMax}`,
        );
      }
      const suggestedMin = raw.suggestedMinReps;
      const suggestedMax = raw.suggestedMaxReps;
      if (suggestedMin === null || suggestedMin === undefined) {
        return invalid(`suggestedMinReps ausente em [${index}]`);
      }
      if (suggestedMax === null || suggestedMax === undefined) {
        return invalid(`suggestedMaxReps ausente em [${index}]`);
      }
      const min = validateIntRange(suggestedMin, R.minReps, R.maxReps, 'suggestedMinReps', index);
      if (!min.ok) return min;
      if (
        !Number.isInteger(suggestedMax) ||
        suggestedMax < suggestedMin ||
        suggestedMax > R.maxReps
      ) {
        return invalid(`suggestedMaxReps inválido em [${index}]: ${suggestedMax}`);
      }
      if (suggestedMin === currentMin && suggestedMax === currentMax) {
        return invalid(`repetições sugeridas iguais às atuais em [${index}]`);
      }
      return VALID;
    }

    case 'ADJUST_REST': {
      const current = planned.restSeconds ?? null;
      if (current === null) return invalid(`treino sem descanso configurado em [${index}]`);
      if (raw.currentRestSeconds !== current) {
        return invalid(
          `currentRestSeconds não corresponde ao treino em [${index}]: modelo=${raw.currentRestSeconds ?? 'null'}, treino=${current}`,
        );
      }
      const suggested = raw.suggestedRestSeconds;
      if (suggested === null || suggested === undefined) {
        return invalid(`suggestedRestSeconds ausente em [${index}]`);
      }
      const range = validateIntRange(
        suggested,
        R.minRestSeconds,
        R.maxRestSeconds,
        'suggestedRestSeconds',
        index,
      );
      if (!range.ok) return range;
      if (suggested === current)
        return invalid(`suggestedRestSeconds igual ao atual em [${index}]`);
      return VALID;
    }

    case 'REPLACE_EXERCISE': {
      const replacementId = trimmedOrNull(raw.replacementExerciseId);
      if (replacementId === null) return invalid(`replacementExerciseId ausente em [${index}]`);
      if (replacementId === planned.exerciseId) {
        return invalid(`replacementExerciseId igual ao exercício atual em [${index}]`);
      }
      // Id que o app não ofereceu é invenção — inclusive um id real do catálogo que não entrou
      // nos candidatos desta requisição.
      if (!scope.replacementsById.has(replacementId)) {
        return invalid(
          `replacementExerciseId fora dos candidatos em [${index}]: '${replacementId}'`,
        );
      }
      if (scope.templateIds.has(replacementId)) {
        return invalid(`replacementExerciseId já está no treino em [${index}]`);
      }
      if (scope.seenReplacements.has(replacementId)) {
        return invalid(`mesmo substituto usado duas vezes em [${index}]: '${replacementId}'`);
      }
      scope.seenReplacements.add(replacementId);
      return VALID;
    }

    default:
      return invalid(`tipo desconhecido em [${index}]: '${type}'`);
  }
}

// ---------------------------------------------------------------------------------------------
// EXPLAIN_*
// ---------------------------------------------------------------------------------------------

/** Todos os `exerciseId` que uma explicação tem permissão de citar. */
export function knownExplanationExerciseIds(context: ExplanationContext): Set<string> {
  const ids = new Set<string>();
  if (context.exerciseId) ids.add(context.exerciseId);
  if (context.exerciseHistory) ids.add(context.exerciseHistory.exerciseId);
  context.workoutExercises.forEach((exercise) => ids.add(exercise.exerciseId));
  return ids;
}

export function validateExplanation(
  context: ExplanationContext,
  response: RawExplanationOutput,
): AiCoachValidation {
  const title = response.title.trim();
  if (title.length === 0) return invalid('title vazio');
  if (title.length > R.maxTitleLength) {
    return invalid(`title excede ${R.maxTitleLength} caracteres`);
  }

  const explanation = response.explanation.trim();
  if (explanation.length === 0) return invalid('explanation vazia');
  if (explanation.length > R.maxExplanationTextLength) {
    return invalid(`explanation excede ${R.maxExplanationTextLength} caracteres`);
  }

  if (response.limitations.length > R.maxLimitations) {
    return invalid(`mais de ${R.maxLimitations} limitações`);
  }
  for (const [index, raw] of response.limitations.entries()) {
    const limitation = raw.trim();
    if (limitation.length === 0) return invalid(`limitação vazia em [${index}]`);
    if (limitation.length > R.maxDescriptionLength) {
      return invalid(`limitação excede ${R.maxDescriptionLength} caracteres em [${index}]`);
    }
  }

  const known = knownExplanationExerciseIds(context);
  for (const [index, raw] of response.referencedExerciseIds.entries()) {
    const exerciseId = raw.trim();
    if (exerciseId.length === 0) return invalid(`exerciseId vazio em [${index}]`);
    if (!known.has(exerciseId)) {
      return invalid(`exerciseId fora do contexto em [${index}]: '${exerciseId}'`);
    }
  }

  // Uma explicação é read-only por construção: o schema de saída não tem campo de ação, e o
  // servidor não escreve nada de domínio em nenhum caminho.
  return VALID;
}

// ---------------------------------------------------------------------------------------------

/**
 * O nível declarado pelo modelo, conferido contra o teto que o **app** calculou.
 *
 * O modelo pode ser mais conservador que o app, nunca mais confiante: declarar mais evidência do
 * que recebeu é a forma mais barata de uma resposta soar melhor do que é.
 */
function validateDataQuality(
  ceiling: string,
  raw: { level: string; description: string } | null | undefined,
): AiCoachValidation {
  if (!raw) return invalid('dataQuality ausente');

  const level = raw.level.trim();
  const levelOrder = DATA_QUALITY_ORDER.get(level);
  if (levelOrder === undefined) return invalid(`dataQuality desconhecido: '${raw.level}'`);

  const ceilingOrder = DATA_QUALITY_ORDER.get(ceiling) ?? 0;
  if (levelOrder > ceilingOrder) {
    return invalid(`dataQuality ${level} acima da evidência enviada (${ceiling})`);
  }
  if (raw.description.trim().length > R.maxDescriptionLength) {
    return invalid(`dataQuality.description excede ${R.maxDescriptionLength} caracteres`);
  }
  return VALID;
}

function validateIntRange(
  value: number,
  min: number,
  max: number,
  field: string,
  index: number,
): AiCoachValidation {
  if (!Number.isInteger(value) || value < min || value > max) {
    return invalid(`${field} fora de ${min}..${max} em [${index}]: ${value}`);
  }
  return VALID;
}

/** Comparação de carga tolerante a `Float`, tratando ausência como valor. Regra do Android. */
function sameWeight(declared: number | null, inTemplate: number | null): boolean {
  if (declared === null && inTemplate === null) return true;
  if (declared === null || inTemplate === null) return false;
  return Math.abs(declared - inTemplate) <= R.weightToleranceKg;
}

function isPresent(value: unknown): boolean {
  return value !== null && value !== undefined;
}

function trimmedOrNull(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}
