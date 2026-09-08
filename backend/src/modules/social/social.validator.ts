import {
  isSocialDiscoverability,
  type SocialDiscoverability,
  SOCIAL_DISCOVERABILITY_VALUES,
} from './social.contract';
import { SocialErrors } from './social.errors';
import { MAX_SOCIAL_REQUEST_BODY_BYTES, SOCIAL_DISPLAY_NAME } from './social.limits';
import { isValidTimeZone } from './social-time';

/**
 * A validação das requisições sociais (T17.0).
 *
 * Duas responsabilidades, e as duas são fronteira:
 *
 * 1. **envelope estrito** — o corpo só pode conter os campos que o cliente tem o direito de
 *    propor. `ownerUid`, `socialId`, `friendCode`, `status`, `createdAt` e `updatedAt` recusam a
 *    requisição inteira em vez de serem ignorados. Ignorar em silêncio é pior: um cliente que
 *    envia `ownerUid` acredita que ele significa alguma coisa, e alguma versão futura do servidor
 *    acabaria "aproveitando" um campo que nunca deveria ter existido;
 * 2. **forma do nome social** — o que é um nome, e o que é uma tentativa de falsificar layout.
 */

/**
 * Os campos que o servidor decide e que o cliente nunca envia.
 *
 * Eles são recusados **por nome**, e não apenas ausentes do schema, para que a mensagem de erro
 * seja específica: um cliente que manda `socialId` precisa descobrir que a identidade é do
 * servidor, e não que "o corpo é inválido".
 */
const SERVER_OWNED_FIELDS = [
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'socialId',
  'social_id',
  'friendCode',
  'friend_code',
  'status',
  'createdAt',
  'created_at',
  'updatedAt',
  'updated_at',
  'email',
] as const;

export interface ActivateSocialRequest {
  readonly displayName: string;
}

export interface UpdateSocialProfileRequest {
  readonly displayName: string;
}

export interface UpdateSocialPrivacyRequest {
  readonly discoverability?: SocialDiscoverability;
  readonly friendRequestsEnabled?: boolean;
  readonly activitySharingEnabled?: boolean;
  readonly activityTimeZoneId?: string;
  readonly friendRankingParticipationEnabled?: boolean;
}

/** `POST /v1/social/me/activate` — só o nome social. Todo o resto é gerado pelo servidor. */
export function parseActivateRequest(body: unknown): ActivateSocialRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['displayName']);
  return { displayName: normalizeDisplayName(object.displayName) };
}

/**
 * `PATCH /v1/social/me` — só o nome social.
 *
 * `socialId`, `friendCode` e `ownerUid` não são alteráveis por PATCH, e a recusa é explícita:
 * uma identidade que muda por requisição do cliente não é identidade.
 */
export function parseUpdateProfileRequest(body: unknown): UpdateSocialProfileRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['displayName']);
  return { displayName: normalizeDisplayName(object.displayName) };
}

/**
 * `PATCH /v1/social/me/privacy` — parcial, e por isso todo campo é opcional.
 *
 * Um corpo vazio é recusado: ele quase sempre significa que o cliente montou a requisição errado,
 * e responder `200` a um pedido que não pediu nada esconderia esse defeito.
 */
export function parseUpdatePrivacyRequest(body: unknown): UpdateSocialPrivacyRequest {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, [
    'discoverability',
    'friendRequestsEnabled',
    'activitySharingEnabled',
    'activityTimeZoneId',
    'friendRankingParticipationEnabled',
  ]);

  const request: {
    discoverability?: SocialDiscoverability;
    friendRequestsEnabled?: boolean;
    activitySharingEnabled?: boolean;
    activityTimeZoneId?: string;
    friendRankingParticipationEnabled?: boolean;
  } = {};

  if ('discoverability' in object) {
    if (!isSocialDiscoverability(object.discoverability)) {
      // A lista de valores aceitos entra na mensagem de propósito: ela é pública, pequena, e é a
      // informação que faz um cliente desatualizado entender o que aconteceu.
      throw SocialErrors.invalid(
        `discoverability aceita apenas: ${SOCIAL_DISCOVERABILITY_VALUES.join(', ')}`,
      );
    }
    request.discoverability = object.discoverability;
  }
  if ('friendRequestsEnabled' in object) {
    request.friendRequestsEnabled = requireBoolean(
      object.friendRequestsEnabled,
      'friendRequestsEnabled',
    );
  }
  if ('activitySharingEnabled' in object) {
    request.activitySharingEnabled = requireBoolean(
      object.activitySharingEnabled,
      'activitySharingEnabled',
    );
  }
  if ('activityTimeZoneId' in object) {
    if (
      typeof object.activityTimeZoneId !== 'string' ||
      !isValidTimeZone(object.activityTimeZoneId)
    ) {
      throw SocialErrors.invalidActivityTimeZone('fuso horário da atividade inválido');
    }
    request.activityTimeZoneId = object.activityTimeZoneId;
  }
  if ('friendRankingParticipationEnabled' in object) {
    request.friendRankingParticipationEnabled = requireBoolean(
      object.friendRankingParticipationEnabled,
      'friendRankingParticipationEnabled',
    );
  }

  if (Object.keys(request).length === 0) {
    throw SocialErrors.invalid('nenhuma configuração de privacidade foi informada');
  }
  return request;
}

