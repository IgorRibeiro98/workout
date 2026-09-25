import { type ConsistencyParameters, isMondayEpochDay } from './social-consistency';
import { SocialProfileErrors } from './social-profile.errors';
import { PROGRESS_SHARING_FLAGS, type ProgressSharingFlag } from './social-progress.repository';
import { isValidTimeZone } from './social-progress.source';
import {
  MAX_SOCIAL_WEEK_TIME_ZONE_LENGTH,
  MAX_SOCIAL_WEEKLY_GOAL,
  MAX_SOCIAL_WEEKLY_GOAL_SNAPSHOTS,
  MIN_SOCIAL_TRACKING_EPOCH_DAY,
  MIN_SOCIAL_WEEKLY_GOAL,
} from './social.limits';

/**
 * A validação do perfil social enriquecido (T17.2).
 *
 * ## O que o cliente pode propor, e o que ele nunca propõe
 *
 * ```text
 * PODE     shareLevel, shareConsistencyStreak, shareWeeklyWorkoutCount,
 *          shareHighlightedAchievements,
 *          shareWeeklyTrainingMinutes, shareWeeklyCompletedSets,
 *          shareWeeklyVolume, shareTotalWorkouts,
 *          shareWorkoutName, shareWorkoutTime, shareWorkoutDuration,
 *          shareWorkoutExercises, shareWorkoutSets, shareWorkoutWeights,
 *          shareWorkoutVolume              ← preferência: o que os outros podem ver (T19.H3)
 *          weekTimeZone                    ← configuração do aparelho, não progresso
 *          consistency                     ← meta por semana + início do acompanhamento (T19.2A):
 *                                            os parâmetros da regra, nunca o resultado dela
 *
 * NUNCA    level, xp, totalXp, streak, consistencyStreak, weeklyWorkoutCount,
 *          achievementIds, earnedAchievementIds, longestStreak, unlocked, ...
 * ```
 *
 * A segunda lista é recusada **por nome** (§85–§87), e não apenas ausente do conjunto permitido.
 * A diferença é a mensagem: um cliente que manda `level` precisa descobrir que o servidor não
 * aceita progresso vindo do aparelho — e não que "o corpo é inválido". Aceitar seria o defeito
 * mais grave possível nesta tarefa: um APK modificado se declararia nível 99, e o perfil social
 * passaria a ser uma vitrine do que cada um digita sobre si.
 *
 * A recusa é **da requisição inteira**, nunca "ignorar em silêncio": um cliente que envia `level`
 * acredita que ele significa alguma coisa, e ignorar o deixaria acreditando por mais uma versão.
 */

/** Campos que descrevem quem é o chamador. Saem do token verificado, nunca do corpo. */
const CALLER_OWNED_FIELDS = [
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'socialId',
  'social_id',
  'friendCode',
  'friend_code',
  'email',
  'status',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
] as const;

/**
 * Valores de **progresso**. O servidor os deriva das autoridades canônicas; ele não os aceita.
 *
 * A lista cobre as grafias que um cliente tentaria naturalmente, incluindo as que o próprio DTO de
 * resposta usa — é justamente o formato da resposta que alguém copiaria para montar um PATCH.
 */
const PROGRESS_VALUE_FIELDS = [
  'level',
  'currentLevel',
  'xp',
  'totalXp',
  'currentLevelXp',
  'xpForNextLevel',
  'streak',
  'streakWeeks',
  'consistencyStreak',
  'weeklyWorkoutCount',
  'weeklyCompleted',
  'completedWorkouts',
  'achievements',
  'achievementIds',
  'earnedAchievementIds',
  'highlightedAchievementIds',
  'sharedProgress',
  'availability',
  // T19.H5 — o motivo de indisponibilidade é o servidor dizendo o que falta a ele; um cliente que
  // o envie acredita que pode declará-lo.
  'availabilityReasons',
  // T19.2 — o que a autoridade remota passou a derivar. Recusado por nome pelo mesmo motivo: o
  // servidor calcula, e um cliente que envie o resultado acredita que ele significa alguma coisa.
  'currentStreakWeeks',
  'longestStreakWeeks',
  'longestStreak',
  'verifiedXp',
  'xpTotal',
  'unlocked',
  'unlockedAchievementIds',
  'achievementUnlocks',
  'missions',
  'missionsCompleted',
  'personalRecords',
  // T19.H3 — as estatísticas e o resumo de treino também são derivados, nunca declarados. Um
  // `PATCH { weeklyVolumeKg: 5000 }` é exatamente o "meu volume foi 5000" que §21 proíbe.
  'weeklyTrainingMinutes',
  'weeklyCompletedSets',
  'weeklyVolumeKg',
  'weeklyVolume',
  'totalWorkouts',
  'totalVolumeKg',
  'volumeKg',
  'workoutSummary',
  'durationSeconds',
  'completedSetCount',
  'exerciseCount',
  'exercises',
  'sets',
  'reps',
  'weightKg',
] as const;

export type UpdateProgressSharingRequest = {
  readonly [K in ProgressSharingFlag]?: boolean;
} & {
  readonly weekTimeZone?: string;
  readonly consistency?: ConsistencyParameters;
};

