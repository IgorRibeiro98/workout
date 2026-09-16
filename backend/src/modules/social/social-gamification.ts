import { type WeeklyConsistency, weekStartEpochDay } from './social-consistency';

/**
 * A autoridade remota de gamificação (T19.2B) e as derivações que ela sustenta (T19.2C).
 *
 * ## O princípio
 *
 * ```text
 * fato canônico sincronizado ──▶ servidor reconstrói ──▶ XP verificado ──▶ nível verificado
 *                                                     └─▶ conquistas verificadas
 * ```
 *
 * e **nunca** `cliente afirma ──▶ servidor acredita`. Nenhuma função deste arquivo recebe XP,
 * nível, streak ou lista de conquistas: todas recebem **contagens** de fatos que o servidor mesmo
 * guarda (`sync_entities`) e devolvem a consequência que a regra canônica do Spark dá a elas.
 *
 * ## A matriz de autoridade
 *
 * A gamificação local do Android continua sendo a autoridade **operacional** do aparelho: é ela
 * que anima a barra de XP e comemora uma conquista. O que existe aqui é a autoridade sobre o que
 * pode ser **publicado socialmente** — e ela só cobre o que o servidor consegue reconstruir. A
 * tabela abaixo é o resultado da investigação da T19.2B sobre `XpRewardPolicy`,
 * `GamificationEventRecorder`, `ConsistencyMilestoneEvaluator`, `MissionEvaluator` e
 * `AchievementEvaluator`, e é o que os testes estruturais fixam.
 *
 * Um valor `UNSUPPORTED_SERVER_SIDE` não é um defeito a corrigir "portando mais código": ele
 * descreve um fato cujos dados, ou cuja identidade, não chegam ao servidor de forma verificável.
 * O recorde pessoal é o exemplo: a regra vive em `WorkoutEngine.registerPersonalRecordIfImproved`
 * sobre cargas de série, e a `dedupeKey` do evento cita o `exerciseId` **local** do Room. Ler
 * cargas de série no domínio social seria exatamente a porta que `AGGREGATE_ONLY` fecha.
 *
 * ## Nível verificado ≤ nível local
 *
 * Como o recorde pessoal fica fora, o XP verificado é um **limite inferior** do XP que o aparelho
 * mostra ao dono. A curva é a mesma; o que difere é o conjunto de fatos que a alimenta. Isso é
 * dito na tela do dono e na documentação — e é preferível a publicar um nível que o servidor não
 * tem como defender.
 */

/** O que o servidor consegue afirmar sobre uma origem de XP. */
export type RemoteGamificationAuthority =
  /** O servidor recompõe o fato inteiramente a partir de dados canônicos que ele guarda. */
  | 'RECONSTRUCTABLE'
  /** O cliente precisaria enviar o fato, e o servidor conseguiria verificá-lo. Nenhuma origem usa isto hoje. */
  | 'VERIFIABLE'
  /** Só existe no aparelho e não influencia o que se publica. */
  | 'LOCAL_ONLY'
  /** Vale XP no aparelho e o servidor **não** tem como reconstruir nem verificar. Fica fora da projeção. */
  | 'UNSUPPORTED_SERVER_SIDE';

export interface XpSourceAuthority {
  /** O `GamificationEventType` do Kotlin. */
  readonly event: string;
  /** A recompensa de `XpRewardPolicy` (v1). `null` quando o fato não vale XP. */
  readonly xp: number | null | 'MISSION_CATALOG';
  readonly authority: RemoteGamificationAuthority;
  /** De onde o servidor tira o fato, ou por que não consegue. */
  readonly basis: string;
}

/** `XpRewardPolicy.VERSION` e `MissionCatalog.CATALOG_VERSION` do Android, espelhados. */
export const REMOTE_XP_POLICY_VERSION = 1;
export const REMOTE_MISSION_CATALOG_VERSION = 1;
export const REMOTE_ACHIEVEMENT_CATALOG_VERSION = 1;

