import { z } from 'zod';
import { BackupEntityRegistry, identityMismatch } from '../backup/backup-entity.registry';
import { parseCanonical, sha256Hex, type CanonicalNode } from '../backup/canonical-json';
import {
  isSyncEntityType,
  SYNC_MUTATION_REASONS,
  SYNC_OPERATIONS,
  type SyncEntityType,
  type SyncOperation,
} from './sync.contract';
import { SyncErrors } from './sync.errors';
import { MAX_SYNC_PUSH_BODY_BYTES, SYNC_LIMITS } from './sync.limits';

/**
 * A validação de um push (T16.6).
 *
 * ```text
 * tamanho → forma canônica → envelope → mutação a mutação → payload → identidade
 * ```
 *
 * ## Duas camadas, de propósito
 *
 * O **envelope** é validado de forma binária: um corpo malformado, um `deviceId` ausente ou um
 * lote acima do teto recusam a requisição inteira, porque não há como saber o que o cliente quis
 * dizer. Já uma **mutação individual** fora do contrato não derruba o lote: ela recebe
 * `INVALID`/`UNSUPPORTED` no resultado dela, e as outras seguem. Um app com um agregado corrompido
 * não pode ficar impedido de sincronizar tudo o mais.
 *
 * ## O payload é validado pelo registry do backup
 *
 * Não existe um segundo schema de treino no servidor. `BackupEntityRegistry` é o mesmo que o
 * backup usa, o mesmo que espelha `com.example.data.sync.dto`, e é ele que recusa `entityType`
 * desconhecido, `entitySchemaVersion` que este servidor não sabe ler, campo a mais e identidade
 * que não corresponde ao payload — sem *fuzzy matching*, sem preencher com padrão.
 */

const identifier = z.string().trim().min(1).max(SYNC_LIMITS.maxIdLength);

const mutationSchema = z
  .object({
    clientMutationId: identifier,
    entityType: z.string().max(SYNC_LIMITS.maxIdLength),
    entitySyncId: identifier,
    entitySchemaVersion: z.number().int(),
    operation: z.string().max(32),
    /**
     * `null`/ausente é criação. Zero é aceito como sinônimo — o cliente que ainda não conhece a
     * entidade pode dizer qualquer um dos dois, e não vale transformar isso em erro.
     */
    baseRevision: z.number().int().min(0).nullish(),
    payload: z.unknown().nullish(),
  })
  .strict();

const pushEnvelopeSchema = z
  .object({
    deviceId: identifier,
    mutations: z.array(mutationSchema),
  })
  .strict();

/** Uma mutação que passou pelo envelope. O veredito por item vem depois, no serviço. */
export interface ParsedMutation {
  readonly clientMutationId: string;
  readonly entityType: string;
  readonly entitySyncId: string;
  readonly entitySchemaVersion: number;
  readonly operation: string;
  readonly baseRevision: number | null;
  /** O valor JavaScript do payload, para validação de schema. */
  readonly payload: unknown;
  /** O texto canônico do payload, verbatim — é sobre ele que o hash é calculado. */
  readonly canonicalPayload: string | null;
}

export interface ParsedPush {
  readonly deviceId: string;
  readonly mutations: readonly ParsedMutation[];
}

/**
 * Valida o corpo cru e devolve as mutações prontas para o serviço julgar.
 *
 * Recebe **texto**, e não um objeto já parseado, porque a forma canônica preserva os tokens
 * escalares originais: é isso que faz Kotlin e TypeScript chegarem ao mesmo `payloadHash` sem que
 * um precise imitar o formatador de ponto flutuante do outro (`canonical-json.ts`).
 */
export function parsePushRequest(rawBody: string): ParsedPush {
  if (Buffer.byteLength(rawBody, 'utf8') > MAX_SYNC_PUSH_BODY_BYTES) {
    throw SyncErrors.tooLarge('corpo do push acima do teto do servidor');
  }

  let document: CanonicalNode;
  try {
    document = parseCanonical(rawBody);
  } catch {
    throw SyncErrors.invalid('corpo não é um documento JSON válido');
  }

  const parsed = pushEnvelopeSchema.safeParse(document.value);
  if (!parsed.success) {
    throw SyncErrors.invalid(describe(parsed.error));
  }

  const envelope = parsed.data;
  if (envelope.mutations.length === 0) {
    throw SyncErrors.invalid('push sem mutações');
  }
  if (envelope.mutations.length > SYNC_LIMITS.maxMutations) {
    throw SyncErrors.tooLarge('mutações acima do teto por requisição');
  }

  // Duas mutações com o mesmo `clientMutationId` no mesmo corpo tornariam o resultado ambíguo:
  // a resposta é indexada por esse id, e o cliente não saberia a qual das duas ela se refere.
  const seen = new Set<string>();
  for (const mutation of envelope.mutations) {
    if (seen.has(mutation.clientMutationId)) {
      throw SyncErrors.invalid('clientMutationId repetido no mesmo push');
    }
    seen.add(mutation.clientMutationId);
  }

  const nodes = document.members?.get('mutations')?.elements ?? [];

  return {
    deviceId: envelope.deviceId,
    mutations: envelope.mutations.map((mutation, index) => ({
      clientMutationId: mutation.clientMutationId,
      entityType: mutation.entityType,
      entitySyncId: mutation.entitySyncId,
      entitySchemaVersion: mutation.entitySchemaVersion,
      operation: mutation.operation,
      baseRevision: mutation.baseRevision ?? null,
      payload: mutation.payload ?? null,
      canonicalPayload: nodes[index]?.members?.get('payload')?.text ?? null,
    })),
  };
}

