import { BadRequestException } from '@nestjs/common';
import {
  WorkoutShareErrorCodes,
  type CreateWorkoutShareRequest,
  type WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';

/**
 * A validação **estrutural** do corpo de `POST /v1/social/workout-shares` (T17.7).
 *
 * Ela existe porque o controller lia `body.recipientSocialId` e `body.snapshot.exercises.length`
 * de um corpo que ninguém tinha olhado: `null`, um array ou um número viravam `TypeError` dentro
 * do handler — `500` para uma requisição malformada, que é defeito do cliente e não do servidor.
 *
 * O envelope é estrito, e a recusa é por nome, como em toda rota social (T17.0, T17.8, T17.11): o
 * que não está na allowlist recusa a requisição **inteira** em vez de ser ignorado. Aqui isso não
 * é só higiene — o snapshot é persistido **verbatim** em `workout_shares.snapshot_json` e volta
 * inteiro para o destinatário. Uma chave desconhecida aceita aqui é conteúdo escolhido pelo
 * remetente atravessando o servidor até a tela de outra pessoa, dentro de 64 KB de folga.
 *
 * ## O que este arquivo **não** faz
 *
 * Semântica. Versão suportada, tamanho do nome, quantidade de exercícios, faixas de repetição e
 * forma do `canonicalExerciseId` continuam em `WorkoutShareService.validateSnapshot`, que é onde
 * sempre estiveram — duplicar as regras aqui criaria duas autoridades sobre o mesmo contrato.
 *
 * ## Os objetos devolvidos são os originais
 *
 * `snapshot` e cada exercício voltam **por referência**, e não recopiados campo a campo: é o texto
 * de `JSON.stringify(snapshot)` que vira `snapshot_hash`, e a idempotência por `clientRequestId`
 * compara esse hash. Remontar o objeto mudaria a ordem das chaves e, com ela, o hash de um corpo
 * idêntico ao anterior.
 */

/** Os campos que o servidor decide e que o cliente nunca envia. */
const SERVER_OWNED_FIELDS = [
  'senderUid',
  'sender_uid',
  'recipientUid',
  'recipient_uid',
  'ownerUid',
  'owner_uid',
  'uid',
  'firebaseUid',
  'email',
  'shareId',
  'id',
  'status',
  'snapshotHash',
  'snapshot_hash',
  'createdAt',
  'created_at',
  'expiresAt',
  'expires_at',
  'acceptedAt',
  'importedAt',
  'declinedAt',
  'cancelledAt',
] as const;

const SNAPSHOT_FIELDS = ['snapshotVersion', 'name', 'shortIdentifier', 'exercises'] as const;

const EXERCISE_FIELDS = [
  'canonicalExerciseId',
  'sortOrder',
  'targetSets',
  'minReps',
  'maxReps',
  'restDurationSeconds',
] as const;

/**
 * Teto do corpo, em bytes UTF-8.
 *
 * Maior que o das outras rotas sociais (2–8 KiB) porque aqui o corpo carrega um treino inteiro, e
 * `MAX_SNAPSHOT_BYTES` (64 KiB, no serviço) é o teto do snapshot em si. A folga cobre o envelope
 * ao redor dele; o teto global do processo continua sendo o do backup (4 MiB), que não faz sentido
 * nenhum conceder a esta rota.
 */
export const MAX_WORKOUT_SHARE_REQUEST_BODY_BYTES = 96 * 1024;

export function assertWorkoutShareBodyWithinLimit(rawBody: string | undefined): void {
  if (
    rawBody !== undefined &&
    Buffer.byteLength(rawBody, 'utf8') > MAX_WORKOUT_SHARE_REQUEST_BODY_BYTES
  ) {
    throw invalid('o corpo da requisição excede o tamanho máximo');
  }
}

export function parseCreateWorkoutShareRequest(body: unknown): CreateWorkoutShareRequest {
  const object = requireObject(body, 'o corpo da requisição');
  rejectServerOwnedFields(object);
  rejectUnknownFields(object, ['recipientSocialId', 'clientRequestId', 'snapshot'], 'no corpo');

  const recipientSocialId = requireText(object.recipientSocialId, 'recipientSocialId');
  const clientRequestId = requireText(object.clientRequestId, 'clientRequestId');

  const snapshot = requireObject(object.snapshot, 'snapshot');
  rejectUnknownFields(snapshot, SNAPSHOT_FIELDS, 'no snapshot');

  if (!Array.isArray(snapshot.exercises)) {
    throw invalid('snapshot.exercises precisa ser uma lista');
  }
  snapshot.exercises.forEach((raw, index) => {
    const exercise = requireObject(raw, `o exercício [${index}]`);
    rejectUnknownFields(exercise, EXERCISE_FIELDS, `no exercício [${index}]`);
  });

  return {
    recipientSocialId,
    clientRequestId,
    snapshot: object.snapshot as WorkoutTemplateShareSnapshotV1,
  };
}

function invalid(reason: string): BadRequestException {
  return new BadRequestException({
    code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
    message: reason,
  });
}

function requireObject(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalid(`${label} precisa ser um objeto JSON`);
  }
  return value as Record<string, unknown>;
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw invalid(`${field} é obrigatório e precisa ser texto`);
  }
  return value;
}

function rejectServerOwnedFields(object: Record<string, unknown>): void {
  for (const field of SERVER_OWNED_FIELDS) {
    if (field in object) {
      throw invalid(`${field} é definido pelo servidor e não pode ser enviado na requisição`);
    }
  }
}

function rejectUnknownFields(
  object: Record<string, unknown>,
  allowed: readonly string[],
  where: string,
): void {
  for (const key of Object.keys(object)) {
    if (!allowed.includes(key)) {
      throw invalid(`campo não reconhecido ${where}: ${key}`);
    }
  }
}
