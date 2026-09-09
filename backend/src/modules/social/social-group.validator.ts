import type {
  CreateGroupInvitationRequest,
  CreateSocialGroupRequest,
  TransferGroupOwnershipRequest,
} from './social-group.contract';
import { SocialGroupErrors } from './social-group.errors';
import {
  MAX_SOCIAL_GROUP_IDENTIFIER_LENGTH,
  MAX_SOCIAL_GROUP_REQUEST_BODY_BYTES,
  SOCIAL_GROUP_FEED,
  SOCIAL_GROUP_NAME,
} from './social-group.limits';

/**
 * A validação das requisições de Squad (T17.11 §8/§83).
 *
 * Duas responsabilidades, e as duas são fronteira:
 *
 * 1. **envelope estrito** — o corpo só pode conter os campos que o cliente tem o direito de
 *    propor. `ownerUid`, `groupId`, `memberCount`, `role` e `status` recusam a requisição inteira
 *    em vez de serem ignorados. Ignorar em silêncio é pior: um cliente que envia `role: "OWNER"`
 *    acredita que aquilo significa alguma coisa, e alguma versão futura do servidor acabaria
 *    "aproveitando" um campo que nunca deveria ter existido;
 * 2. **forma do nome** — o que é um nome de grupo, e o que é uma tentativa de falsificar layout na
 *    tela de outras pessoas.
 *
 * §83 é o princípio que atravessa este arquivo: **o backend calcula a autorização**. O `groupId`
 * da rota identifica um contexto possível; ele nunca *concede* nada.
 */

/**
 * Os campos que o servidor decide e que o cliente nunca envia (§83).
 *
 * Recusados **por nome** para que a mensagem seja específica: um cliente que manda `ownerUid`
 * precisa descobrir que a posse é do servidor, e não que "o corpo é inválido".
 */
const SERVER_OWNED_FIELDS = [
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'groupId',
  'group_id',
  'publicId',
  'public_id',
  'memberUids',
  'members',
  'memberCount',
  'role',
  'status',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  'deletedAt',
  'expiresAt',
  'respondedAt',
  'email',
] as const;

/** `POST /v1/social/groups` (§16/§17). */
export function parseCreateGroupRequest(body: unknown): CreateSocialGroupRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['name', 'clientRequestId']);

  return {
    name: normalizeGroupName(object.name),
    clientRequestId: requireIdentifier(object.clientRequestId, 'clientRequestId'),
  };
}

/** `POST /v1/social/groups/{groupId}/invitations` (§22/§23/§24). */
export function parseCreateInvitationRequest(body: unknown): CreateGroupInvitationRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  // `socialId` é o alvo, e por isso é o **único** campo de identidade aceito aqui. Ele não está em
  // `SERVER_OWNED_FIELDS` para esta rota: quem convida precisa dizer quem convidar, e a identidade
  // pública é justamente o que existe para isso. `friendCode`, `displayName` e `email` continuam
  // recusados por não estarem na lista de conhecidos — §24.
  rejectUnknownFields(object, ['socialId', 'clientRequestId']);

  return {
    socialId: requireIdentifier(object.socialId, 'socialId'),
    clientRequestId: requireIdentifier(object.clientRequestId, 'clientRequestId'),
  };
}

/**
 * `POST /v1/social/groups/{groupId}/transfer-ownership` (§40/§41).
 *
 * `membershipId` **não** está em [SERVER_OWNED_FIELDS], e é a única rota em que ele é aceito: ele
 * é o alvo legítimo desta operação. Nas outras duas o `rejectUnknownFields` já o recusa, porque
 * ele não está na lista de conhecidos delas — a proteção continua existindo, sem transformar o
 * campo em proibido em toda parte.
 */
export function parseTransferOwnershipRequest(body: unknown): TransferGroupOwnershipRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['membershipId']);

  return { membershipId: requireIdentifier(object.membershipId, 'membershipId') };
}

/**
 * O `limit` do feed do Squad e das listas (§86).
 *
 * Pedir mais que o teto **não** é erro — é atendido até o teto, como no Feed de amigos (§74 da
 * T17.8). Recusar faria um cliente novo quebrar contra um servidor antigo por causa de um número.
 * O que é recusado é um `limit` fora de forma: texto, negativo, zero. Isso é defeito de cliente.
 */
export function parseGroupFeedQuery(query: Record<string, unknown>): { limit?: number } {
  rejectUnknownFields(query, ['limit']);
  if (!('limit' in query) || query.limit === undefined) {
    return {};
  }

  const raw = query.limit;
  const parsed = typeof raw === 'string' ? Number(raw) : raw;
  if (typeof parsed !== 'number' || !Number.isInteger(parsed) || parsed < 1) {
    throw SocialGroupErrors.invalid('limit precisa ser um inteiro positivo');
  }
  return { limit: Math.min(parsed, SOCIAL_GROUP_FEED.maxLimit) };
}

