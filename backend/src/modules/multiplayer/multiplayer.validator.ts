import type { WorkoutTemplateShareSnapshotV1 } from '../social/workout-share.contract';
import {
  MULTIPLAYER_CLIENT_EVENT_TYPES,
  MULTIPLAYER_LIMITS,
  type CreateMultiplayerRoomRequest,
  type MultiplayerClientEventType,
  type PublishMultiplayerEventRequest,
  type PublishMultiplayerEventsRequest,
} from './multiplayer.contract';
import { MultiplayerErrors } from './multiplayer.errors';

/**
 * A validação da fronteira do multiplayer (T19.5).
 *
 * ## Allowlist, e recusa por nome
 *
 * Como em toda rota social: o que não está na lista recusa a requisição inteira. Aqui isso é o
 * próprio requisito de privacidade da T19.5 — o payload de um evento é gravado e devolvido ao
 * outro aparelho, e a única forma de garantir que peso, repetição, RPE, nota, PR e XP **não**
 * atravessam a rede é o servidor recusá-los na porta, e não confiar que o cliente não os manda.
 * [FORBIDDEN_PAYLOAD_KEYS] existe para que a recusa seja explícita mesmo se a allowlist um dia
 * crescer por descuido.
 *
 * ## O snapshot do treino é o da T17.7
 *
 * A sala carrega um `WorkoutTemplateShareSnapshotV1`: a mesma forma portável do compartilhamento
 * (só catálogo canônico, sem carga, sem nota). Os limites semânticos são os mesmos do
 * `WorkoutShareService` — repetidos aqui em vez de importados porque aquele módulo os mantém como
 * métodos privados de um caso de uso que não é este, e um acoplamento de import entre os dois
 * módulos custaria mais que dez linhas de validação.
 */

const REQUEST_FIELDS = ['clientRequestId', 'inviteeSocialId', 'workout'] as const;
const SNAPSHOT_FIELDS = ['snapshotVersion', 'name', 'shortIdentifier', 'exercises'] as const;
const EXERCISE_FIELDS = [
  'canonicalExerciseId',
  'sortOrder',
  'targetSets',
  'minReps',
  'maxReps',
  'restDurationSeconds',
] as const;
const EVENTS_REQUEST_FIELDS = ['events'] as const;
const EVENT_FIELDS = ['eventId', 'type', 'payload'] as const;

const PAYLOAD_FIELDS: Record<MultiplayerClientEventType, readonly string[]> = {
  WORKOUT_STARTED: ['exerciseCount'],
  SET_COMPLETED: [
    'canonicalExerciseId',
    'exercisePosition',
    'setNumber',
    'setCount',
    'completedAt',
  ],
  MEMBER_FINISHED: [],
};

/**
 * O que **nunca** entra num payload, em nenhum tipo — e é recusado por nome antes da allowlist.
 * A lista é a do §8 da T19.5 mais as grafias que um cliente distraído usaria.
 */
export const FORBIDDEN_PAYLOAD_KEYS = [
  'weight',
  'weightKg',
  'load',
  'reps',
  'repetitions',
  'rpe',
  'rir',
  'notes',
  'note',
  'pr',
  'personalRecord',
  'xp',
  'level',
  'streak',
  'measurement',
  'bodyWeight',
  'uid',
  'ownerUid',
  'firebaseUid',
  'email',
  'deviceId',
  'syncId',
  'localId',
  'sessionId',
] as const;

const EVENT_ID_PATTERN = /^[A-Za-z0-9._:-]{8,64}$/;
const CANONICAL_EXERCISE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const SOCIAL_ID_PATTERN = /^[A-Za-z0-9-]{8,64}$/;

export function assertMultiplayerBodyWithinLimit(rawBody: string | undefined): void {
  if (
    rawBody !== undefined &&
    Buffer.byteLength(rawBody, 'utf8') > MULTIPLAYER_LIMITS.maxRequestBodyBytes
  ) {
    throw MultiplayerErrors.invalid('o corpo da requisição excede o tamanho máximo');
  }
}

