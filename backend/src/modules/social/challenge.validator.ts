import { createHash } from 'node:crypto';
import type { ChallengeType } from './challenge.contract';
import { CHALLENGE_TYPES } from './challenge.contract';
import { ChallengeErrors } from './challenge.errors';
import {
  CHALLENGE_DURATION_DAYS,
  CHALLENGE_LIST_PAGE,
  CHALLENGE_NAME,
  CHALLENGE_PARTICIPANTS,
  CHALLENGE_TARGET,
  MAX_CHALLENGE_REQUEST_BODY_BYTES,
  MAX_CHALLENGE_TIME_ZONE_LENGTH,
} from './challenge.limits';
import {
  calendarDateAsUtc,
  DAY_MS,
  formatCalendarDate,
  isValidTimeZone,
  localCalendarDate,
  localMidnightToInstant,
  parseCalendarDate,
} from './social-time';
import type { ListCursor } from './friendship.repository';

/**
 * A validação das requisições de desafio (T17.3).
 *
 * Mesma fronteira de `friendship.validator.ts`, com um acréscimo que é o coração da tarefa:
 *
 * ```text
 * T17.1   socialId no corpo   ──▶ ACEITO     (referência a outra pessoa)
 * T17.2   level no corpo      ──▶ RECUSADO   (o cliente afirmando progresso sobre si)
 * T17.3   score no corpo      ──▶ RECUSADO   (o cliente afirmando pontuação)
 *         progress, rank,
 *         winner, points      ──▶ RECUSADO
 * ```
 *
 * A recusa é **por nome** e invalida a requisição inteira. Não é filtragem silenciosa: um corpo
 * que carrega `progress: 8` descreve um cliente que acredita ser autoridade de pontuação, e
 * aceitar o resto do corpo dele seria concordar em parte. O bloqueante da tarefa é literal —
 * "Android envia progress e servidor confia" é FAIL —, e a forma de garantir que isso nunca
 * aconteça é o servidor não ter por onde ler o campo.
 */

/**
 * Campos que descrevem **quem é o chamador** ou **qual é a pontuação dele**.
 *
 * Os primeiros saem do token; os segundos são derivados na leitura. Nenhum dos dois grupos vem do
 * corpo, e um deles presente recusa a requisição inteira.
 */
const SERVER_OWNED_FIELDS = [
  // Identidade do chamador (herdado de T17.0/T17.1).
  'ownerUid',
  'owner_uid',
  'uid',
  'creatorUid',
  'creator_uid',
  'firebaseUid',
  'email',
  'participantUids',
  'participant_uids',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  // Ciclo de vida e janela: derivados no servidor (§13/§26/§27).
  'status',
  'lifecycle',
  'startsAt',
  'starts_at',
  'endsAt',
  'endsAtExclusive',
  'ends_at_exclusive',
  'cancelledAt',
  'cancelled_at',
  // Pontuação: derivada na leitura, nunca afirmada pelo cliente (§bloqueantes).
  'score',
  'progress',
  'currentScore',
  'current_score',
  'points',
  'count',
  'rank',
  'ranking',
  'winner',
  'goalReached',
  'goal_reached',
  'leaderboard',
  'participants',
] as const;

const MAX_IDENTIFIER_LENGTH = 128;
const MAX_CURSOR_LENGTH = 256;

/**
 * Recusa um corpo grande **antes** de qualquer validação de conteúdo.
 *
 * Mede em bytes UTF-8, e não em caracteres: o teto protege memória e parsing, e um nome com
 * acentos ocupa mais bytes do que caracteres. Mesma forma de `assertBodyWithinLimit` (T17.0).
 */
export function assertChallengeBodyWithinLimit(rawBody: string | undefined): void {
  if (
    rawBody !== undefined &&
    Buffer.byteLength(rawBody, 'utf8') > MAX_CHALLENGE_REQUEST_BODY_BYTES
  ) {
    throw ChallengeErrors.invalid('o corpo da requisição excede o tamanho máximo');
  }
}

// --------------------------------------------------------------------------------- criação

/** Uma criação já validada — e com a janela em instantes já derivada no servidor (§13). */
export interface CreateChallengeRequest {
  readonly clientRequestId: string;
  readonly name: string;
  readonly type: ChallengeType;
  readonly target: number;
  readonly startDate: string;
  readonly endDate: string;
  readonly timeZoneId: string;
  readonly startsAt: number;
  readonly endsAtExclusive: number;
  /** Normalizados: sem duplicatas, em ordem estável (§43). */
  readonly invitedSocialIds: readonly string[];
  /** SHA-256 da forma canônica, para o ledger de idempotência (§189/§190). */
  readonly requestHash: string;
}

