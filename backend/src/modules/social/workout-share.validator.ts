import { BadRequestException } from '@nestjs/common';
import {
  WorkoutShareErrorCodes,
  type CreateWorkoutShareRequest,
  type WorkoutProgramShareSnapshotV1,
  type WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';

/**
 * A validação **estrutural** do corpo de `POST /v1/social/workout-shares` (T17.7 / T19.3).
 *
 * Ela existe porque o controller lia `body.recipientSocialId` e `body.snapshot.exercises.length`
 * de um corpo que ninguém tinha olhado: `null`, um array ou um número viravam `TypeError` dentro
 * do handler — `500` para uma requisição malformada, que é defeito do cliente e não do servidor.
 *
 * O envelope é estrito, e a recusa é por nome, como em toda rota social (T17.0, T17.8, T17.11): o
 * que não está na allowlist recusa a requisição **inteira** em vez de ser ignorado. Aqui isso não
 * é só higiene — o snapshot é persistido **verbatim** em `workout_shares.snapshot_json` e volta
 * inteiro para o destinatário. Uma chave desconhecida aceita aqui é conteúdo escolhido pelo
 * remetente atravessando o servidor até a tela de outra pessoa, dentro da folga do teto.
 *
 * ## Dois tipos, um discriminador (T19.3)
 *
 * O corpo carrega `snapshot` (um treino) **ou** `programSnapshot` (um programa inteiro). O campo
 * presente decide o `shareType`; os dois juntos, ou nenhum, recusam. Não existe um `shareType`
 * no corpo: seria uma segunda afirmação sobre o mesmo fato, e as duas poderiam discordar.
 *
 * ## Duas versões de snapshot (T19.H2)
 *
 * ```text
 * V1   treino com 1..30 exercícios, todos do catálogo
 * V2   + treino vazio  + exercício CUSTOM portátil (customExercises / customExerciseRef)
 * ```
 *
 * A allowlist deste arquivo é a **união** das duas: quem separa é `WorkoutShareService`, que
 * recusa `customExercises`/`customExerciseRef` num snapshot `snapshotVersion: 1`. Aqui a forma
 * ainda não sabe qual versão está lendo — o campo `snapshotVersion` é só mais uma chave.
 *
 * ## O que este arquivo **não** faz
 *
 * Semântica. Versão suportada, tamanho do nome, quantidade de treinos e de exercícios, faixas de
 * repetição, forma do `canonicalExerciseId` e resolução das referências CUSTOM continuam em
 * `WorkoutShareService`, que é onde sempre estiveram — duplicar as regras aqui criaria duas
 * autoridades sobre o mesmo contrato.
 *
 * ## Os objetos devolvidos são os originais
 *
 * O snapshot, cada treino e cada exercício voltam **por referência**, e não recopiados campo a
 * campo: é o texto de `JSON.stringify(snapshot)` que vira `snapshot_hash`, e a idempotência por
 * `clientRequestId` compara esse hash. Remontar o objeto mudaria a ordem das chaves e, com ela, o
 * hash de um corpo idêntico ao anterior.
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
  'shareType',
  'share_type',
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

const REQUEST_FIELDS = ['recipientSocialId', 'clientRequestId', 'snapshot', 'programSnapshot'];

const SNAPSHOT_FIELDS = [
  'snapshotVersion',
  'name',
  'shortIdentifier',
  'customExercises',
  'exercises',
] as const;

const PROGRAM_SNAPSHOT_FIELDS = [
  'snapshotVersion',
  'name',
  'description',
  'customExercises',
  'templates',
] as const;

const PROGRAM_TEMPLATE_FIELDS = [
  'name',
  'shortIdentifier',
  'orderInProgram',
  'scheduledDays',
  'dayOfWeek',
  'exercises',
] as const;

const EXERCISE_FIELDS = [
  'canonicalExerciseId',
  'customExerciseRef',
  'sortOrder',
  'targetSets',
  'minReps',
  'maxReps',
  'restDurationSeconds',
] as const;

/**
 * O que um exercício CUSTOM pode carregar (T19.H2 / V2).
 *
 * A allowlist é curta de propósito: é a lista do que quem recebe precisa para ter um exercício
 * dele. Foto local, mídia, `canonicalId`, `slug`, origem, versão de conteúdo e qualquer sinal de
 * identidade do remetente ficam de fora — e, por serem nomes fora desta lista, recusam a oferta
 * inteira em vez de serem ignorados.
 */
const CUSTOM_EXERCISE_FIELDS = [
  'ref',
  'name',
  'primaryMuscle',
  'equipment',
  'description',
] as const;

/**
 * Teto do corpo, em bytes UTF-8.
 *
 * Maior que o das outras rotas sociais (2–8 KiB) porque aqui o corpo carrega um treino inteiro —
 * ou, desde a T19.3, um programa inteiro. `MAX_SNAPSHOT_BYTES` e `MAX_PROGRAM_SNAPSHOT_BYTES` (no
 * serviço) são os tetos do snapshot em si; a folga cobre o envelope ao redor dele. O teto global do
 * processo continua sendo o do backup (4 MiB), que não faz sentido nenhum conceder a esta rota.
 */
export const MAX_WORKOUT_SHARE_REQUEST_BODY_BYTES = 320 * 1024;

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
  rejectUnknownFields(object, REQUEST_FIELDS, 'no corpo');

  const recipientSocialId = requireText(object.recipientSocialId, 'recipientSocialId');
  const clientRequestId = requireText(object.clientRequestId, 'clientRequestId');

  const hasTemplate = 'snapshot' in object;
  const hasProgram = 'programSnapshot' in object;
  if (hasTemplate === hasProgram) {
    throw invalid('a requisição precisa trazer exatamente um de snapshot ou programSnapshot');
  }

  if (hasTemplate) {
    const snapshot = requireObject(object.snapshot, 'snapshot');
    rejectUnknownFields(snapshot, SNAPSHOT_FIELDS, 'no snapshot');
    requireCustomExerciseList(snapshot.customExercises, 'snapshot');
    requireExerciseList(snapshot.exercises, 'snapshot');
    return {
      recipientSocialId,
      clientRequestId,
      content: {
        shareType: 'WORKOUT_TEMPLATE',
        snapshot: object.snapshot as WorkoutTemplateShareSnapshotV1,
      },
    };
  }

  const program = requireObject(object.programSnapshot, 'programSnapshot');
  rejectUnknownFields(program, PROGRAM_SNAPSHOT_FIELDS, 'no programSnapshot');
  requireCustomExerciseList(program.customExercises, 'programSnapshot');
  if (!Array.isArray(program.templates)) {
    throw invalid('programSnapshot.templates precisa ser uma lista');
  }
  program.templates.forEach((raw, index) => {
    const template = requireObject(raw, `o treino [${index}]`);
    rejectUnknownFields(template, PROGRAM_TEMPLATE_FIELDS, `no treino [${index}]`);
    requireExerciseList(template.exercises, `o treino [${index}]`);
  });

  return {
    recipientSocialId,
    clientRequestId,
    content: {
      shareType: 'WORKOUT_PROGRAM',
      snapshot: object.programSnapshot as WorkoutProgramShareSnapshotV1,
    },
  };
}

function requireCustomExerciseList(value: unknown, owner: string): void {
  if (value === undefined) return;
  if (!Array.isArray(value)) {
    throw invalid(`${owner}.customExercises precisa ser uma lista`);
  }
  value.forEach((raw, index) => {
    const custom = requireObject(raw, `o exercício personalizado [${index}]`);
    rejectUnknownFields(custom, CUSTOM_EXERCISE_FIELDS, `no exercício personalizado [${index}]`);
  });
}

function requireExerciseList(value: unknown, owner: string): void {
  if (!Array.isArray(value)) {
    throw invalid(`${owner}.exercises precisa ser uma lista`);
  }
  value.forEach((raw, index) => {
    const exercise = requireObject(raw, `o exercício [${index}]`);
    rejectUnknownFields(exercise, EXERCISE_FIELDS, `no exercício [${index}]`);
  });
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
