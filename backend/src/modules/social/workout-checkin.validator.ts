import {
  INTERACTION_AUDIENCE_TYPES,
  REACTION_TYPES,
  type CreateWorkoutCheckInRequest,
  type InteractionContextRequest,
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
 * O contexto de audiência de uma interação (T17.12 §7/§27/§67/§69).
 *
 * ## O que este parser garante — e o que ele deliberadamente **não** garante
 *
 * Ele garante **forma**: `type` pertence ao enum fechado `FRIEND | GROUP`, `GROUP` traz um
 * `groupId` não vazio, e `FRIEND` **não** traz nenhum. Um `{ type: 'GROUP' }` sem `groupId` é
 * `INVALID_CHECKIN_REQUEST` (400) e não um `404`: a requisição está malformada, e responder "não
 * encontrado" faria um cliente com bug procurar o defeito no Squad errado.
 *
 * Ele **não** garante autorização. Que aquele Squad exista, que o check-in esteja compartilhado
 * nele, que o requisitante seja membro ativo e que não haja bloqueio é decidido por
 * `WorkoutCheckInContextResolver`, contra as tabelas, a cada requisição (§7/§9). Aqui `groupId` é
 * só texto — e é exatamente por isso que ele nunca concede nada sozinho (§179).
 *
 * Ausente (`undefined`/`null`) é aceito e significa `FRIEND` no resolvedor (§68): um APK anterior
 * à T17.12 continua reagindo e comentando no Feed de amigos como sempre fez.
 */
export function parseInteractionContext(value: unknown): InteractionContextRequest | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    throw WorkoutCheckInErrors.invalid('context precisa ser um objeto');
  }

  const object = value as Record<string, unknown>;
  rejectUnknownFields(object, ['type', 'groupId']);

  const type = object.type;
  if (
    typeof type !== 'string' ||
    !(INTERACTION_AUDIENCE_TYPES as readonly string[]).includes(type)
  ) {
    throw WorkoutCheckInErrors.invalid('context.type precisa ser FRIEND ou GROUP');
  }

  if (type === 'GROUP') {
    // §69 — fail-closed: sem `groupId` não existe contexto de grupo, e **nunca** se cai para
    // `FRIEND` no lugar. Um contexto de grupo incompleto que virasse interação de amigos publicaria
    // no Feed de amigos algo que a pessoa escreveu achando que estava num Squad.
    if (typeof object.groupId !== 'string' || object.groupId.trim().length === 0) {
      throw WorkoutCheckInErrors.invalid(
        'context.groupId é obrigatório quando context.type é GROUP',
      );
    }
    if (object.groupId.trim().length > MAX_CHECKIN_IDENTIFIER_LENGTH) {
      throw WorkoutCheckInErrors.invalid('context.groupId é maior que o permitido');
    }
    return { type: 'GROUP', groupId: object.groupId.trim() };
  }

  // §27 — `FRIEND` com `groupId` descreve um cliente confuso: as duas coisas não coexistem, e a
  // mesma checagem existe como `CHECK` no banco.
  if ('groupId' in object && object.groupId !== undefined && object.groupId !== null) {
    throw WorkoutCheckInErrors.invalid('context.groupId não é aceito quando context.type é FRIEND');
  }
  return { type: 'FRIEND' };
}

/**
 * `PUT /v1/social/workout-checkins/{id}/reaction` (T17.9 §62/§66; T17.12 §15).
 *
 * O enum é **fechado**, e a recusa acontece aqui antes de qualquer escrita. Não existe caminho em
 * que o cliente envie um emoji: `"🔥"` no lugar de `"FIRE"` é `INVALID_REACTION`, porque aceitar
 * um caractere arbitrário seria aceitar conteúdo livre de terceiros na publicação de alguém — sem
 * limite, sem sanitização e sem alvo de denúncia. A T17.12 **não** acrescenta nenhum tipo novo
 * (§14): o que ela acrescenta é a audiência em que aquela reação existe.
 */
export function parseReactionRequest(body: unknown): {
  readonly type: ReactionType;
  readonly context: InteractionContextRequest | undefined;
} {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['type', 'context']);

  const type = object.type;
  if (typeof type !== 'string' || !(REACTION_TYPES as readonly string[]).includes(type)) {
    throw WorkoutCheckInErrors.invalidReaction();
  }
  return { type: type as ReactionType, context: parseInteractionContext(object.context) };
}

