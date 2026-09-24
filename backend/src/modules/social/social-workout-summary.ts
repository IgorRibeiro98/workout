import type {
  WorkoutSocialExerciseDto,
  WorkoutSocialSetDto,
  WorkoutSocialSummaryDto,
} from './workout-checkin.contract';
import type { ProgressSharingFlags } from './social-progress.repository';
import {
  completedWorkingSets,
  isDurationSet,
  performedExercises,
  workoutMetrics,
} from './social-training-metrics';
import type { SocialSetFact, SocialWorkoutFacts } from './social-workout-facts.source';

/** Teto de texto livre (nome do treino, nome de exercício CUSTOM) no resumo social. */
export const MAX_SUMMARY_TEXT_LENGTH = 120;

/**
 * O resumo de treino de um check-in, **já filtrado** pelas escolhas do dono (T19.H3 §28–§35).
 *
 * ```text
 * WORKOUT_SESSION sincronizada ──▶ SocialWorkoutFacts ──▶ (este filtro) ──▶ workoutSummary
 * ```
 *
 * ## Um campo desligado não existe no JSON
 *
 * O objeto é montado **por inclusão**: cada campo entra só quando o interruptor dele está ligado
 * e o fato existe. Não existe `weightKg: null`, não existe `hidden: true`, e o Compose não recebe
 * nada para esconder — um cliente modificado que lesse a resposta crua veria exatamente o que a
 * tela mostra (§35). Quando nada sobra, o resultado é `null` e o check-in sai **sem**
 * `workoutSummary`, igual a um check-in da T17.8.
 *
 * ## As dependências entre interruptores (§31)
 *
 * ```text
 * Exercícios            → nomes (e grupo muscular) dos exercícios executados, e quantos foram
 * Séries e repetições   → quantas séries; com Exercícios, as repetições de cada série
 * Cargas utilizadas     → o peso **dentro** de cada série — exige Séries e repetições E Exercícios
 * Volume total          → a soma; independente dos outros três
 * ```
 *
 * "Cargas" sozinho não publica nada: carga sem série não tem onde aparecer, e uma "carga média"
 * inventada seria uma métrica que ninguém escolheu. A tela diz isso ao lado do interruptor.
 *
 * ## Retroativo por construção (§37)
 *
 * O filtro roda **na leitura**, com as escolhas **atuais** do dono, sobre a sessão canônica. Nada
 * disto é copiado para o check-in: desligar "Cargas" hoje remove as cargas também dos check-ins de
 * ontem, na próxima leitura de qualquer amigo.
 */
export function projectWorkoutSummary(
  facts: SocialWorkoutFacts,
  flags: ProgressSharingFlags,
): WorkoutSocialSummaryDto | null {
  const metrics = workoutMetrics(facts);
  const summary: {
    name?: string;
    startedAt?: number;
    durationSeconds?: number;
    exerciseCount?: number;
    completedSetCount?: number;
    totalVolumeKg?: number;
    exercises?: WorkoutSocialExerciseDto[];
  } = {};

  const name = boundedText(facts.name);
  if (flags.shareWorkoutName && name !== null) {
    summary.name = name;
  }
  if (flags.shareWorkoutTime) {
    summary.startedAt = facts.startedAt;
  }
  if (flags.shareWorkoutDuration && metrics.durationSeconds !== null) {
    summary.durationSeconds = metrics.durationSeconds;
  }
  if (flags.shareWorkoutExercises) {
    summary.exerciseCount = metrics.exerciseCount;
  }
  if (flags.shareWorkoutSets) {
    summary.completedSetCount = metrics.completedSetCount;
  }
  if (flags.shareWorkoutVolume) {
    summary.totalVolumeKg = metrics.volumeKg;
  }

  if (flags.shareWorkoutExercises) {
    const withSets = flags.shareWorkoutSets;
    const withWeights = withSets && flags.shareWorkoutWeights;
    const exercises: WorkoutSocialExerciseDto[] = [];
    for (const exercise of performedExercises(facts)) {
      const exerciseName = boundedText(exercise.name);
      if (exerciseName === null) continue;
      const item: { name: string; primaryMuscle?: string; sets?: WorkoutSocialSetDto[] } = {
        name: exerciseName,
      };
      const muscle = boundedText(exercise.primaryMuscle);
      if (muscle !== null) {
        item.primaryMuscle = muscle;
      }
      if (withSets) {
        item.sets = completedWorkingSets(exercise).map((set) => toSetDto(set, withWeights));
      }
      exercises.push(item);
    }
    summary.exercises = exercises;
  }

  return Object.keys(summary).length > 0 ? summary : null;
}

/** Algum interruptor de detalhe está ligado? Sem nenhum, a sessão nem é lida (§38). */
export function sharesAnyWorkoutDetail(flags: ProgressSharingFlags): boolean {
  return (
    flags.shareWorkoutName ||
    flags.shareWorkoutTime ||
    flags.shareWorkoutDuration ||
    flags.shareWorkoutExercises ||
    flags.shareWorkoutSets ||
    flags.shareWorkoutVolume
  );
}

function toSetDto(set: SocialSetFact, withWeight: boolean): WorkoutSocialSetDto {
  const dto: { reps?: number; durationSeconds?: number; weightKg?: number } = {};
  if (isDurationSet(set)) {
    dto.durationSeconds = set.durationSeconds ?? 0;
  } else {
    dto.reps = set.repetitions;
  }
  // Peso zero é "sem carga" (peso corporal): não aparece como "0 kg". Duas casas, e não uma: uma
  // anilha de 1,25 kg produz 22,25 kg, e arredondar para 22,3 publicaria uma carga que não existiu.
  if (withWeight && set.weightKg > 0) {
    dto.weightKg = Math.round(set.weightKg * 100) / 100;
  }
  return dto;
}

function boundedText(value: string | null): string | null {
  if (value === null) return null;
  const trimmed = value.trim();
  if (trimmed.length === 0) return null;
  return trimmed.length > MAX_SUMMARY_TEXT_LENGTH
    ? trimmed.slice(0, MAX_SUMMARY_TEXT_LENGTH)
    : trimmed;
}