/**
 * `POST /v1/social/challenges`.
 *
 * @param nowMs relógio do **servidor**. É contra ele que "começa pelo menos amanhã" é decidido —
 * nunca contra um instante que o cliente tenha mandado, porque não há nenhum (§14).
 */
export function parseCreateChallengeRequest(body: unknown, nowMs: number): CreateChallengeRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, [
    'clientRequestId',
    'name',
    'type',
    'target',
    'startDate',
    'endDate',
    'timeZoneId',
    'invitedSocialIds',
  ]);

  const clientRequestId = requireIdentifier(object.clientRequestId, 'clientRequestId');
  const name = requireName(object.name);
  const type = requireType(object.type);
  const timeZoneId = requireTimeZone(object.timeZoneId);
  const period = requirePeriod(object.startDate, object.endDate, timeZoneId, nowMs);
  const target = requireTarget(object.target, type, period.durationDays);
  const invitedSocialIds = requireInvitedSocialIds(object.invitedSocialIds);

  return {
    clientRequestId,
    name,
    type,
    target,
    startDate: period.startDate,
    endDate: period.endDate,
    timeZoneId,
    startsAt: period.startsAt,
    endsAtExclusive: period.endsAtExclusive,
    invitedSocialIds,
    // O hash cobre **as regras**, e não o `clientRequestId`: a pergunta que ele responde é "este
    // reenvio pede a mesma coisa?", e o identificador da tentativa já é a chave. Incluí-lo faria
    // todo pedido ter hash diferente, e o conflito de §190 nunca seria detectado.
    requestHash: hashOf({
      name,
      type,
      target,
      startDate: period.startDate,
      endDate: period.endDate,
      timeZoneId,
      invitedSocialIds,
    }),
  };
}

/**
 * O nome (§22).
 *
 * `trim` antes de medir, Unicode permitido, controle recusado. A contagem é em code points, como
 * em `social.validator.ts`: contar unidades UTF-16 faria o limite significar coisas diferentes
 * dependendo do alfabeto.
 */
function requireName(raw: unknown): string {
  if (typeof raw !== 'string') {
    throw ChallengeErrors.invalid('name é obrigatório e precisa ser texto');
  }
  const trimmed = raw.trim();
  const length = [...trimmed].length;

  if (length < CHALLENGE_NAME.minLength) {
    throw ChallengeErrors.invalid('o nome do desafio é curto demais');
  }
  if (length > CHALLENGE_NAME.maxLength) {
    throw ChallengeErrors.invalid('o nome do desafio é longo demais');
  }
  // Controle, e não alfabeto: quebra de linha e tab transformam um cartão de lista em três, e são
  // o vetor mais barato de falsificar layout numa tela que outras pessoas leem.
  if (/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(trimmed)) {
    throw ChallengeErrors.invalid('o nome do desafio contém caracteres não permitidos');
  }
  return trimmed;
}

function requireType(raw: unknown): ChallengeType {
  if (typeof raw !== 'string' || !CHALLENGE_TYPES.includes(raw as ChallengeType)) {
    throw ChallengeErrors.invalidType();
  }
  return raw as ChallengeType;
}

function requireTimeZone(raw: unknown): string {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > MAX_CHALLENGE_TIME_ZONE_LENGTH) {
    throw ChallengeErrors.invalidTimeZone();
  }
  // A validação de verdade é contra o runtime (§10): o ICU sabe quais fusos existem, e uma lista
  // mantida à mão envelheceria a cada revisão do banco de fusos.
  if (!isValidTimeZone(raw)) {
    throw ChallengeErrors.invalidTimeZone();
  }
  return raw;
}

interface ParsedPeriod {
  readonly startDate: string;
  readonly endDate: string;
  readonly startsAt: number;
  readonly endsAtExclusive: number;
  readonly durationDays: number;
}

/**
 * O período, e a conversão para instantes (§12/§13).
 *
 * ```text
 * startDate 2026-09-10  ─┐
 * endDate   2026-10-09  ─┼─▶ [meia-noite local de 10/09, meia-noite local de 10/10)
 * timeZone  America/... ─┘
 * ```
 *
 * O fim é a meia-noite local do dia **seguinte** ao `endDate`, e não `startsAt + N×24h` (§203):
 * num período com virada de horário de verão as duas diferem em uma hora, e a diferença
 * apareceria como um treino da última noite ficando de fora.
 *
 * ## "Pelo menos amanhã" (§15/§16)
 *
 * O início precisa ser, no mínimo, o **próximo dia de calendário** no fuso escolhido. É o que
 * garante a ordem `criação → convites → aceites → janela`, sem ninguém entrando horas depois e
 * sem ter de decidir se um treino anterior conta. A comparação é entre datas locais, e não entre
 * instantes: quem cria às 23h50 em São Paulo escolhendo "amanhã" está escolhendo dez minutos
 * adiante, e isso é legítimo — o que ele não pode escolher é "hoje".
 */