export const XP_SOURCE_AUTHORITY: readonly XpSourceAuthority[] = [
  {
    event: 'WORKOUT_COMPLETED',
    xp: 100,
    authority: 'RECONSTRUCTABLE',
    basis: 'uma por WORKOUT_SESSION COMPLETED sincronizada (sem tombstone)',
  },
  {
    event: 'FIRST_WORKOUT_COMPLETED',
    xp: 100,
    authority: 'RECONSTRUCTABLE',
    basis: 'uma vez, quando existe ao menos uma sessão COMPLETED sincronizada',
  },
  {
    event: 'WEEKLY_GOAL_COMPLETED',
    xp: 150,
    authority: 'RECONSTRUCTABLE',
    basis:
      'uma por semana COMPLETED da consistência canônica (sessões + parâmetros declarados, T19.2A)',
  },
  {
    event: 'MISSION_COMPLETED',
    xp: 'MISSION_CATALOG',
    authority: 'RECONSTRUCTABLE',
    basis: 'as quatro missões do catálogo v1 são agregados de sessões por semana e da consistência',
  },
  {
    event: 'PERSONAL_RECORD_CREATED',
    xp: 50,
    authority: 'UNSUPPORTED_SERVER_SIDE',
    basis:
      'a regra é do WorkoutEngine sobre cargas de série e a dedupeKey cita exerciseId local; ler cargas no social violaria AGGREGATE_ONLY',
  },
  {
    event: 'STREAK_MILESTONE_REACHED',
    xp: null,
    authority: 'RECONSTRUCTABLE',
    basis: 'não vale XP; a sequência é a da consistência canônica',
  },
  {
    event: 'WORKOUT_STARTED',
    xp: null,
    authority: 'LOCAL_ONLY',
    basis: 'não vale XP; sessões não concluídas não sincronizam',
  },
  {
    event: 'EXERCISE_COMPLETED',
    xp: null,
    authority: 'LOCAL_ONLY',
    basis: 'não vale XP',
  },
  {
    event: 'FIRST_EXERCISE_COMPLETED',
    xp: null,
    authority: 'LOCAL_ONLY',
    basis: 'não vale XP',
  },
];

// --------------------------------------------------------------------------------- missões (v1)

/**
 * O catálogo de missões do Android (`MissionCatalog`, v1), na parte que o servidor reconstrói.
 *
 * `weekly_goal` não tem alvo próprio: a meta e o veredito da semana pertencem à consistência —
 * exatamente como no `MissionEvaluator`.
 */
export const REMOTE_MISSIONS = [
  { id: 'weekly_workouts_3', type: 'WORKOUT_COUNT', target: 3, rewardXp: 150 },
  { id: 'weekly_training_days_3', type: 'TRAINING_DAYS', target: 3, rewardXp: 150 },
  { id: 'weekly_goal', type: 'WEEKLY_GOAL', target: null, rewardXp: 100 },
  { id: 'total_workouts_10', type: 'TOTAL_WORKOUTS', target: 10, rewardXp: 200 },
] as const;

// --------------------------------------------------------------------------------- fatos → XP

/**
 * Os fatos verificados de um dono, como o servidor os agrega — sem timestamp nenhum.
 *
 * `sessionsPerDay` é a projeção `epoch day local → treinos concluídos iniciados naquele dia`, lida
 * do `sync_entities` por janelas de dia calculadas no fuso do dono (`social-time.ts`).
 */
export interface VerifiedWorkoutFacts {
  /** Total de sessões `COMPLETED` sincronizadas, em qualquer data. */
  readonly completedWorkouts: number;
  readonly sessionsPerDay: ReadonlyMap<number, number>;
  /** As semanas da consistência canônica; `null` quando o dono ainda não declarou os parâmetros. */
  readonly weeks: readonly WeeklyConsistency[] | null;
}

export interface VerifiedXpBreakdown {
  readonly workoutCompleted: number;
  readonly firstWorkoutCompleted: number;
  readonly weeklyGoalCompleted: number;
  readonly missions: number;
  readonly total: number;
}

/** Agrupa a projeção por dia em `semana → { sessões, dias ativos }`. */
export function weeklyActivity(
  sessionsPerDay: ReadonlyMap<number, number>,
): Map<number, { sessions: number; activeDays: number }> {
  const byWeek = new Map<number, { sessions: number; activeDays: number }>();
  for (const [epochDay, count] of sessionsPerDay) {
    if (count <= 0) {
      continue;
    }
    const week = weekStartEpochDay(epochDay);
    const entry = byWeek.get(week) ?? { sessions: 0, activeDays: 0 };
    entry.sessions += count;
    entry.activeDays += 1;
    byWeek.set(week, entry);
  }
  return byWeek;
}