/** O que uma mutação vira depois de aceita pelo contrato: conteúdo canônico e hash. */
export interface AcceptedMutation {
  readonly entityType: SyncEntityType;
  readonly operation: SyncOperation;
  readonly canonicalPayload: string;
  readonly payloadHash: string;
}

/** A recusa de uma mutação individual — um dos motivos fechados do contrato. */
export interface RejectedMutation {
  readonly reason: string;
  readonly unsupported: boolean;
}

export type MutationVerdict =
  | { readonly ok: true; readonly accepted: AcceptedMutation }
  | { readonly ok: false; readonly rejected: RejectedMutation };

/**
 * O veredito de contrato de **uma** mutação: tipo, versão, payload e identidade.
 *
 * Ainda não olha revision nem ledger — isso é do serviço, que é quem tem transação. Aqui só se
 * decide se o cliente disse algo que o servidor sabe ler.
 */
export function validateMutation(mutation: ParsedMutation): MutationVerdict {
  if (!(SYNC_OPERATIONS as readonly string[]).includes(mutation.operation)) {
    return reject(SYNC_MUTATION_REASONS.INVALID_PAYLOAD, false);
  }
  const operation = mutation.operation as SyncOperation;

  if (!isSyncEntityType(mutation.entityType)) {
    // Registry fechado: um tipo inventado não vira linha "para o caso de servir depois". E um
    // agregado que o backup conhece mas o sync ainda não (EXERCISE_OVERRIDE, WEEKLY_GOAL,
    // USER_PREFERENCES) é recusado aqui, não aceito pela metade.
    return reject(SYNC_MUTATION_REASONS.UNKNOWN_ENTITY_TYPE, true);
  }
  const entityType = mutation.entityType;

  if (operation === 'DELETE') {
    // T16.6 não propaga exclusão. Recusar explicitamente é o contrato: converter em `UPSERT`
    // ressuscitaria o que o usuário apagou, e aceitar em silêncio apagaria em outro aparelho sem
    // política de tombstone. A intenção continua pendente no aparelho até a T16.7.
    return reject(SYNC_MUTATION_REASONS.DELETE_NOT_SUPPORTED, true);
  }

  const definition = BackupEntityRegistry.definitionOf(entityType);
  if (!definition.supportedSchemaVersions.includes(mutation.entitySchemaVersion)) {
    return reject(SYNC_MUTATION_REASONS.UNSUPPORTED_ENTITY_SCHEMA_VERSION, true);
  }

  if (mutation.canonicalPayload === null || mutation.payload === null) {
    return reject(SYNC_MUTATION_REASONS.INVALID_PAYLOAD, false);
  }

  const bytes = Buffer.byteLength(mutation.canonicalPayload, 'utf8');
  if (bytes > SYNC_LIMITS.maxMutationPayloadBytes) {
    return reject(SYNC_MUTATION_REASONS.PAYLOAD_TOO_LARGE, false);
  }

  const payload = definition.schema.safeParse(mutation.payload);
  if (!payload.success) {
    return reject(SYNC_MUTATION_REASONS.INVALID_PAYLOAD, false);
  }

  const mismatch = identityMismatch(definition, mutation.entitySyncId, payload.data);
  if (mismatch !== null) {
    return reject(SYNC_MUTATION_REASONS.IDENTITY_MISMATCH, false);
  }

  return {
    ok: true,
    accepted: {
      entityType,
      operation,
      canonicalPayload: mutation.canonicalPayload,
      payloadHash: sha256Hex(mutation.canonicalPayload),
    },
  };
}

function reject(reason: string, unsupported: boolean): MutationVerdict {
  return { ok: false, rejected: { reason, unsupported } };
}

/**
 * O cursor do pull, validado.
 *
 * Ausente é zero — o começo. Negativo, não inteiro ou além do que o servidor já emitiu é recusa
 * explícita: recomeçar do zero em silêncio faria o aparelho reprocessar a conta inteira sem
 * ninguém saber por quê.
 */
export function parseCursor(raw: unknown, maxSequence: number): number {
  if (raw === undefined || raw === null || raw === '') {
    return 0;
  }
  if (typeof raw !== 'string' && typeof raw !== 'number') {
    throw SyncErrors.invalidCursor('cursor precisa ser um inteiro');
  }
  const text = String(raw).trim();
  if (!/^\d+$/.test(text)) {
    throw SyncErrors.invalidCursor('cursor precisa ser um inteiro não negativo');
  }
  const value = Number.parseInt(text, 10);
  if (!Number.isSafeInteger(value)) {
    throw SyncErrors.invalidCursor('cursor fora da faixa representável');
  }
  if (value > maxSequence) {
    throw SyncErrors.invalidCursor('cursor além da sequência conhecida pelo servidor');
  }
  return value;
}

/** O tamanho de página pedido, validado contra o teto. */
export function parseLimit(raw: unknown): number {
  if (raw === undefined || raw === null || raw === '') {
    return SYNC_LIMITS.defaultPullPageSize;
  }
  if (typeof raw !== 'string' && typeof raw !== 'number') {
    throw SyncErrors.invalid('limit precisa ser um inteiro positivo');
  }
  const text = String(raw).trim();
  if (!/^\d+$/.test(text)) {
    throw SyncErrors.invalid('limit precisa ser um inteiro positivo');
  }
  const value = Number.parseInt(text, 10);
  if (value < 1) {
    throw SyncErrors.invalid('limit precisa ser um inteiro positivo');
  }
  return Math.min(value, SYNC_LIMITS.maxPullPageSize);
}

function describe(error: z.ZodError): string {
  const issue = error.issues[0];
  if (!issue) return 'formato inválido';
  const path = issue.path.join('.') || '(raiz)';
  return `${path}: ${issue.code}`;
}