function requirePeriod(
  rawStart: unknown,
  rawEnd: unknown,
  timeZoneId: string,
  nowMs: number,
): ParsedPeriod {
  if (typeof rawStart !== 'string' || typeof rawEnd !== 'string') {
    throw ChallengeErrors.invalidPeriod('startDate e endDate são obrigatórios');
  }
  const start = parseCalendarDate(rawStart);
  const end = parseCalendarDate(rawEnd);
  if (!start || !end) {
    throw ChallengeErrors.invalidPeriod('as datas precisam estar no formato AAAA-MM-DD');
  }

  const startAsUtc = calendarDateAsUtc(start);
  const endAsUtc = calendarDateAsUtc(end);

  if (endAsUtc < startAsUtc) {
    throw ChallengeErrors.invalidPeriod('a data final não pode ser anterior à inicial');
  }

  // Dias inclusivos: 10/09 a 10/09 é um dia. A divisão é exata porque os dois lados são
  // `Date.UTC` de datas — aritmética de calendário sem fuso, e portanto sem horário de verão.
  const durationDays = (endAsUtc - startAsUtc) / DAY_MS + 1;
  if (durationDays < CHALLENGE_DURATION_DAYS.min) {
    throw ChallengeErrors.invalidPeriod('o desafio precisa durar pelo menos um dia');
  }
  if (durationDays > CHALLENGE_DURATION_DAYS.max) {
    throw ChallengeErrors.invalidPeriod(
      `o desafio não pode durar mais de ${CHALLENGE_DURATION_DAYS.max} dias`,
    );
  }

  // "Hoje" só existe dentro de um fuso, e o fuso que vale é o do desafio.
  const todayAsUtc = localCalendarDate(nowMs, timeZoneId);
  if (startAsUtc <= todayAsUtc) {
    throw ChallengeErrors.invalidPeriod('o desafio precisa começar a partir do dia seguinte');
  }

  return {
    startDate: formatCalendarDate(startAsUtc),
    endDate: formatCalendarDate(endAsUtc),
    startsAt: localMidnightToInstant(startAsUtc, timeZoneId),
    // A meia-noite local do dia seguinte ao último dia: `[início, fim)` fecha o último dia inteiro.
    endsAtExclusive: localMidnightToInstant(endAsUtc + DAY_MS, timeZoneId),
    durationDays,
  };
}

/**
 * A meta (§19–§21).
 *
 * `ACTIVE_DAYS` é limitado pela **duração**: pedir 40 dias ativos num desafio de 30 dias é uma
 * meta que ninguém pode alcançar, e aceitar isso produziria um desafio em que todo mundo perde
 * por construção. O teto de `WORKOUTS_COMPLETED` é um número absoluto porque vários treinos cabem
 * no mesmo dia.
 */
function requireTarget(raw: unknown, type: ChallengeType, durationDays: number): number {
  if (typeof raw !== 'number' || !Number.isInteger(raw)) {
    throw ChallengeErrors.invalidTarget('a meta precisa ser um número inteiro');
  }
  if (raw < CHALLENGE_TARGET.min) {
    throw ChallengeErrors.invalidTarget('a meta precisa ser positiva');
  }

  const max = type === 'ACTIVE_DAYS' ? durationDays : CHALLENGE_TARGET.maxWorkoutsCompleted;
  if (raw > max) {
    throw ChallengeErrors.invalidTarget(
      type === 'ACTIVE_DAYS'
        ? 'a meta não pode ser maior que o número de dias do desafio'
        : 'a meta excede o máximo permitido',
    );
  }
  return raw;
}

/**
 * Os convidados (§30/§43).
 *
 * Duplicata é **normalizada**, não recusada: `[B, B]` significa, sem ambiguidade, "convide o B", e
 * transformar isso em erro faria uma lista montada por uma UI com um toque repetido virar uma
 * falha que o usuário não sabe corrigir. A `UNIQUE (challenge_id, recipient_uid)` do banco é a
 * segunda garantia, para o caso de esta normalização um dia falhar.
 *
 * A ordem original é preservada: a normalização é determinística, e é a mesma que entra no hash de
 * idempotência — reordenar faria dois reenvios idênticos parecerem pedidos diferentes.
 */
