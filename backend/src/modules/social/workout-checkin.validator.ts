import {
  REACTION_TYPES,
  type CreateWorkoutCheckInRequest,
  type ReactionType,
} from './workout-checkin.contract';
import { WorkoutCheckInErrors } from './workout-checkin.errors';
import {
  MAX_CHECKIN_IDENTIFIER_LENGTH,
  MAX_CHECKIN_REQUEST_BODY_BYTES,
} from './workout-checkin.limits';
import { parseCaption, parseCommentBody } from './social-content.validator';

/**
 * A validação do corpo de criação de check-in (T17.8 §12/§14).
 *
 * ## O envelope é estrito, e a recusa é por nome
 *
 * O corpo só pode conter `sessionSyncId` e `clientRequestId`. Qualquer outro campo recusa a
 * requisição **inteira** em vez de ser ignorado — a mesma regra que a T17.0 aplica a `ownerUid` e
 * a T17.2 a `level`. Ignorar em silêncio é pior: um cliente que envia `completed: true` acredita
 * que aquilo significa alguma coisa, e alguma versão futura do servidor acabaria "aproveitando"
 * um campo que nunca deveria ter existido.
 *
 * `completed` está na lista por escrito, e não só por ser desconhecido, porque ele é **a**
 * declaração que a T17.8 proíbe (§11/§169): o servidor não aceita "eu concluí" vindo do aparelho.
 * Quem responde se a sessão está concluída é a fonte canônica, lendo o estado sincronizado.
 */
const SERVER_OWNED_FIELDS = [
  // O dono sai do token (§14).
  'ownerUid',
  'owner_uid',
  'authorUid',
  'author_uid',
  'uid',
  'firebaseUid',
  'socialId',
  'social_id',
  'friendCode',
  'friend_code',
  'email',
  // O estado da sessão é derivado do domínio canônico, nunca declarado (§11).
  'completed',
  'status',
  'sessionStatus',
  'finishedAt',
  'finished_at',
  'startedAt',
  'started_at',
  // A identidade e o instante da publicação são do servidor (§36/§49).
  'checkInId',
  'checkin_id',
  'id',
  'publishedAt',
  'published_at',
  'createdAt',
  'created_at',
  'deletedAt',
  'deleted_at',
  // A T17.9 abriu **duas** portas de conteúdo, e só duas: `caption` e `mediaId` — que saíram
  // desta lista e entraram em `ALLOWED_FIELDS`, com sanitização (§9) e pipeline de imagem (§15)
  // atrás de cada uma.
  //
  // O resto continua recusado **por nome**, e a lista é a fronteira escrita: `description`,
  // `note`, `notes` e `message` são os campos que alguém acrescentaria "porque já existe legenda"
  // — e cada um seria um segundo texto livre sem limite próprio nem lugar na tela. `photoUrl`,
  // `imageUrl` e `mediaUrl` seriam o cliente **propondo onde a imagem mora**, que é exatamente o
  // que §24 e §25 impedem: a chave de armazenamento nasce no servidor. `video` e `videoUrl` estão
  // fora de escopo (§3), e recusá-los agora é o que evita que entrem sem pipeline.
  'description',
  'note',
  'notes',
  'message',
  'text',
  'photo',
  'photoUrl',
  'imageUrl',
  'mediaUrl',
  'video',
  'videoUrl',
  'storageKey',
  'storage_key',
  'contentHash',
  'reactions',
  'reactionCount',
  'commentCount',
  'comments',
  'likes',
  // A audiência não é por post nesta fase (§53).
  'audience',
  'visibility',
  'recipients',
  'recipientSocialIds',
] as const;

const ALLOWED_FIELDS = ['sessionSyncId', 'clientRequestId', 'caption', 'mediaId'] as const;

/**
 * `POST /v1/social/workout-checkins` — o corpo inteiro (§12; T17.9 §7/§33).
 *
 * Dois identificadores obrigatórios, uma legenda opcional e um `mediaId` opcional. A legenda passa
 * pela sanitização compartilhada (`social-content.validator.ts`), e o `mediaId` é só um
 * identificador aqui: quem decide se ele pode ser anexado é o `UPDATE` condicional do banco, que
 * confere dono, sessão e estado (T17.9 §34/§35/§148).
 */
export function parseCreateCheckInRequest(body: unknown): CreateWorkoutCheckInRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ALLOWED_FIELDS);

  return {
    sessionSyncId: requireIdentifier(object.sessionSyncId, 'sessionSyncId'),
    clientRequestId: requireIdentifier(object.clientRequestId, 'clientRequestId'),
    caption: parseCaption(object.caption),
    mediaId:
      object.mediaId === undefined || object.mediaId === null
        ? null
        : requireIdentifier(object.mediaId, 'mediaId'),
  };
}

/**
 * `PUT /v1/social/workout-checkins/{id}/reaction` (T17.9 §62/§66).
 *
 * O enum é **fechado**, e a recusa acontece aqui antes de qualquer escrita. Não existe caminho em
 * que o cliente envie um emoji: `"🔥"` no lugar de `"FIRE"` é `INVALID_REACTION`, porque aceitar
 * um caractere arbitrário seria aceitar conteúdo livre de terceiros na publicação de alguém — sem
 * limite, sem sanitização e sem alvo de denúncia.
 */
