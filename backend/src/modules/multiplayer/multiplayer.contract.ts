import type { WorkoutTemplateShareSnapshotV1 } from '../social/workout-share.contract';

/**
 * Contrato do multiplayer remoto (T19.5): duas pessoas, dois aparelhos, o mesmo treino.
 *
 * ## O que a sala é — e o que ela não é
 *
 * ```text
 * MultiplayerRoom   ≠   WorkoutSession
 * backend           ≠   autoridade do Workout
 * ```
 *
 * A sala coordena: quem está nela, quem é o host, quem está conectado, o que aconteceu e em que
 * ordem. Cada aparelho continua dono da própria `WorkoutSession`, das próprias séries, dos
 * próprios pesos, do próprio PR e do próprio XP — nada disso chega aqui, e o validador recusa o
 * que tentar chegar. Se o servidor sumir no meio do treino, os dois treinos locais continuam
 * íntegros; é exatamente o critério de correção da T19.5.
 *
 * ## Identidade na fronteira
 *
 * Membros são `socialId` + `displayName` (T17.0). Firebase UID, e-mail, `friendCode`, `deviceId`
 * e `syncId` não aparecem em DTO nenhum — há teste que varre as respostas reais.
 *
 * ## Transporte
 *
 * HTTP autenticado, o mesmo de todo o resto: criar, entrar, sair, publicar eventos e um
 * `GET .../events?after=<seq>&wait=<ms>` que segura a resposta até haver novidade (long-polling,
 * teto de [MULTIPLAYER_LIMITS.maxLongPollWaitMs]). Não existe WebSocket nem push: o PostgreSQL já
 * é o ponto único de coordenação, e um long-poll funciona igual com uma ou dez instâncias do
 * Cloud Run. Presença é o último poll — nunca um heartbeat separado.
 */

export const MULTIPLAYER_ROOM_STATUSES = ['WAITING', 'ACTIVE', 'CLOSED', 'EXPIRED'] as const;
export type MultiplayerRoomStatus = (typeof MULTIPLAYER_ROOM_STATUSES)[number];

export const MULTIPLAYER_MEMBER_ROLES = ['HOST', 'GUEST'] as const;
export type MultiplayerMemberRole = (typeof MULTIPLAYER_MEMBER_ROLES)[number];

export const MULTIPLAYER_MEMBER_STATUSES = ['INVITED', 'ACTIVE', 'FINISHED', 'LEFT'] as const;
export type MultiplayerMemberStatus = (typeof MULTIPLAYER_MEMBER_STATUSES)[number];

export const MULTIPLAYER_CLOSE_REASONS = [
  'HOST_CLOSED',
  'ALL_LEFT',
  'INVITE_DECLINED',
  'HOST_LEFT_WAITING',
  'SUPERSEDED',
  'EXPIRED',
  'UNAVAILABLE',
] as const;
export type MultiplayerCloseReason = (typeof MULTIPLAYER_CLOSE_REASONS)[number];

/**
 * Os tipos de evento — poucos de propósito.
 *
 * Os três primeiros são publicados pelo aparelho; os três últimos nascem no servidor, na mesma
 * transação da transição de membership/sala que eles descrevem, para que o log de uma sala conte
 * a história inteira em uma única ordem.
 */
export const MULTIPLAYER_CLIENT_EVENT_TYPES = [
  'WORKOUT_STARTED',
  'SET_COMPLETED',
  'MEMBER_FINISHED',
] as const;
export const MULTIPLAYER_SERVER_EVENT_TYPES = [
  'MEMBER_JOINED',
  'MEMBER_LEFT',
  'ROOM_CLOSED',
] as const;
export const MULTIPLAYER_EVENT_TYPES = [
  ...MULTIPLAYER_CLIENT_EVENT_TYPES,
  ...MULTIPLAYER_SERVER_EVENT_TYPES,
] as const;
export type MultiplayerClientEventType = (typeof MULTIPLAYER_CLIENT_EVENT_TYPES)[number];
export type MultiplayerEventType = (typeof MULTIPLAYER_EVENT_TYPES)[number];

export const MULTIPLAYER_LIMITS = {
  /** Uma sala WAITING sem ninguém entrar expira. */
  waitingTtlMs: 30 * 60 * 1000,
  /** Teto absoluto de vida de uma sala, a partir da criação. */
  activeTtlMs: 6 * 60 * 60 * 1000,
  /** Um membro sem poll há mais que isto é "desconectado" — derivado, nunca gravado. */
  presenceTtlMs: 45 * 1000,
  /** O long-poll nunca segura mais que isto; o cliente pede menos que o `readTimeout` dele. */
  maxLongPollWaitMs: 20_000,
  /** Intervalo com que o long-poll relê o banco enquanto espera. */
  longPollIntervalMs: 1_000,
  /** Eventos por `POST` — o reenvio integral de um treino cabe em uma requisição. */
  maxEventsPerBatch: 50,
  /** Eventos por `GET` — acima disto o cliente pagina pelo cursor. */
  maxEventsPerPage: 200,
  /** Teto de eventos por sala: 30 exercícios × 20 séries × 2 membros, com folga. */
  maxEventsPerRoom: 2_000,
  maxPayloadBytes: 1_024,
  maxWorkoutSnapshotBytes: 64 * 1024,
  maxRequestBodyBytes: 96 * 1024,
  /** Salas criadas por host por dia. */
  maxRoomsPerDay: 30,
} as const;