/**
 * `DELETE /v1/social/workout-checkins/{id}/reaction` — o corpo, quando existe (T17.12 §16).
 *
 * Remover exige o **mesmo** contexto de quem colocou: sem ele, um toque em "desfazer" dentro do
 * Squad X apagaria a reação que a pessoa deixou no Feed de amigos, que é uma audiência inteiramente
 * diferente. Um `DELETE` sem corpo continua válido e significa `FRIEND` (§68), pelo mesmo motivo do
 * `PUT`.
 */
export function parseRemoveReactionRequest(body: unknown): InteractionContextRequest | undefined {
  // Um `DELETE` sem corpo chega aqui como `{}` (o parser JSON do Nest) ou `undefined`.
  if (body === undefined || body === null) {
    return undefined;
  }
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['context']);
  return parseInteractionContext(object.context);
}

/** `POST /v1/social/workout-checkins/{id}/comments` (T17.9 §81; T17.12 §17). */
export function parseCommentRequest(body: unknown): {
  readonly body: string;
  readonly context: InteractionContextRequest | undefined;
} {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['body', 'context']);
  return {
    body: parseCommentBody(object.body),
    context: parseInteractionContext(object.context),
  };
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

/**
 * O contexto de audiência quando ele chega pela **query string** (T17.12 §33/§34/§35/§66).
 *
 * As duas rotas de leitura — o detalhe do check-in e a lista de comentários — são `GET`, e um `GET`
 * não carrega corpo. A forma é a mesma do objeto do corpo, achatada:
 *
 * ```text
 * (ausente)                     → FRIEND         (§68, compatibilidade)
 * ?context=FRIEND               → FRIEND
 * ?context=GROUP&groupId=X      → GROUP(X)
 * ?context=GROUP                → 400            (§69, fail-closed)
 * ```
 *
 * Ela é reconstruída no mesmo [parseInteractionContext] do corpo — não existe um segundo conjunto
 * de regras para o mesmo conceito, que é justamente o que §29/§31 proíbem.
 */
function parseContextQuery(query: Record<string, unknown>): InteractionContextRequest | undefined {
  const raw = query.context;
  if (raw === undefined || raw === null || raw === '') {
    if (query.groupId !== undefined && query.groupId !== null && query.groupId !== '') {
      // `groupId` sem `context` é ambíguo: pode ser um cliente que esqueceu metade do contexto.
      // Recusar é a única resposta segura — inferir `GROUP` a partir da presença do parâmetro
      // faria o servidor adivinhar exatamente o que §7 diz para ele nunca adivinhar.
      throw WorkoutCheckInErrors.invalid('groupId exige context=GROUP');
    }
    return undefined;
  }
  if (typeof raw !== 'string') {
    throw WorkoutCheckInErrors.invalid('context precisa ser FRIEND ou GROUP');
  }
  // `groupId` é repassado **sempre**, inclusive com `context=FRIEND`, para que a recusa de §27
  // ("FRIEND não coexiste com groupId") aconteça também aqui. Filtrá-lo antes faria a query string
  // aceitar em silêncio o que o corpo recusa com `400` — duas regras para o mesmo conceito, que é
  // exatamente o que §29/§31 proíbem.
  const groupId = query.groupId === '' ? undefined : query.groupId;
  return parseInteractionContext({ type: raw, groupId });
}

/**
 * `GET /v1/social/workout-checkins/{id}/comments?limit=&context=&groupId=` (§90; T17.12 §65/§66).
 *
 * A audiência é **obrigatória para a resposta fazer sentido**: `GROUP(X)` lista só os comentários
 * de `GROUP(X)`, e nunca os de `GROUP(Y)` nem os do Feed de amigos (§20/§21/§137).
 */
export function parseCommentsQuery(query: Record<string, unknown>): {
  readonly limit?: number;
  readonly context?: InteractionContextRequest;
} {
  rejectUnknownFields(query, ['limit', 'context', 'groupId']);
  const context = parseContextQuery(query);

  if (!('limit' in query) || query.limit === undefined || query.limit === '') {
    return { context };
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
  return { limit: parsed, context };
}

/**
 * `GET /v1/social/workout-checkins/{id}?context=&groupId=` — o detalhe (T17.12 §35).
 *
 * Sem contexto, a rota responde exatamente o que respondia na T17.11: a publicação é resolvida por
 * `findAccessibleCheckIn`, e quem chega até ela **só** por um Squad continua sem interação. Com
 * `context=GROUP&groupId=X`, a leitura passa a ser a daquele Squad — as contagens são de `GROUP(X)`
 * e a interação é permitida a qualquer membro ativo (§76).
 */
export function parseCheckInDetailQuery(query: Record<string, unknown>): {
  readonly context?: InteractionContextRequest;
} {
  rejectUnknownFields(query, ['context', 'groupId']);
  return { context: parseContextQuery(query) };
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