/**
 * O XP que os fatos canônicos provam, pela política de recompensa v1.
 *
 * Cada linha corresponde a um fato que o `GamificationEventRecorder` registraria ao vivo — e a
 * uma `dedupeKey` que garante que ele vale uma vez só:
 *
 * | fato local | dedupeKey | aqui |
 * | --- | --- | --- |
 * | `WORKOUT_COMPLETED` | `workout_completed:<sessão>` | `completedWorkouts × 100` |
 * | `FIRST_WORKOUT_COMPLETED` | `first_workout_completed` | `100`, se houver sessão |
 * | `WEEKLY_GOAL_COMPLETED` | `weekly_goal:<semana>` | `150 × semanas COMPLETED` |
 * | `MISSION_COMPLETED` | `mission_completed:<missão>:<período>` | catálogo v1, por período |
 *
 * As semanas das duas missões contadas por sessão (`weekly_workouts_3`, `weekly_training_days_3`)
 * são **todas** as semanas com sessão sincronizada, sem o filtro de início do acompanhamento —
 * o `MissionEvaluator` também não o aplica a elas. `weekly_goal` segue a consistência, que aplica.
 */
export function projectVerifiedXp(facts: VerifiedWorkoutFacts): VerifiedXpBreakdown {
  const workoutCompleted = facts.completedWorkouts * 100;
  const firstWorkoutCompleted = facts.completedWorkouts >= 1 ? 100 : 0;

  const completedWeeks = (facts.weeks ?? []).filter((week) => week.status === 'COMPLETED').length;
  const weeklyGoalCompleted = completedWeeks * 150;

  let missions = 0;
  const activity = weeklyActivity(facts.sessionsPerDay);
  for (const mission of REMOTE_MISSIONS) {
    switch (mission.type) {
      case 'WORKOUT_COUNT':
        for (const week of activity.values()) {
          if (week.sessions >= mission.target) missions += mission.rewardXp;
        }
        break;
      case 'TRAINING_DAYS':
        for (const week of activity.values()) {
          if (week.activeDays >= mission.target) missions += mission.rewardXp;
        }
        break;
      case 'WEEKLY_GOAL':
        missions += completedWeeks * mission.rewardXp;
        break;
      case 'TOTAL_WORKOUTS':
        if (facts.completedWorkouts >= mission.target) missions += mission.rewardXp;
        break;
    }
  }

  return {
    workoutCompleted,
    firstWorkoutCompleted,
    weeklyGoalCompleted,
    missions,
    total: workoutCompleted + firstWorkoutCompleted + weeklyGoalCompleted + missions,
  };
}

// --------------------------------------------------------------------------------- XP → nível

export interface VerifiedLevel {
  readonly level: number;
  readonly currentLevelXp: number;
  readonly xpForNextLevel: number;
}

/**
 * `XpTransactionRepositoryImpl.calculateProgress`: o nível 1 pede 500 XP, e cada nível seguinte
 * pede `nível × 500`. A curva é a do aparelho, linha por linha — uma curva social "simplificada"
 * seria uma segunda fórmula, e um nível que o próprio dono não reconhece.
 */
export function levelFor(totalXp: number): VerifiedLevel {
  let level = 1;
  let xpForNext = 500;
  let remaining = Math.max(0, Math.floor(totalXp));

  while (remaining >= xpForNext) {
    remaining -= xpForNext;
    level++;
    xpForNext = level * 500;
  }

  return { level, currentLevelXp: remaining, xpForNextLevel: xpForNext };
}

// --------------------------------------------------------------------------------- conquistas

export type RemoteAchievementCategory = 'TRAINING' | 'CONSISTENCY' | 'PERFORMANCE' | 'BODY';

export interface RemoteAchievementDefinition {
  readonly id: string;
  readonly category: RemoteAchievementCategory;
  readonly target: number;
  readonly authority: RemoteGamificationAuthority;
}

/**
 * O `AchievementCatalog` do Android (v1), com a autoridade remota de cada conquista.
 *
 * A ordem é a do catálogo: é nela que a lista publicada sai. Os ids são os canônicos — o mesmo
 * texto que `achievement_unlocks.achievementId` guarda no aparelho e que a tela de conquistas
 * resolve em título e ícone.
 *
 * `PERFORMANCE` é `UNSUPPORTED_SERVER_SIDE` pela mesma razão do XP de recorde: a contagem de
 * recordes é uma contagem de eventos `PERSONAL_RECORD_CREATED`, e esses eventos não são
 * reconstruíveis aqui. Elas **nunca** entram na lista publicada — nem como "não obtida".
 */
