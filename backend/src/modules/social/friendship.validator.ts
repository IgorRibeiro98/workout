import { FriendshipErrors } from './friendship.errors';
import type { ListCursor } from './friendship.repository';
import { SOCIAL_LIST_PAGE } from './social.limits';

/**
 * A validação das requisições do grafo social (T17.1).
 *
 * Mesma fronteira de `social.validator.ts`, com uma diferença que precisa ser explícita:
 *
 * ```text
 * T17.0   socialId no corpo  ──▶ RECUSADO   (o cliente estaria propondo a própria identidade)
 * T17.1   socialId no corpo  ──▶ ACEITO     (o cliente está nomeando OUTRA pessoa)
 * ```
 *
 * Não é uma exceção à regra da T17.0 — é a mesma regra. O que continua proibido é o cliente
 * propor **a identidade dele**: `ownerUid`, `uid`, `firebaseUid`, `email`, `status`, `createdAt`.
 * Um `socialId` aqui é uma **referência** a um perfil alheio, do jeito que um `friendCode` no
 * lookup também é. Nenhum dos dois autoriza coisa alguma: quem age continua sendo o `uid` do token
 * verificado, e o servidor resolve `socialId → owner_uid` internamente antes de aplicar a política.
 */

/** Campos que descrevem **quem é o chamador**. Eles saem do token, e nunca do corpo. */
const CALLER_OWNED_FIELDS = [
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'email',
  'status',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
] as const;

/**
 * Teto de tamanho de um identificador vindo do cliente.
 *
 * Um `socialId` é um UUID (36) e um `friendCode` canônico tem 12. O teto existe para que uma
 * entrada absurda seja recusada **antes** de virar consulta ou entrar em qualquer comparação —
 * e não porque um valor maior encontraria algo.
 */
const MAX_IDENTIFIER_LENGTH = 128;

/** Teto do cursor opaco. Ele é gerado pelo servidor; um cursor grande é cliente inventando. */
const MAX_CURSOR_LENGTH = 256;

export interface FriendLookupRequest {
  readonly friendCode: string;
}

export interface SocialIdTargetRequest {
  readonly socialId: string;
}

/** `POST /v1/social/friends/lookup`. */
export function parseFriendLookupRequest(body: unknown): FriendLookupRequest {
  const object = requireObject(body);
  rejectCallerOwnedFields(object);
  rejectUnknownFields(object, ['friendCode']);
  return { friendCode: requireIdentifier(object.friendCode, 'friendCode') };
}

/** `POST /v1/social/friend-requests` e `POST /v1/social/friends/remove`. */
export function parseSocialIdTarget(body: unknown): SocialIdTargetRequest {
  const object = requireObject(body);
  rejectCallerOwnedFields(object);
  rejectUnknownFields(object, ['socialId']);
  return { socialId: requireIdentifier(object.socialId, 'socialId') };
}

/**
 * O `requestId` de um caminho de rota.
 *
 * Ele é opaco para o cliente e não precisa ser um UUID bem-formado para ser recusado: um id que
 * não existe e um id fora de forma dão a mesma resposta (`FRIEND_REQUEST_NOT_FOUND`), então a
 * única coisa que a validação faz aqui é impedir que um valor absurdo chegue à consulta.
 */
export function parseRequestId(raw: unknown): string {
  return requireIdentifier(raw, 'requestId');
}

export interface ListQuery {
  readonly limit: number;
  readonly cursor: ListCursor | null;
}

/**
 * `?limit=&cursor=` das listagens.
 *
 * Ausente vira o default. Acima do teto é **cortado em silêncio**, não recusado: um cliente que
 * pede 500 quer a lista, e devolver 100 é atendê-lo dentro do que o servidor sustenta. Já um
 * `limit` fora de forma (texto, zero, negativo, fracionário) é defeito de cliente e é recusado —
 * ele quase sempre significa uma URL montada errado.
 */
export function parseListQuery(rawLimit: unknown, rawCursor: unknown): ListQuery {
  let limit: number = SOCIAL_LIST_PAGE.defaultLimit;

  if (rawLimit !== undefined && rawLimit !== '') {
    const parsed = Number(rawLimit);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw FriendshipErrors.invalid('limit precisa ser um inteiro positivo');
    }
    limit = Math.min(parsed, SOCIAL_LIST_PAGE.maxLimit);
  }

  return { limit, cursor: parseCursor(rawCursor) };
}

/**
 * O cursor, codificado pelo servidor e devolvido pelo cliente **como veio**.
 *
 * Base64url de um JSON com a chave de ordenação e o desempate. Ele é opaco por decisão: um cursor
 * que o cliente saiba montar é um cursor que ele vai montar errado — e um `offset` numérico, que
 * seria a alternativa "simples", pula ou repete itens sempre que a lista muda entre duas páginas.
 */
export function encodeCursor(cursor: ListCursor): string {
  return Buffer.from(JSON.stringify([cursor.primary, cursor.secondary]), 'utf8').toString(
    'base64url',
  );
}

function parseCursor(raw: unknown): ListCursor | null {
  if (raw === undefined || raw === '') {
    return null;
  }
  if (typeof raw !== 'string' || raw.length > MAX_CURSOR_LENGTH) {
    throw FriendshipErrors.invalid('cursor inválido');
  }

  let decoded: unknown;
  try {
    decoded = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
  } catch {
    throw FriendshipErrors.invalid('cursor inválido');
  }

  if (
    !Array.isArray(decoded) ||
    decoded.length !== 2 ||
    (typeof decoded[0] !== 'string' && typeof decoded[0] !== 'number') ||
    typeof decoded[1] !== 'string'
  ) {
    throw FriendshipErrors.invalid('cursor inválido');
  }
  return { primary: decoded[0], secondary: decoded[1] };
}

function requireObject(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw FriendshipErrors.invalid('o corpo da requisição precisa ser um objeto JSON');
  }
  return body as Record<string, unknown>;
}

function rejectCallerOwnedFields(object: Record<string, unknown>): void {
  for (const field of CALLER_OWNED_FIELDS) {
    if (field in object) {
      throw FriendshipErrors.invalid(
        `${field} é definido pelo servidor e não pode ser enviado na requisição`,
      );
    }
  }
}

function rejectUnknownFields(object: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw FriendshipErrors.invalid(`campo não reconhecido no corpo da requisição: ${key}`);
    }
  }
}

function requireIdentifier(raw: unknown, field: string): string {
  if (typeof raw !== 'string') {
    throw FriendshipErrors.invalid(`${field} é obrigatório e precisa ser texto`);
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_IDENTIFIER_LENGTH) {
    throw FriendshipErrors.invalid(`${field} tem tamanho inválido`);
  }
  return trimmed;
}