/** Os quinze interruptores (T17.2 + T19.H3), o fuso e os parâmetros — e nada mais. */
const ALLOWED_FIELDS = [
  ...PROGRESS_SHARING_FLAGS.map(([flag]) => flag),
  'weekTimeZone',
  'consistency',
] as const;

/**
 * `PATCH /v1/social/me/progress-sharing`.
 *
 * **Semântica de `PATCH`, e só ela** (§57): o que não veio no corpo não muda. Não existe modo
 * "configuração completa" nesta rota, e misturar os dois seria a pior das opções — um cliente que
 * ligasse só o nível desligaria os outros três sem saber que o fez.
 *
 * Corpo vazio é recusado: ele quase sempre significa uma requisição montada errado, e responder
 * `200` a um pedido que não pediu nada esconderia o defeito.
 */
export function parseUpdateProgressSharingRequest(body: unknown): UpdateProgressSharingRequest {
  const object = requireObject(body);
  rejectCallerOwnedFields(object);
  rejectProgressValues(object);
  rejectUnknownFields(object);

  const request: {
    -readonly [K in keyof UpdateProgressSharingRequest]: UpdateProgressSharingRequest[K];
  } = {};

  for (const [flag] of PROGRESS_SHARING_FLAGS) {
    if (flag in object) {
      request[flag] = requireBoolean(object[flag], flag);
    }
  }
  if ('weekTimeZone' in object) {
    request.weekTimeZone = requireTimeZone(object.weekTimeZone);
  }
  if ('consistency' in object) {
    request.consistency = requireConsistencyParameters(object.consistency);
  }

  if (Object.keys(request).length === 0) {
    throw SocialProfileErrors.invalidProgressSettings(
      'nenhuma configuração de compartilhamento foi informada',
    );
  }
  return request;
}

/**
 * O `socialId` que veio no caminho da rota.
 *
 * A validação só impede que um valor absurdo chegue à consulta. Ela **não** decide existência: um
 * `socialId` fora de forma e um que nunca existiu recebem a mesma resposta
 * (`FRIEND_PROFILE_NOT_FOUND`), pela mesma razão do lookup da T17.1 — distinguir transformaria a
 * rota num validador gratuito de identidades alheias.
 */
export function parseSocialIdParam(raw: unknown): string {
  if (typeof raw !== 'string') {
    throw SocialProfileErrors.friendProfileNotFound();
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_SOCIAL_ID_LENGTH) {
    throw SocialProfileErrors.friendProfileNotFound();
  }
  return trimmed;
}

/** Um `socialId` é um UUID (36). O teto existe para recusar entrada absurda antes da consulta. */
const MAX_SOCIAL_ID_LENGTH = 128;

/**
 * O fuso IANA do dono.
 *
 * Validado contra o próprio runtime (`Intl`), e não contra uma lista mantida à mão: uma lista
 * envelheceria a cada revisão do banco de dados de fusos, e um fuso novo e legítimo passaria a ser
 * recusado. Um valor inválido é recusado em vez de virar UTC — um palpite produziria uma semana
 * plausível e errada, que é pior do que campo ausente (§4).
 */
function requireTimeZone(value: unknown): string {
  if (typeof value !== 'string') {
    throw SocialProfileErrors.invalidProgressSettings('weekTimeZone precisa ser texto');
  }
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_SOCIAL_WEEK_TIME_ZONE_LENGTH) {
    throw SocialProfileErrors.invalidProgressSettings(
      'weekTimeZone precisa ser um identificador IANA de fuso horário',
    );
  }
  if (!isValidTimeZone(trimmed)) {
    // A mensagem não repete o valor enviado: ela descreve a forma esperada. É a mesma regra das
    // outras mensagens sociais — nenhuma delas devolve conteúdo do usuário.
    throw SocialProfileErrors.invalidProgressSettings(
      'weekTimeZone precisa ser um identificador IANA de fuso horário',
    );
  }
  return trimmed;
}

/**
 * Os parâmetros de consistência (T19.2A).
 *
 * O que se valida aqui é a **forma** de uma configuração — inteiros, segundas-feiras, meta no
 * intervalo da tela, tamanho plausível — e não a sua veracidade: a meta é escolha do dono, no
 * aparelho e aqui. O que impede um parâmetro de virar sequência inventada não é esta função, é a
 * derivação: ela só conta sessões `COMPLETED` que chegaram por sync.
 *
 * Os campos de **resultado** (`streak`, `longestStreak`, `completedWorkouts`...) continuam
 * recusados por nome — inclusive dentro deste objeto, porque é aqui que alguém tentaria encaixá-los.
 */
