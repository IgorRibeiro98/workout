import type { BlockUserRequestDto } from './block.contract';
import { SocialErrors } from './social.errors';

/**
 * A validação do corpo de `POST /v1/social/blocks` (T17.6).
 *
 * O envelope é estrito, e a recusa é por nome — a mesma regra da T17.0 (`social.validator.ts`) e
 * da T17.11 (`social-group.validator.ts`). Sem ela, o controller lia `body.blockedSocialId` de um
 * corpo que podia ser `null`, um array ou um número: um `TypeError` dentro do handler vira `500`,
 * e "requisição malformada" não é falha do servidor.
 *
 * `blockedSocialId` é o **único** campo aceito. `blockerUid` sai do token e nunca do corpo: um
 * cliente que o envia acredita que pode bloquear em nome de outra conta, e ignorá-lo em silêncio
 * concordaria em parte com essa crença.
 */
const SERVER_OWNED_FIELDS = [
  'blockerUid',
  'blocker_uid',
  'blockedUid',
  'blocked_uid',
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'friendCode',
  'friend_code',
  'email',
  'status',
  'createdAt',
  'created_at',
  'blockedAt',
  'blocked_at',
] as const;

/** O tamanho máximo de um `socialId` aceito aqui — o teto de forma, não a existência do perfil. */
const MAX_SOCIAL_ID_LENGTH = 200;

export function parseBlockUserRequest(body: unknown): BlockUserRequestDto {
  const object = requireObject(body);
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['blockedSocialId']);

  const blockedSocialId = object.blockedSocialId;
  if (typeof blockedSocialId !== 'string') {
    throw SocialErrors.invalid('blockedSocialId é obrigatório e precisa ser texto');
  }
  const trimmed = blockedSocialId.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_SOCIAL_ID_LENGTH) {
    throw SocialErrors.invalid('blockedSocialId fora do tamanho aceito');
  }
  return { blockedSocialId: trimmed };
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