export function parseReactionRequest(body: unknown): ReactionType {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['type']);

  const type = object.type;
  if (typeof type !== 'string' || !(REACTION_TYPES as readonly string[]).includes(type)) {
    throw WorkoutCheckInErrors.invalidReaction();
  }
  return type as ReactionType;
}

/** `POST /v1/social/workout-checkins/{id}/comments` — um campo (T17.9 §81). */
export function parseCommentRequest(body: unknown): string {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['body']);
  return parseCommentBody(object.body);
}

/**
 * `POST /v1/social/checkin-media?sessionSyncId=&clientUploadId=` (T17.9 §32/§36).
 *
 * Os dois parâmetros são obrigatórios, e o envelope é estrito pela mesma razão do corpo: um
 * parâmetro desconhecido descreve um cliente que acredita que ele significa alguma coisa.
 *
 * O `sessionSyncId` está aqui — e não no corpo — porque o corpo **são os bytes da imagem**. Ele
 * continua sendo referência interna do dono para o servidor (T17.8 §13): não sai em DTO nenhum,
 * não vai para log, e não aparece em mensagem de erro.
 */
export function parseUploadQuery(query: Record<string, unknown>): {
  readonly sessionSyncId: string;
  readonly clientUploadId: string;
} {
  rejectUnknownFields(query, ['sessionSyncId', 'clientUploadId']);
  return {
    sessionSyncId: requireIdentifier(query.sessionSyncId, 'sessionSyncId'),
    clientUploadId: requireIdentifier(query.clientUploadId, 'clientUploadId'),
  };
}

/** `GET /v1/social/workout-checkins/{id}/comments?limit=` — o único parâmetro aceito (§90). */
export function parseCommentsQuery(query: Record<string, unknown>): { readonly limit?: number } {
  rejectUnknownFields(query, ['limit']);
  if (!('limit' in query) || query.limit === undefined || query.limit === '') {
    return {};
  }
  const raw = query.limit;
  if (typeof raw !== 'string' && typeof raw !== 'number') {
    throw WorkoutCheckInErrors.invalid('limit precisa ser um número inteiro');
  }
  const parsed = typeof raw === 'number' ? raw : Number(raw);
  if (!Number.isInteger(parsed) || parsed < 1) {
    throw WorkoutCheckInErrors.invalid('limit precisa ser um número inteiro maior que zero');
  }
  // Como no feed: pedir mais que o teto não é erro, é atendido até o teto (§90).
  return { limit: parsed };
}

/**
 * `GET /v1/social/feed?limit=` — o único parâmetro aceito.
 *
 * **Não existe** `?users=`, `?socialIds=` nem `?authors=` (§71): o servidor deriva a audiência de
 * quem perguntou, das amizades atuais e da política de bloqueio. Uma lista de usuários no query
 * string seria o cliente escolhendo de quem ler — que é exatamente o feed público que a T17.8 não
 * é. Um parâmetro desconhecido recusa a requisição, pela mesma razão do envelope estrito acima.
 */
export function parseFeedQuery(query: Record<string, unknown>): { readonly limit?: number } {
  rejectUnknownFields(query, ['limit']);

  if (!('limit' in query) || query.limit === undefined || query.limit === '') {
    return {};
  }

  const raw = query.limit;
  if (typeof raw !== 'string' && typeof raw !== 'number') {
    throw WorkoutCheckInErrors.invalid('limit precisa ser um número inteiro');
  }

  const parsed = typeof raw === 'number' ? raw : Number(raw);
  if (!Number.isInteger(parsed) || parsed < 1) {
    throw WorkoutCheckInErrors.invalid('limit precisa ser um número inteiro maior que zero');
  }

  // O teto não é validado aqui: pedir mais que o máximo **não** é erro, é um pedido que o serviço
  // atende até o limite (§74). Recusar faria um cliente novo quebrar contra um servidor antigo.
  return { limit: parsed };
}

/** Teto de tamanho do corpo, medido em bytes UTF-8. */
export function assertCheckInBodyWithinLimit(rawBody: string | undefined): void {
  if (
    rawBody !== undefined &&
    Buffer.byteLength(rawBody, 'utf8') > MAX_CHECKIN_REQUEST_BODY_BYTES
  ) {
    throw WorkoutCheckInErrors.invalid('corpo da requisição maior que o permitido');
  }
}

function requireObject(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw WorkoutCheckInErrors.invalid('o corpo da requisição precisa ser um objeto JSON');
  }
  return body as Record<string, unknown>;
}

function rejectServerOwnedFields(object: Record<string, unknown>): void {
  for (const field of SERVER_OWNED_FIELDS) {
    if (field in object) {
      throw WorkoutCheckInErrors.invalid(
        `${field} não é aceito nesta requisição: o servidor deriva esse dado da sessão canônica`,
      );
    }
  }
}

function rejectUnknownFields(object: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw WorkoutCheckInErrors.invalid(`campo não reconhecido na requisição: ${key}`);
    }
  }
}

function requireIdentifier(value: unknown, field: string): string {
  if (typeof value !== 'string') {
    throw WorkoutCheckInErrors.invalid(`${field} é obrigatório e precisa ser texto`);
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    throw WorkoutCheckInErrors.invalid(`${field} não pode ser vazio`);
  }
  if (trimmed.length > MAX_CHECKIN_IDENTIFIER_LENGTH) {
    throw WorkoutCheckInErrors.invalid(`${field} é maior que o permitido`);
  }
  return trimmed;
}