function requireConsistencyParameters(value: unknown): ConsistencyParameters {
  const object = requireNestedObject(value, 'consistency');
  rejectProgressValues(object);
  for (const key of Object.keys(object)) {
    if (key !== 'trackingStartedAtEpochDay' && key !== 'weeklyGoals') {
      throw SocialProfileErrors.invalidProgressSettings(
        `campo não reconhecido em consistency: ${key}`,
      );
    }
  }

  const trackingStartedAtEpochDay = requireEpochDay(
    object.trackingStartedAtEpochDay,
    'consistency.trackingStartedAtEpochDay',
  );

  if (!Array.isArray(object.weeklyGoals)) {
    throw SocialProfileErrors.invalidProgressSettings(
      'consistency.weeklyGoals precisa ser uma lista',
    );
  }
  if (object.weeklyGoals.length > MAX_SOCIAL_WEEKLY_GOAL_SNAPSHOTS) {
    throw SocialProfileErrors.invalidProgressSettings(
      `consistency.weeklyGoals aceita no máximo ${MAX_SOCIAL_WEEKLY_GOAL_SNAPSHOTS} semanas`,
    );
  }

  const seen = new Set<number>();
  const weeklyGoals = object.weeklyGoals.map((raw: unknown) => {
    const snapshot = requireNestedObject(raw, 'consistency.weeklyGoals[]');
    rejectProgressValues(snapshot);
    for (const key of Object.keys(snapshot)) {
      if (key !== 'weekStartEpochDay' && key !== 'goal') {
        throw SocialProfileErrors.invalidProgressSettings(
          `campo não reconhecido em consistency.weeklyGoals: ${key}`,
        );
      }
    }
    // `setWeeklyGoal` grava a meta nova a partir da **próxima** segunda-feira: um snapshot até
    // oito dias à frente de hoje é a configuração normal do app, não um cliente inventando futuro.
    const weekStartEpochDay = requireEpochDay(
      snapshot.weekStartEpochDay,
      'consistency.weeklyGoals[].weekStartEpochDay',
      8,
    );
    if (!isMondayEpochDay(weekStartEpochDay)) {
      throw SocialProfileErrors.invalidProgressSettings(
        'consistency.weeklyGoals[].weekStartEpochDay precisa ser uma segunda-feira',
      );
    }
    if (seen.has(weekStartEpochDay)) {
      throw SocialProfileErrors.invalidProgressSettings(
        'consistency.weeklyGoals não pode repetir a mesma semana',
      );
    }
    seen.add(weekStartEpochDay);

    const goal = snapshot.goal;
    if (
      typeof goal !== 'number' ||
      !Number.isInteger(goal) ||
      goal < MIN_SOCIAL_WEEKLY_GOAL ||
      goal > MAX_SOCIAL_WEEKLY_GOAL
    ) {
      throw SocialProfileErrors.invalidProgressSettings(
        `consistency.weeklyGoals[].goal precisa ser um inteiro entre ${MIN_SOCIAL_WEEKLY_GOAL} e ${MAX_SOCIAL_WEEKLY_GOAL}`,
      );
    }
    return { weekStartEpochDay, goal };
  });

  return { trackingStartedAtEpochDay, weeklyGoals };
}

/**
 * Um epoch day plausível: inteiro, não anterior ao piso do Spark e não mais de [maxDaysAhead]
 * dias à frente de hoje em UTC (um dono em UTC+14 já está "amanhã" para o servidor).
 */
function requireEpochDay(value: unknown, field: string, maxDaysAhead = 1): number {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    throw SocialProfileErrors.invalidProgressSettings(
      `${field} precisa ser um inteiro (epoch day)`,
    );
  }
  const todayUtcEpochDay = Math.floor(Date.now() / 86_400_000);
  if (value < MIN_SOCIAL_TRACKING_EPOCH_DAY || value > todayUtcEpochDay + maxDaysAhead) {
    throw SocialProfileErrors.invalidProgressSettings(`${field} está fora do intervalo aceito`);
  }
  return value;
}

function requireNestedObject(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw SocialProfileErrors.invalidProgressSettings(`${field} precisa ser um objeto JSON`);
  }
  return value as Record<string, unknown>;
}

function requireObject(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw SocialProfileErrors.invalidProgressSettings(
      'o corpo da requisição precisa ser um objeto JSON',
    );
  }
  return body as Record<string, unknown>;
}

function rejectCallerOwnedFields(object: Record<string, unknown>): void {
  for (const field of CALLER_OWNED_FIELDS) {
    if (field in object) {
      throw SocialProfileErrors.invalidProgressSettings(
        `${field} é definido pelo servidor e não pode ser enviado na requisição`,
      );
    }
  }
}

function rejectProgressValues(object: Record<string, unknown>): void {
  for (const field of PROGRESS_VALUE_FIELDS) {
    if (field in object) {
      throw SocialProfileErrors.invalidProgressSettings(
        `${field} é derivado das fontes canônicas do Spark e não pode ser enviado pelo cliente`,
      );
    }
  }
}

function rejectUnknownFields(object: Record<string, unknown>): void {
  for (const key of Object.keys(object)) {
    if (!(ALLOWED_FIELDS as readonly string[]).includes(key)) {
      throw SocialProfileErrors.invalidProgressSettings(
        `campo não reconhecido no corpo da requisição: ${key}`,
      );
    }
  }
}

function requireBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') {
    throw SocialProfileErrors.invalidProgressSettings(`${field} precisa ser booleano`);
  }
  return value;
}