export const REMOTE_ACHIEVEMENTS: readonly RemoteAchievementDefinition[] = [
  { id: 'first_workout', category: 'TRAINING', target: 1, authority: 'RECONSTRUCTABLE' },
  { id: '10_workouts', category: 'TRAINING', target: 10, authority: 'RECONSTRUCTABLE' },
  { id: '25_workouts', category: 'TRAINING', target: 25, authority: 'RECONSTRUCTABLE' },
  { id: '50_workouts', category: 'TRAINING', target: 50, authority: 'RECONSTRUCTABLE' },
  { id: '100_workouts', category: 'TRAINING', target: 100, authority: 'RECONSTRUCTABLE' },

  { id: 'streak_2_weeks', category: 'CONSISTENCY', target: 2, authority: 'RECONSTRUCTABLE' },
  { id: 'streak_4_weeks', category: 'CONSISTENCY', target: 4, authority: 'RECONSTRUCTABLE' },
  { id: 'streak_8_weeks', category: 'CONSISTENCY', target: 8, authority: 'RECONSTRUCTABLE' },
  { id: 'streak_12_weeks', category: 'CONSISTENCY', target: 12, authority: 'RECONSTRUCTABLE' },
  { id: 'streak_24_weeks', category: 'CONSISTENCY', target: 24, authority: 'RECONSTRUCTABLE' },
  { id: 'streak_52_weeks', category: 'CONSISTENCY', target: 52, authority: 'RECONSTRUCTABLE' },

  { id: 'first_pr', category: 'PERFORMANCE', target: 1, authority: 'UNSUPPORTED_SERVER_SIDE' },
  { id: '5_prs', category: 'PERFORMANCE', target: 5, authority: 'UNSUPPORTED_SERVER_SIDE' },
  { id: '10_prs', category: 'PERFORMANCE', target: 10, authority: 'UNSUPPORTED_SERVER_SIDE' },
  { id: '25_prs', category: 'PERFORMANCE', target: 25, authority: 'UNSUPPORTED_SERVER_SIDE' },

  { id: 'first_measurement', category: 'BODY', target: 1, authority: 'RECONSTRUCTABLE' },
  { id: '4_measurements', category: 'BODY', target: 4, authority: 'RECONSTRUCTABLE' },
  { id: '12_measurements', category: 'BODY', target: 12, authority: 'RECONSTRUCTABLE' },
  { id: '24_measurements', category: 'BODY', target: 24, authority: 'RECONSTRUCTABLE' },
];

/** As contagens que o `AchievementEvaluator` usa, na parte que o servidor consegue produzir. */
export interface VerifiedAchievementFacts {
  readonly completedWorkouts: number;
  /** `ConsistencyProgress.longestStreakWeeks`; `null` sem parâmetros declarados. */
  readonly longestStreakWeeks: number | null;
  /** Dias locais distintos com medição corporal sincronizada; `null` sem fuso declarado. */
  readonly measurementDays: number | null;
}

/**
 * `AchievementEvaluator.evaluate`, restrito ao que tem autoridade remota.
 *
 * Uma categoria cujo fato é `null` não é avaliada — nem como obtida, nem como não obtida. A lista
 * devolvida é o que o servidor **consegue afirmar agora**; a ordem é a do catálogo.
 */
export function evaluateVerifiedAchievements(facts: VerifiedAchievementFacts): string[] {
  const earned: string[] = [];
  for (const definition of REMOTE_ACHIEVEMENTS) {
    if (definition.authority !== 'RECONSTRUCTABLE') {
      continue;
    }
    const progress = progressFor(definition.category, facts);
    if (progress !== null && progress >= definition.target) {
      earned.push(definition.id);
    }
  }
  return earned;
}

function progressFor(
  category: RemoteAchievementCategory,
  facts: VerifiedAchievementFacts,
): number | null {
  switch (category) {
    case 'TRAINING':
      return facts.completedWorkouts;
    case 'CONSISTENCY':
      return facts.longestStreakWeeks;
    case 'BODY':
      return facts.measurementDays;
    case 'PERFORMANCE':
      return null;
  }
}