/**
 * O nome do Squad, normalizado e validado (§8).
 *
 * A ordem importa: normaliza **antes** de medir. `"  Os Monstros  "` é um nome de 11 caracteres, e
 * `"   "` não é um nome de 3 — validar antes do trim aceitaria o segundo.
 *
 * O que é recusado, e por quê:
 *
 * - **controle e quebra de linha** (`U+0000`–`U+001F`, `U+007F`–`U+009F`): transformam uma linha
 *   de lista em três e permitem desenhar um nome que finge ser outra coisa na tela de todo mundo
 *   que recebe o convite;
 * - **marcas de direção bidirecional** (`U+202A`–`U+202E`, `U+2066`–`U+2069`, `U+200E`, `U+200F`):
 *   reordenam visualmente o texto ao redor, e um nome que reordena o texto ao redor não é um nome;
 * - **invisíveis** (`U+200B`–`U+200D`, `U+FEFF`): permitem um nome que parece vazio, ou dois nomes
 *   visualmente idênticos e diferentes para o servidor;
 * - **comprimento** fora de [SOCIAL_GROUP_NAME], contado em code points.
 *
 * O que **não** é recusado: acento, emoji, alfabeto não latino e nomes repetidos. Dois Squads
 * chamados "Os Monstros" são legítimos — o nome não é identidade, o `groupId` é.
 *
 * A normalização é NFC, como a legenda da T17.9: sem ela, "é" pode ser um code point ou dois, e
 * dois nomes idênticos na tela teriam comprimentos diferentes.
 */
export function normalizeGroupName(raw: unknown): string {
  if (typeof raw !== 'string') {
    throw SocialGroupErrors.invalidName('name é obrigatório e precisa ser texto');
  }

  const trimmed = raw.normalize('NFC').trim();
  if (trimmed.length === 0) {
    throw SocialGroupErrors.invalidName('o nome do squad não pode ser vazio');
  }

  // Escapes explícitos, e não os caracteres literais: um `\x00` literal no fonte é invisível em
  // review, sobrevive mal a copiar/colar e some numa normalização de arquivo.
  // eslint-disable-next-line no-control-regex -- é exatamente a classe que precisa ser recusada.
  if (/[\u0000-\u001F\u007F-\u009F]/.test(trimmed)) {
    throw SocialGroupErrors.invalidName(
      'o nome do squad não pode conter quebras de linha ou caracteres de controle',
    );
  }
  if (/[\u200E\u200F\u202A-\u202E\u2066-\u2069]/.test(trimmed)) {
    throw SocialGroupErrors.invalidName(
      'o nome do squad não pode conter marcas de direção de texto',
    );
  }
  if (/[\u200B-\u200D\uFEFF]/.test(trimmed)) {
    throw SocialGroupErrors.invalidName('o nome do squad não pode conter caracteres invisíveis');
  }

  // Code points, não unidades UTF-16: um emoji é um caractere para quem digita e dois para
  // `String.length`, e o limite precisa significar a mesma coisa em qualquer alfabeto.
  const length = [...trimmed].length;
  if (length < SOCIAL_GROUP_NAME.minLength) {
    throw SocialGroupErrors.invalidName(
      `o nome do squad precisa ter pelo menos ${SOCIAL_GROUP_NAME.minLength} caracteres`,
    );
  }
  if (length > SOCIAL_GROUP_NAME.maxLength) {
    throw SocialGroupErrors.invalidName(
      `o nome do squad pode ter no máximo ${SOCIAL_GROUP_NAME.maxLength} caracteres`,
    );
  }

  return trimmed;
}

/**
 * Teto de tamanho do corpo, medido em bytes UTF-8.
 *
 * Existe porque as rotas de Squad carregam um nome e um ou dois identificadores, e o teto global
 * do processo é o do backup (4 MiB). Aceitar 4 MiB para criar um grupo não teria razão nenhuma, e
 * o corpo é rejeitado por tamanho **antes** de qualquer validação de conteúdo.
 */
export function assertGroupBodyWithinLimit(rawBody: string | undefined): void {
  if (
    rawBody !== undefined &&
    Buffer.byteLength(rawBody, 'utf8') > MAX_SOCIAL_GROUP_REQUEST_BODY_BYTES
  ) {
    throw SocialGroupErrors.invalid('corpo da requisição de squad maior que o permitido');
  }
}

/**
 * Um identificador vindo da **rota**.
 *
 * Ele não autoriza nada (§59/§83) — a autorização é sempre uma consulta contra as tabelas —, mas
 * ainda precisa ter forma: um segmento de rota de 10 KB não é um UUID, e recusá-lo antes de virar
 * parâmetro de consulta evita gastar banco com entrada absurda.
 */
export function requirePathIdentifier(value: unknown, field: string): string {
  return requireIdentifier(value, field);
}

function requireIdentifier(value: unknown, field: string): string {
  if (typeof value !== 'string') {
    throw SocialGroupErrors.invalid(`${field} precisa ser texto`);
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    throw SocialGroupErrors.invalid(`${field} é obrigatório`);
  }
  if (trimmed.length > MAX_SOCIAL_GROUP_IDENTIFIER_LENGTH) {
    throw SocialGroupErrors.invalid(`${field} é maior que o permitido`);
  }
  return trimmed;
}

function requireObject(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw SocialGroupErrors.invalid('o corpo da requisição precisa ser um objeto JSON');
  }
  return body as Record<string, unknown>;
}

function rejectServerOwnedFields(object: Record<string, unknown>): void {
  for (const field of SERVER_OWNED_FIELDS) {
    if (field in object) {
      throw SocialGroupErrors.invalid(
        `${field} é definido pelo servidor e não pode ser enviado na requisição`,
      );
    }
  }
}

function rejectUnknownFields(object: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw SocialGroupErrors.invalid(`campo não reconhecido: ${key}`);
    }
  }
}