/**
 * O nome social, normalizado e validado.
 *
 * A ordem importa: normaliza **antes** de medir. `"  Igor  "` é um nome de 4 caracteres, e
 * `"  "` não é um nome de 2 — validar antes do trim aceitaria o segundo.
 *
 * O que é recusado, e por quê:
 *
 * - **controle e quebra de linha** (`U+0000`–`U+001F`, `U+007F`–`U+009F`): transformam uma linha
 *   de UI em três e permitem desenhar um nome que finge ser outra coisa na tela;
 * - **marcas de direção bidirecional** (`U+202A`–`U+202E`, `U+2066`–`U+2069`, `U+200E`,
 *   `U+200F`): elas reordenam visualmente o texto ao redor, e um nome que reordena o texto ao
 *   redor não é um nome;
 * - **comprimento** fora de [SOCIAL_DISPLAY_NAME].
 *
 * O que **não** é recusado: acento, emoji, alfabeto não latino e nomes repetidos. `displayName`
 * não é username global e não precisa ser único — a identidade única é o `socialId`.
 */
export function normalizeDisplayName(raw: unknown): string {
  if (typeof raw !== 'string') {
    throw SocialErrors.invalidDisplayName('displayName é obrigatório e precisa ser texto');
  }

  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    throw SocialErrors.invalidDisplayName('o nome social não pode ser vazio');
  }

  // Escapes explícitos, e não os caracteres literais: um `\x00` literal no fonte é invisível em
  // review, sobrevive mal a copiar/colar e some numa normalização de arquivo.
  // eslint-disable-next-line no-control-regex -- é exatamente a classe que precisa ser recusada.
  if (/[\u0000-\u001F\u007F-\u009F]/.test(trimmed)) {
    throw SocialErrors.invalidDisplayName(
      'o nome social não pode conter quebras de linha ou caracteres de controle',
    );
  }
  if (/[\u200E\u200F\u202A-\u202E\u2066-\u2069]/.test(trimmed)) {
    throw SocialErrors.invalidDisplayName(
      'o nome social não pode conter marcas de direção de texto',
    );
  }

  // Code points, não unidades UTF-16: um emoji é um caractere para quem digita e dois para
  // `String.length`, e o limite precisa significar a mesma coisa em qualquer alfabeto.
  const length = [...trimmed].length;
  if (length < SOCIAL_DISPLAY_NAME.minLength) {
    throw SocialErrors.invalidDisplayName(
      `o nome social precisa ter pelo menos ${SOCIAL_DISPLAY_NAME.minLength} caracteres`,
    );
  }
  if (length > SOCIAL_DISPLAY_NAME.maxLength) {
    throw SocialErrors.invalidDisplayName(
      `o nome social pode ter no máximo ${SOCIAL_DISPLAY_NAME.maxLength} caracteres`,
    );
  }

  return trimmed;
}

/**
 * Teto de tamanho do corpo, medido em bytes UTF-8.
 *
 * Existe porque as rotas sociais carregam um nome e três booleanos, e o teto global do processo é
 * o do backup (4 MiB). Aceitar 4 MiB para escrever um nome não teria razão nenhuma.
 */
export function assertBodyWithinLimit(rawBody: string | undefined): void {
  if (rawBody !== undefined && Buffer.byteLength(rawBody, 'utf8') > MAX_SOCIAL_REQUEST_BODY_BYTES) {
    throw SocialErrors.invalid('corpo da requisição social maior que o permitido');
  }
}

function requireObject(body: unknown): Record<string, unknown> {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw SocialErrors.invalid('o corpo da requisição precisa ser um objeto JSON');
  }
  return body as Record<string, unknown>;
}

function rejectServerOwnedFields(object: Record<string, unknown>): void {
  for (const field of SERVER_OWNED_FIELDS) {
    if (field in object) {
      throw SocialErrors.invalid(
        `${field} é definido pelo servidor e não pode ser enviado na requisição`,
      );
    }
  }
}

function rejectUnknownFields(object: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw SocialErrors.invalid(`campo não reconhecido no corpo da requisição: ${key}`);
    }
  }
}

function requireBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') {
    throw SocialErrors.invalid(`${field} precisa ser booleano`);
  }
  return value;
}