export function parseCreateRoomRequest(body: unknown): CreateMultiplayerRoomRequest {
  const object = requireObject(body, 'o corpo da requisição');
  rejectUnknownFields(object, REQUEST_FIELDS, 'no corpo');

  const clientRequestId = requireText(object.clientRequestId, 'clientRequestId', 128);
  const inviteeSocialId = requireText(object.inviteeSocialId, 'inviteeSocialId', 64);
  if (!SOCIAL_ID_PATTERN.test(inviteeSocialId)) {
    throw MultiplayerErrors.invalid('inviteeSocialId malformado');
  }

  const workout = parseWorkoutSnapshot(object.workout);
  return { clientRequestId, inviteeSocialId, workout };
}

/** O snapshot volta **por referência**: é o `JSON.stringify` dele que vira `workout_hash`. */
export function parseWorkoutSnapshot(value: unknown): WorkoutTemplateShareSnapshotV1 {
  const snapshot = requireObject(value, 'workout');
  rejectUnknownFields(snapshot, SNAPSHOT_FIELDS, 'no treino');
  if (snapshot.snapshotVersion !== 1) {
    throw MultiplayerErrors.invalid('versão do snapshot não suportada');
  }
  const name = requireText(snapshot.name, 'workout.name', 100);
  if (name.trim().length === 0) {
    throw MultiplayerErrors.invalid('workout.name é obrigatório');
  }
  if (
    snapshot.shortIdentifier !== undefined &&
    snapshot.shortIdentifier !== null &&
    (typeof snapshot.shortIdentifier !== 'string' || snapshot.shortIdentifier.length > 10)
  ) {
    throw MultiplayerErrors.invalid('workout.shortIdentifier inválido');
  }
  if (
    !Array.isArray(snapshot.exercises) ||
    snapshot.exercises.length === 0 ||
    snapshot.exercises.length > 30
  ) {
    throw MultiplayerErrors.invalid('o treino precisa ter entre 1 e 30 exercícios');
  }
  snapshot.exercises.forEach((raw, index) => {
    const exercise = requireObject(raw, `o exercício [${index}]`);
    rejectUnknownFields(exercise, EXERCISE_FIELDS, `no exercício [${index}]`);
    if (
      typeof exercise.canonicalExerciseId !== 'string' ||
      !CANONICAL_EXERCISE_ID_PATTERN.test(exercise.canonicalExerciseId)
    ) {
      throw MultiplayerErrors.invalid(`canonicalExerciseId inválido no exercício [${index}]`);
    }
    requireIntInRange(exercise.sortOrder, 0, 30, `sortOrder do exercício [${index}]`);
    requireIntInRange(exercise.targetSets, 1, 20, `targetSets do exercício [${index}]`);
    const minReps = requireIntInRange(exercise.minReps, 1, 100, `minReps do exercício [${index}]`);
    const maxReps = requireIntInRange(exercise.maxReps, 1, 100, `maxReps do exercício [${index}]`);
    if (minReps > maxReps) {
      throw MultiplayerErrors.invalid(`faixa de repetições inválida no exercício [${index}]`);
    }
    requireIntInRange(
      exercise.restDurationSeconds,
      0,
      600,
      `restDurationSeconds do exercício [${index}]`,
    );
  });
  const json = JSON.stringify(snapshot);
  if (Buffer.byteLength(json, 'utf8') > MULTIPLAYER_LIMITS.maxWorkoutSnapshotBytes) {
    throw MultiplayerErrors.invalid('o treino excede o tamanho máximo');
  }
  return snapshot as unknown as WorkoutTemplateShareSnapshotV1;
}

export function parsePublishEventsRequest(body: unknown): PublishMultiplayerEventsRequest {
  const object = requireObject(body, 'o corpo da requisição');
  rejectUnknownFields(object, EVENTS_REQUEST_FIELDS, 'no corpo');
  if (
    !Array.isArray(object.events) ||
    object.events.length === 0 ||
    object.events.length > MULTIPLAYER_LIMITS.maxEventsPerBatch
  ) {
    throw MultiplayerErrors.invalid(
      `events precisa ser uma lista com 1 a ${MULTIPLAYER_LIMITS.maxEventsPerBatch} itens`,
    );
  }
  const seen = new Set<string>();
  const events = object.events.map((raw, index) => {
    const event = parseEvent(raw, index);
    if (seen.has(event.eventId)) {
      throw MultiplayerErrors.invalid(`eventId repetido no lote [${index}]`);
    }
    seen.add(event.eventId);
    return event;
  });
  return { events };
}

