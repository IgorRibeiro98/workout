import { DAY_MS, localCalendarDate, localMidnightToInstant } from './social-time';

/**
 * A regra canônica de consistência do Spark, no servidor (T19.2A).
 *
 * ## O que este arquivo é, e o que ele não é
 *
 * É a **mesma** regra de `ConsistencyCalculator.calculateWeeklyConsistencies` +
 * `calculateProgress` (Android, `domain/evolution/calculator`), escrita sobre o que o servidor
 * tem: contagens por semana, e não a lista de sessões. Ela não foi inventada aqui — cada ramo
 * abaixo corresponde a um ramo do Kotlin, e a fixture compartilhada
 * `contracts/social/v1/consistency-streak.json` é lida pelos testes dos **dois** lados. Uma
 * mudança unilateral quebra o teste de quem mudou, em vez de virar "o perfil do meu amigo mostra
 * uma sequência diferente da que ele vê".
 *
 * Não é uma segunda autoridade sobre o que o **dono** vê: a tela do próprio usuário continua
 * calculada no aparelho. O que sai daqui é a **projeção social** — o que o servidor consegue
 * afirmar a partir de fatos que ele mesmo guarda (sessões `COMPLETED` sincronizadas) e de
 * parâmetros que o dono declarou (meta por semana e início do acompanhamento).
 *
 * ## Semana, dia e fuso
 *
 * A semana começa na **segunda-feira** da data local do dono (`weekStart`), e um "dia" aqui é um
 * epoch day do calendário local, exatamente como o `LocalDate.toEpochDay()` do Kotlin. A conversão
 * de instante para data local é a de `social-time.ts` (ICU), a mesma da semana canônica da T17.2 e
 * dos dias de desafio da T17.3 — nenhum terceiro conceito de "que dia é" nasce aqui.
 *
 * ## Só contagens entram
 *
 * A entrada é `Map<epochDay, sessões>`: quantos treinos concluídos começaram em cada dia local.
 * Nenhum timestamp de treino chega a este arquivo, e nenhum sai dele.
 */

/** O mesmo `WeeklyConsistencyStatus` do Kotlin. */
export type WeeklyConsistencyStatus = 'IN_PROGRESS' | 'COMPLETED' | 'MISSED' | 'NOT_COUNTED';

/** Uma linha de `weekly_goal_history`: a meta vigente **a partir** daquela segunda-feira. */
export interface WeeklyGoalSnapshot {
  readonly weekStartEpochDay: number;
  readonly goal: number;
}

/**
 * Os parâmetros de consistência que o dono declara (§6.1 da T19.2).
 *
 * Eles são **configuração**, não progresso: são o que o próprio aparelho lê de
 * `weekly_goal_history` e do DataStore para calcular a sequência do dono. Sem eles o servidor não
 * tem como saber qual meta valia em cada semana — e supor "3" produziria uma sequência plausível e
 * errada. Um parâmetro não fabrica sessão: com qualquer meta e qualquer início, a sequência que sai
 * daqui só cresce com treinos `COMPLETED` que de fato chegaram por sync.
 */
export interface ConsistencyParameters {
  /** Epoch day (calendário local do dono) em que o acompanhamento começou. */
  readonly trackingStartedAtEpochDay: number;
  readonly weeklyGoals: readonly WeeklyGoalSnapshot[];
}

/** O mesmo `WeeklyConsistency` do Kotlin. */
export interface WeeklyConsistency {
  readonly weekStartEpochDay: number;
  readonly goal: number;
  readonly completedWorkouts: number;
  readonly status: WeeklyConsistencyStatus;
}

/** O mesmo `ConsistencyProgress` do Kotlin. */
export interface ConsistencyProgress {
  readonly currentStreakWeeks: number;
  readonly longestStreakWeeks: number;
  readonly currentWeekCompleted: number;
  readonly currentWeekGoal: number;
  readonly currentWeekStatus: WeeklyConsistencyStatus;
}

/**
 * Epoch day 4 = 1970-01-05, uma segunda-feira. `LocalDate.with(DayOfWeek.MONDAY)` leva qualquer
 * data para a segunda da **mesma** semana ISO, ou seja, para trás — nunca para frente.
 */
const MONDAY_ANCHOR_EPOCH_DAY = 4;

/** Segunda-feira da semana que contém [epochDay] — `ConsistencyCalculator.weekStart`. */
export function weekStartEpochDay(epochDay: number): number {
  const offset = (((epochDay - MONDAY_ANCHOR_EPOCH_DAY) % 7) + 7) % 7;
  return epochDay - offset;
}

/** [epochDay] é uma segunda-feira? */
export function isMondayEpochDay(epochDay: number): boolean {
  return weekStartEpochDay(epochDay) === epochDay;
}

/** O epoch day do calendário **local** de um instante. */
export function localEpochDay(instantMs: number, timeZone: string): number {
  return Math.floor(localCalendarDate(instantMs, timeZone) / DAY_MS);
}

/** A janela `[meia-noite local, meia-noite local seguinte)` de um epoch day, em epoch millis. */
export function localDayWindow(
  epochDay: number,
  timeZone: string,
): { readonly startMs: number; readonly endMs: number } {
  const dateAsUtc = epochDay * DAY_MS;
  return {
    startMs: localMidnightToInstant(dateAsUtc, timeZone),
    endMs: localMidnightToInstant(dateAsUtc + DAY_MS, timeZone),
  };
}