export const MultiplayerErrorCodes = {
  INVALID_REQUEST: 'MULTIPLAYER_INVALID_REQUEST',
  SOCIAL_NOT_ENABLED: 'SOCIAL_NOT_ENABLED',
  FRIENDSHIP_REQUIRED: 'FRIENDSHIP_REQUIRED',
  CANNOT_INVITE_SELF: 'MULTIPLAYER_CANNOT_INVITE_SELF',
  ROOM_NOT_FOUND: 'MULTIPLAYER_ROOM_NOT_FOUND',
  ROOM_CLOSED: 'MULTIPLAYER_ROOM_CLOSED',
  ROOM_EXPIRED: 'MULTIPLAYER_ROOM_EXPIRED',
  NOT_A_MEMBER: 'MULTIPLAYER_NOT_A_MEMBER',
  MEMBER_LEFT: 'MULTIPLAYER_MEMBER_LEFT',
  NOT_HOST: 'MULTIPLAYER_NOT_HOST',
  CONFLICT: 'MULTIPLAYER_CONFLICT',
  EVENT_LIMIT: 'MULTIPLAYER_EVENT_LIMIT',
  RATE_LIMITED: 'MULTIPLAYER_RATE_LIMITED',
} as const;

// ----------------------------------------------------------------------------- requests

export interface CreateMultiplayerRoomRequest {
  readonly clientRequestId: string;
  readonly inviteeSocialId: string;
  readonly workout: WorkoutTemplateShareSnapshotV1;
}

/**
 * Um evento como o aparelho o publica. `eventId` é do cliente — global, estável e determinístico
 * por (sala, fato) — e é o que faz reenvio virar dedupe em vez de duplicata.
 */
export interface PublishMultiplayerEventRequest {
  readonly eventId: string;
  readonly type: MultiplayerClientEventType;
  readonly payload: Record<string, unknown>;
}

export interface PublishMultiplayerEventsRequest {
  readonly events: readonly PublishMultiplayerEventRequest[];
}

// ----------------------------------------------------------------------------- payloads

/** `SET_COMPLETED` — o que atravessa a rede quando uma série termina. Sem peso, sem reps. */
export interface SetCompletedPayload {
  readonly canonicalExerciseId: string | null;
  /** A posição do exercício na execução, 1-based. */
  readonly exercisePosition: number;
  /** A série concluída, 1-based, e quantas o exercício tem. */
  readonly setNumber: number;
  readonly setCount: number;
  /** Relógio do aparelho, informativo: nunca ordena nada. */
  readonly completedAt: number;
}

export interface WorkoutStartedPayload {
  readonly exerciseCount: number;
}

// ----------------------------------------------------------------------------- responses

export interface MultiplayerMemberDto {
  readonly socialId: string;
  readonly displayName: string;
  readonly role: MultiplayerMemberRole;
  readonly status: MultiplayerMemberStatus;
  /** Derivado de `last_seen_at` no instante da resposta. */
  readonly connected: boolean;
  readonly lastSeenAt: number | null;
  readonly joinedAt: number | null;
}

export interface MultiplayerRoomDto {
  readonly roomId: string;
  readonly status: MultiplayerRoomStatus;
  readonly closeReason: MultiplayerCloseReason | null;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly expiresAt: number;
  readonly hostSocialId: string;
  /** O papel e o estado de quem está perguntando. */
  readonly me: { readonly role: MultiplayerMemberRole; readonly status: MultiplayerMemberStatus };
  readonly members: readonly MultiplayerMemberDto[];
  readonly workout: WorkoutTemplateShareSnapshotV1;
  /** A última sequence atribuída nesta sala (0 = nenhum evento ainda). */
  readonly lastSequence: number;
}

export interface MultiplayerEventDto {
  readonly eventId: string;
  readonly sequence: number;
  readonly actorSocialId: string;
  readonly type: MultiplayerEventType;
  readonly payload: Record<string, unknown>;
  readonly createdAt: number;
}

export interface MultiplayerEventsPageDto {
  readonly room: MultiplayerRoomDto;
  readonly events: readonly MultiplayerEventDto[];
  /** A sequence do último evento devolvido — ou o `after` pedido, se não veio nenhum. */
  readonly cursor: number;
  /** Há mais eventos além do teto da página: o cliente pede de novo com `after = cursor`. */
  readonly hasMore: boolean;
}

export interface PublishMultiplayerEventsResponse {
  readonly accepted: readonly { readonly eventId: string; readonly sequence: number }[];
  readonly room: MultiplayerRoomDto;
}

export interface MultiplayerInvitationDto {
  readonly roomId: string;
  readonly createdAt: number;
  readonly expiresAt: number;
  readonly host: { readonly socialId: string; readonly displayName: string };
  readonly workoutName: string;
  readonly exerciseCount: number;
}