function parseEvent(raw: unknown, index: number): PublishMultiplayerEventRequest {
  const event = requireObject(raw, `o evento [${index}]`);
  rejectUnknownFields(event, EVENT_FIELDS, `no evento [${index}]`);
  const eventId = requireText(event.eventId, `eventId do evento [${index}]`, 64);
  if (!EVENT_ID_PATTERN.test(eventId)) {
    throw MultiplayerErrors.invalid(`eventId malformado no evento [${index}]`);
  }
  const type = event.type;
  if (
    typeof type !== 'string' ||
    !(MULTIPLAYER_CLIENT_EVENT_TYPES as readonly string[]).includes(type)
  ) {
    throw MultiplayerErrors.invalid(`tipo de evento não suportado no evento [${index}]`);
  }
  const clientType = type as MultiplayerClientEventType;
  const payload = requireObject(event.payload ?? {}, `payload do evento [${index}]`);
  for (const key of FORBIDDEN_PAYLOAD_KEYS) {
    if (key in payload) {
      throw MultiplayerErrors.invalid(`campo proibido no payload do evento [${index}]: ${key}`);
    }
  }
  rejectUnknownFields(payload, PAYLOAD_FIELDS[clientType], `no payload do evento [${index}]`);
  validatePayload(clientType, payload, index);
  if (Buffer.byteLength(JSON.stringify(payload), 'utf8') > MULTIPLAYER_LIMITS.maxPayloadBytes) {
    throw MultiplayerErrors.invalid(`payload do evento [${index}] excede o tamanho máximo`);
  }
  return { eventId, type: clientType, payload };
}

function validatePayload(
  type: MultiplayerClientEventType,
  payload: Record<string, unknown>,
  index: number,
): void {
  switch (type) {
    case 'WORKOUT_STARTED':
      requireIntInRange(payload.exerciseCount, 1, 30, `exerciseCount do evento [${index}]`);
      return;
    case 'SET_COMPLETED': {
      if (
        payload.canonicalExerciseId !== null &&
        (typeof payload.canonicalExerciseId !== 'string' ||
          !CANONICAL_EXERCISE_ID_PATTERN.test(payload.canonicalExerciseId))
      ) {
        throw MultiplayerErrors.invalid(`canonicalExerciseId inválido no evento [${index}]`);
      }
      requireIntInRange(payload.exercisePosition, 1, 60, `exercisePosition do evento [${index}]`);
      const setCount = requireIntInRange(payload.setCount, 1, 60, `setCount do evento [${index}]`);
      const setNumber = requireIntInRange(
        payload.setNumber,
        1,
        60,
        `setNumber do evento [${index}]`,
      );
      if (setNumber > setCount) {
        throw MultiplayerErrors.invalid(`setNumber maior que setCount no evento [${index}]`);
      }
      requireIntInRange(
        payload.completedAt,
        0,
        Number.MAX_SAFE_INTEGER,
        `completedAt do evento [${index}]`,
      );
      return;
    }
    case 'MEMBER_FINISHED':
      return;
  }
}

function requireObject(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw MultiplayerErrors.invalid(`${label} precisa ser um objeto JSON`);
  }
  return value as Record<string, unknown>;
}

function requireText(value: unknown, field: string, maxLength: number): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > maxLength) {
    throw MultiplayerErrors.invalid(
      `${field} é obrigatório, texto, com até ${maxLength} caracteres`,
    );
  }
  return value;
}

function requireIntInRange(value: unknown, min: number, max: number, label: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
    throw MultiplayerErrors.invalid(`${label} precisa ser um inteiro entre ${min} e ${max}`);
  }
  return value;
}

function rejectUnknownFields(
  object: Record<string, unknown>,
  allowed: readonly string[],
  where: string,
): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw MultiplayerErrors.invalid(`campo não reconhecido ${where}: ${key}`);
    }
  }
}

export function parseAfterCursor(value: unknown): number {
  if (value === undefined || value === '') return 0;
  const parsed = typeof value === 'string' ? Number(value) : Number.NaN;
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw MultiplayerErrors.invalid('after precisa ser um inteiro não negativo');
  }
  return parsed;
}

export function parseWaitMs(value: unknown): number {
  if (value === undefined || value === '') return 0;
  const parsed = typeof value === 'string' ? Number(value) : Number.NaN;
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw MultiplayerErrors.invalid('wait precisa ser um inteiro não negativo (ms)');
  }
  return Math.min(parsed, MULTIPLAYER_LIMITS.maxLongPollWaitMs);
}
