import { SocialProfileErrors } from './social-profile.errors';
import { isValidTimeZone } from './social-progress.source';
import { MAX_SOCIAL_WEEK_TIME_ZONE_LENGTH } from './social.limits';

/**
 * A validação do perfil social enriquecido (T17.2).
 *
 * ## O que o cliente pode propor, e o que ele nunca propõe
 *
 * ```text
 * PODE     shareLevel, shareConsistencyStreak, shareWeeklyWorkoutCount,
 *          shareHighlightedAchievements    ← preferência: o que os outros podem ver
 *          weekTimeZone                    ← configuração do aparelho, não progresso
 *
 * NUNCA    level, xp, totalXp, streak, consistencyStreak, weeklyWorkoutCount,
 *          achievementIds, earnedAchievementIds, ...
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
] as const;

export interface UpdateProgressSharingRequest {
  readonly shareLevel?: boolean;
  readonly shareConsistencyStreak?: boolean;
  readonly shareWeeklyWorkoutCount?: boolean;
  readonly shareHighlightedAchievements?: boolean;
  readonly weekTimeZone?: string;
}

const ALLOWED_FIELDS = [
  'shareLevel',
  'shareConsistencyStreak',
  'shareWeeklyWorkoutCount',
  'shareHighlightedAchievements',
  'weekTimeZone',
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
    shareLevel?: boolean;
    shareConsistencyStreak?: boolean;
    shareWeeklyWorkoutCount?: boolean;
    shareHighlightedAchievements?: boolean;
    weekTimeZone?: string;
  } = {};

  if ('shareLevel' in object) {
    request.shareLevel = requireBoolean(object.shareLevel, 'shareLevel');
  }
  if ('shareConsistencyStreak' in object) {
    request.shareConsistencyStreak = requireBoolean(
      object.shareConsistencyStreak,
      'shareConsistencyStreak',
    );
  }
  if ('shareWeeklyWorkoutCount' in object) {
    request.shareWeeklyWorkoutCount = requireBoolean(
      object.shareWeeklyWorkoutCount,
      'shareWeeklyWorkoutCount',
    );
  }
  if ('shareHighlightedAchievements' in object) {
    request.shareHighlightedAchievements = requireBoolean(
      object.shareHighlightedAchievements,
      'shareHighlightedAchievements',
    );
  }
  if ('weekTimeZone' in object) {
    request.weekTimeZone = requireTimeZone(object.weekTimeZone);
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