function requireInvitedSocialIds(raw: unknown): readonly string[] {
  if (!Array.isArray(raw)) {
    throw ChallengeErrors.invalid('invitedSocialIds é obrigatório e precisa ser uma lista');
  }

  const seen = new Set<string>();
  const normalized: string[] = [];
  for (const entry of raw) {
    const socialId = requireIdentifier(entry, 'invitedSocialIds');
    if (!seen.has(socialId)) {
      seen.add(socialId);
      normalized.push(socialId);
    }
  }

  // O criador entra automaticamente (§31), então ele ocupa uma das vagas. O teto compara o total.
  if (normalized.length + 1 > CHALLENGE_PARTICIPANTS.max) {
    throw ChallengeErrors.tooManyParticipants();
  }
  return normalized;
}

// --------------------------------------------------------------------------------- comuns

/** O `challengeId`/`invitationId` de um caminho de rota. */
export function parseChallengeId(raw: unknown): string {
  return requireIdentifier(raw, 'challengeId');
}

export function parseInvitationId(raw: unknown): string {
  return requireIdentifier(raw, 'invitationId');
}

export interface ChallengeListQuery {
  readonly limit: number;
  readonly cursor: ListCursor | null;
}

/**
 * `?limit=&cursor=`.
 *
 * Mesma política de `parseListQuery` da T17.1: ausente vira default, acima do teto é cortado em
 * silêncio (quem pede 500 quer a lista), e fora de forma é recusado — isso é defeito de cliente.
 */
export function parseChallengeListQuery(rawLimit: unknown, rawCursor: unknown): ChallengeListQuery {
  let limit: number = CHALLENGE_LIST_PAGE.defaultLimit;

  if (rawLimit !== undefined && rawLimit !== '') {
    const parsed = Number(rawLimit);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw ChallengeErrors.invalid('limit precisa ser um inteiro positivo');
    }
    limit = Math.min(parsed, CHALLENGE_LIST_PAGE.maxLimit);
  }

  return { limit, cursor: parseChallengeCursor(rawCursor) };
}

/** O cursor opaco, base64url — mesma forma da T17.1, e pelo mesmo motivo. */
export function encodeChallengeCursor(cursor: ListCursor): string {
  return Buffer.from(JSON.stringify([cursor.primary, cursor.secondary]), 'utf8').toString(
    'base64url',
  );
}

function parseChallengeCursor(raw: unknown): ListCursor | null {
  if (raw === undefined || raw === '') {
    return null;
  }
  if (typeof raw !== 'string' || raw.length > MAX_CURSOR_LENGTH) {
    throw ChallengeErrors.invalid('cursor inválido');
  }

  let decoded: unknown;
  try {
    decoded = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
  } catch {
    throw ChallengeErrors.invalid('cursor inválido');
  }

  if (
    !Array.isArray(decoded) ||
    decoded.length !== 2 ||
    (typeof decoded[0] !== 'string' && typeof decoded[0] !== 'number') ||
    typeof decoded[1] !== 'string'
  ) {
    throw ChallengeErrors.invalid('cursor inválido');
  }
  return { primary: decoded[0], secondary: decoded[1] };
}

// --------------------------------------------------------------------------------- utilidades

function requireObject(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw ChallengeErrors.invalid('o corpo da requisição precisa ser um objeto JSON');
  }
  return body as Record<string, unknown>;
}

/**
 * Recusa qualquer campo que o servidor é quem decide.
 *
 * A recusa é da **requisição inteira**, e não do campo. Um corpo com `score: 8` não é um corpo
 * bom com um campo a mais: é um cliente que acredita ser autoridade de pontuação, e atender o
 * resto do pedido dele seria concordar com metade dessa crença.
 */
function rejectServerOwnedFields(object: Record<string, unknown>): void {
  for (const field of SERVER_OWNED_FIELDS) {
    if (field in object) {
      throw ChallengeErrors.invalid(
        `${field} é definido pelo servidor e não pode ser enviado na requisição`,
      );
    }
  }
}

function rejectUnknownFields(object: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw ChallengeErrors.invalid(`campo não reconhecido no corpo da requisição: ${key}`);
    }
  }
}

function requireIdentifier(raw: unknown, field: string): string {
  if (typeof raw !== 'string') {
    throw ChallengeErrors.invalid(`${field} é obrigatório e precisa ser texto`);
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_IDENTIFIER_LENGTH) {
    throw ChallengeErrors.invalid(`${field} tem tamanho inválido`);
  }
  return trimmed;
}

/**
 * O hash canônico das regras de uma criação.
 *
 * `JSON.stringify` sobre um objeto montado **nesta** ordem — e não sobre o corpo recebido, cuja
 * ordem de chaves depende de como o cliente serializou. Dois reenvios idênticos precisam produzir
 * o mesmo hash, ou o retry seguro de §189 viraria o conflito de §190.
 */
function hashOf(canonical: unknown): string {
  return createHash('sha256').update(JSON.stringify(canonical), 'utf8').digest('hex');
}