/**
 * `ConsistencyCalculator.calculateWeeklyConsistencies`, sobre contagens por dia.
 *
 * @param sessionsPerDay quantos treinos concluídos começaram em cada epoch day local.
 * @param referenceEpochDay o "hoje" do dono, em epoch day local — no servidor, derivado do
 * relógio do **servidor** no fuso do dono, pela mesma razão da semana canônica da T17.2.
 */
export function calculateWeeklyConsistencies(
  sessionsPerDay: ReadonlyMap<number, number>,
  parameters: ConsistencyParameters,
  referenceEpochDay: number,
): WeeklyConsistency[] {
  const currentMonday = weekStartEpochDay(referenceEpochDay);
  const startMonday = weekStartEpochDay(parameters.trackingStartedAtEpochDay);
  if (startMonday > currentMonday) {
    return [];
  }

  const sortedGoals = [...parameters.weeklyGoals].sort(
    (a, b) => a.weekStartEpochDay - b.weekStartEpochDay,
  );
  const resolveGoal = (weekStart: number): number | null => {
    let goal: number | null = null;
    for (const snapshot of sortedGoals) {
      if (snapshot.weekStartEpochDay <= weekStart) {
        goal = snapshot.goal;
      }
    }
    return goal;
  };

  // Só treinos a partir da segunda do início do acompanhamento contam — o mesmo filtro do Kotlin
  // (`!it.isBefore(startMonday)`), aplicado às contagens por dia.
  const workoutsByWeek = new Map<number, number>();
  for (const [epochDay, count] of sessionsPerDay) {
    if (epochDay < startMonday || count <= 0) {
      continue;
    }
    const week = weekStartEpochDay(epochDay);
    workoutsByWeek.set(week, (workoutsByWeek.get(week) ?? 0) + count);
  }

  const result: WeeklyConsistency[] = [];
  const startedMidWeek = parameters.trackingStartedAtEpochDay > startMonday;

  for (let week = startMonday; week <= currentMonday; week += 7) {
    const count = workoutsByWeek.get(week) ?? 0;
    const goal = resolveGoal(week);
    const isCurrentWeek = week === currentMonday;
    const isFirstTrackingWeek = week === startMonday;

    if (goal === null) {
      result.push({
        weekStartEpochDay: week,
        goal: 0,
        completedWorkouts: count,
        status: 'NOT_COUNTED',
      });
      continue;
    }

    let status: WeeklyConsistencyStatus;
    if (isCurrentWeek) {
      status = count >= goal ? 'COMPLETED' : 'IN_PROGRESS';
    } else {
      status = count >= goal ? 'COMPLETED' : 'MISSED';
    }
    if (status === 'MISSED' && isFirstTrackingWeek && startedMidWeek) {
      status = 'NOT_COUNTED';
    }

    result.push({ weekStartEpochDay: week, goal, completedWorkouts: count, status });
  }

  return result;
}

/** `ConsistencyCalculator.calculateProgress`. */
export function calculateProgress(
  weeks: readonly WeeklyConsistency[],
  referenceEpochDay: number,
): ConsistencyProgress {
  if (weeks.length === 0) {
    return {
      currentStreakWeeks: 0,
      longestStreakWeeks: 0,
      currentWeekCompleted: 0,
      currentWeekGoal: 3,
      currentWeekStatus: 'IN_PROGRESS',
    };
  }

  const currentMonday = weekStartEpochDay(referenceEpochDay);
  const currentWeek =
    weeks.find((week) => week.weekStartEpochDay === currentMonday) ?? weeks[weeks.length - 1];
  const pastWeeks = weeks.filter((week) => week.weekStartEpochDay < currentWeek.weekStartEpochDay);

  let currentStreak = currentWeek.status === 'COMPLETED' ? 1 : 0;
  for (let i = pastWeeks.length - 1; i >= 0; i--) {
    const past = pastWeeks[i];
    if (past.status === 'COMPLETED') {
      currentStreak++;
    } else if (past.status === 'NOT_COUNTED') {
      continue;
    } else {
      break;
    }
  }
  if (currentWeek.status === 'MISSED') {
    currentStreak = 0;
  }

  let longestStreak = 0;
  let currentRun = 0;
  for (const week of pastWeeks) {
    if (week.status === 'COMPLETED') {
      currentRun++;
      if (currentRun > longestStreak) longestStreak = currentRun;
    } else if (week.status === 'NOT_COUNTED') {
      // Não quebra a sequência, não soma.
    } else {
      currentRun = 0;
    }
  }
  if (currentWeek.status === 'COMPLETED') {
    currentRun++;
    if (currentRun > longestStreak) longestStreak = currentRun;
  }
  if (currentStreak > longestStreak) {
    longestStreak = currentStreak;
  }

  return {
    currentStreakWeeks: currentStreak,
    longestStreakWeeks: longestStreak,
    currentWeekCompleted: currentWeek.completedWorkouts,
    currentWeekGoal: currentWeek.goal,
    currentWeekStatus: currentWeek.status,
  };
}
