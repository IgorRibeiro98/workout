import type {
  SocialExerciseFact,
  SocialSetFact,
  SocialWorkoutFacts,
} from './social-workout-facts.source';

/**
 * As métricas de treino que o Social publica — **uma** definição, para o perfil e para o check-in
 * (T19.H3 §25/§34).
 *
 * `weeklyVolumeKg` do perfil e `totalVolumeKg` de um check-in são a mesma conta sobre conjuntos
 * diferentes de sessões. Se cada serviço escrevesse a sua, o primeiro ajuste em uma delas faria o
 * perfil de alguém dizer um volume semanal diferente da soma dos check-ins da mesma semana.
 *
 * ## As definições (documentadas em `docs/architecture/social-profile-contract.md` §V3)
 *
 * - **Série concluída** — `completed = true` **e** tipo diferente de `WARMUP`. É a regra de
 *   `VolumeCalculator.countEffectiveSets` do app: o aquecimento não é série de trabalho, e o
 *   Resumo do treino que o dono vê já não o conta.
 * - **Volume** — `Σ peso × repetições` das séries concluídas, excluindo as séries por tempo
 *   (`durationSeconds > 0`, onde repetição não tem significado). Série com `peso = 0` (peso
 *   corporal, elástico) **não adiciona carga**: nenhum peso corporal é estimado — o servidor não
 *   conhece o peso de ninguém, e inventá-lo seria publicar um número que o dono nunca registrou.
 *   Mesma regra de `VolumeCalculator.calculateSetsVolume`.
 * - **Duração** — `finishedAt − startedAt`, derivada aqui; nunca um valor enviado à parte. Sem
 *   `finishedAt`, ou com `finishedAt < startedAt`, não há duração a afirmar.
 * - **Exercício executado** — um exercício com ao menos uma série concluída. Um exercício planejado
 *   e pulado não aparece como "feito".
 */

/** A série conta como trabalho? */
export function isCompletedWorkingSet(set: SocialSetFact): boolean {
  return set.completed && set.type !== 'WARMUP';
}

/** Série medida em tempo, e não em repetições. */
export function isDurationSet(set: SocialSetFact): boolean {
  return set.durationSeconds !== null && set.durationSeconds > 0;
}

/** A carga de uma série no volume: `peso × reps`, ou zero quando não há carga a somar. */
export function setVolumeKg(set: SocialSetFact): number {
  if (!isCompletedWorkingSet(set) || isDurationSet(set)) return 0;
  if (set.weightKg <= 0 || set.repetitions <= 0) return 0;
  return set.weightKg * set.repetitions;
}

/** As séries de trabalho concluídas de um exercício, na ordem em que foram feitas. */
export function completedWorkingSets(exercise: SocialExerciseFact): readonly SocialSetFact[] {
  return exercise.sets.filter(isCompletedWorkingSet);
}

/** Os exercícios que de fato tiveram trabalho, na ordem de execução. */
export function performedExercises(facts: SocialWorkoutFacts): readonly SocialExerciseFact[] {
  return facts.exercises.filter((exercise) => completedWorkingSets(exercise).length > 0);
}

/** Duração em segundos inteiros, ou `null` quando não é afirmável. */
export function workoutDurationSeconds(facts: SocialWorkoutFacts): number | null {
  if (facts.finishedAt === null || facts.finishedAt < facts.startedAt) return null;
  return Math.floor((facts.finishedAt - facts.startedAt) / 1000);
}

export interface WorkoutMetrics {
  readonly exerciseCount: number;
  readonly completedSetCount: number;
  readonly volumeKg: number;
  readonly durationSeconds: number | null;
}

/** As métricas de **uma** sessão. */
export function workoutMetrics(facts: SocialWorkoutFacts): WorkoutMetrics {
  const performed = performedExercises(facts);
  let completedSetCount = 0;
  let volumeKg = 0;
  for (const exercise of performed) {
    for (const set of completedWorkingSets(exercise)) {
      completedSetCount += 1;
      volumeKg += setVolumeKg(set);
    }
  }
  return {
    exerciseCount: performed.length,
    completedSetCount,
    volumeKg: roundKg(volumeKg),
    durationSeconds: workoutDurationSeconds(facts),
  };
}

export interface TrainingTotals {
  /** Minutos inteiros: `floor(Σ duração / 60)`. Sessão sem fim afirmável soma zero. */
  readonly trainingMinutes: number;
  readonly completedSets: number;
  readonly volumeKg: number;
}

/** A soma de várias sessões — a semana canônica do perfil. */
export function trainingTotals(sessions: readonly SocialWorkoutFacts[]): TrainingTotals {
  let seconds = 0;
  let completedSets = 0;
  let volumeKg = 0;
  for (const facts of sessions) {
    const metrics = workoutMetrics(facts);
    seconds += metrics.durationSeconds ?? 0;
    completedSets += metrics.completedSetCount;
    volumeKg += metrics.volumeKg;
  }
  return {
    trainingMinutes: Math.floor(seconds / 60),
    completedSets,
    volumeKg: roundKg(volumeKg),
  };
}

/**
 * Uma casa decimal. `Float` do Kotlin vira `22.5` ou `72.30000305175781` no JSON dependendo do
 * valor; arredondar aqui é o que impede o ruído de ponto flutuante de virar "volume 2250.0000001".
 */
export function roundKg(value: number): number {
  return Math.round(value * 10) / 10;
}
