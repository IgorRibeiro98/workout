/**
 * O contrato de sincronização incremental entre o Spark Android e o Spark Backend (T16.6).
 *
 * O espelho Kotlin é `com.example.data.sync.SyncProtocol`. A descrição legível do protocolo —
 * revision, cursor, idempotência, conflito — vive em `docs/architecture/sync-protocol.md`.
 *
 * ## Sync não é backup
 *
 * ```text
 * T16.4   Android ──snapshot completo──▶ Spark Backend     backup, imutável, autocontido
 * T16.5   Android ◀──snapshot completo── Spark Backend     restore, substituição explícita
 * T16.6   Android ⇄ mudanças ⇄ Spark Backend               sync incremental
 * ```
 *
 * Os três coexistem e nenhum substitui o outro. O backup continua sendo o mecanismo de cópia
 * histórica; o sync converge cópias vivas do mesmo dataset.
 */

/** Versão do **protocolo de sync**. Não é `/v1`, não é o Room, não é `entitySchemaVersion`. */
export const SYNC_PROTOCOL_VERSION = 1;

/**
 * Os agregados que participam do sync incremental.
 *
 * É o mesmo conjunto de `SyncEntityType` da T16.3 — exatamente o que a Outbox sabe registrar. Os
 * payloads são validados pelo **mesmo** registry do backup (`BackupEntityRegistry`): não existe um
 * segundo schema de treino no servidor.
 *
 * `EXERCISE_OVERRIDE`, `WEEKLY_GOAL` e `USER_PREFERENCES` ficam **fora** do sync incremental na
 * T16.6 e continuam cobertos pelo backup completo — ver a pendência registrada em
 * `ARCHITECTURE.md`.
 */
export const SYNC_ENTITY_TYPES = [
  'WORKOUT_PROGRAM',
  'WORKOUT_TEMPLATE',
  'WORKOUT_SESSION',
  'CUSTOM_EXERCISE',
  'BODY_MEASUREMENT',
  'CHECK_IN',
] as const;

export type SyncEntityType = (typeof SYNC_ENTITY_TYPES)[number];

export function isSyncEntityType(value: string): value is SyncEntityType {
  return (SYNC_ENTITY_TYPES as readonly string[]).includes(value);
}

/*
 * A política de cada agregado — mutabilidade, exclusão e estratégia de conflito — vive em
 * [SyncEntityPolicyRegistry] (`sync.policy.ts`), com o espelho Kotlin em
 * `com.example.data.sync.SyncEntityPolicies`.
 *
 * Ela saiu daqui na T16.7 porque deixou de ser um enum de duas opções: `UPSERT qualquer coisa`
 * trataria uma sessão concluída como documento colaborativo, e `DELETE qualquer coisa` criaria
 * tombstone para um tipo que o domínio não sabe apagar.
 */

/**
 * As operações que uma mutação pode declarar. As mesmas duas da Outbox (T16.3).
 *
 * Desde a T16.7 as duas são aceitas: `DELETE` produz tombstone e entra no change log como
 * qualquer outra mudança (`sync.policy.ts` decide para quais agregados).
 */
export const SYNC_OPERATIONS = ['UPSERT', 'DELETE'] as const;
export type SyncOperation = (typeof SYNC_OPERATIONS)[number];

/**
 * O desfecho de **uma** mutação dentro de um push.
 *
 * Resultado por item, e não um veredito único do lote: uma mutação stale e uma válida no mesmo
 * push precisam receber respostas diferentes, e esconder isso atrás de um 400 faria o cliente
 * reenviar o que já foi aplicado.
 */
export const SYNC_MUTATION_STATUSES = [
  /** Aplicada agora. `serverRevision` e `serverSequence` vêm preenchidos. */
  'APPLIED',
  /**
   * O servidor já tinha este resultado: reenvio do mesmo `clientMutationId`, ou conteúdo
   * idêntico ao que já está gravado. O cliente confirma a Outbox do mesmo jeito.
   */
  'ALREADY_APPLIED',
  /** `baseRevision` desatualizada. O servidor devolve `currentRevision` e **não** aplica nada. */
  'STALE',
  /** Fora do contrato: payload, identidade ou relação inválida. Não adianta reenviar igual. */
  'INVALID',
  /** O servidor entende o pedido e não o suporta — tipo desconhecido, versão futura, exclusão de um agregado que o domínio não apaga. */
  'UNSUPPORTED',
  /**
   * A entidade tem tombstone no servidor: ela foi excluída, e este `UPSERT` a recriaria (T16.7).
   *
   * Nunca é aplicado. Recriar o que outro aparelho apagou é decisão do usuário, e ela nasce com
   * `syncId` novo — reaproveitar a identidade morta apagaria o significado do tombstone.
   */
  'REMOTE_DELETED',
  /** Sessão concluída com o mesmo `syncId` e conteúdo divergente. Nunca sobrescrita. */
  'IMMUTABLE_HISTORY_CONFLICT',
  /** Mesmo `clientMutationId`, conteúdo ou alvo diferente. Não reaplica. */
  'IDEMPOTENCY_CONFLICT',
] as const;

export type SyncMutationStatus = (typeof SYNC_MUTATION_STATUSES)[number];

export interface SyncMutationResult {
  readonly clientMutationId: string;
  readonly status: SyncMutationStatus;
  /** A revision resultante, quando a mutação foi aplicada ou reconhecida. */
  readonly serverRevision?: number;
  /** A posição no change log, quando existe. */
  readonly serverSequence?: number;
  /** A revision atual do servidor, quando o resultado é `STALE`. */
  readonly currentRevision?: number;
  /** Código curto do motivo, para diagnóstico. Nunca conteúdo do usuário. */
  readonly reason?: string;
}

export interface SyncPushResponse {
  readonly results: readonly SyncMutationResult[];
}

/** Uma mudança do change log, como o cliente a recebe. */
export interface SyncChangeResponse {
  readonly serverSequence: number;
  readonly entityType: SyncEntityType;
  readonly entitySyncId: string;
  readonly entitySchemaVersion: number;
  readonly serverRevision: number;
  readonly operation: SyncOperation;
  readonly payloadHash: string;
  /** Qual instalação originou a mudança. Diagnóstico e *echo suppression* — nunca segurança. */
  readonly originDeviceId: string;
  readonly createdAt: number;
  /**
   * O agregado inteiro, como estava naquela sequência — e `null` quando `operation` é `DELETE`.
   *
   * Um tombstone não carrega conteúdo: ele afirma que a entidade deixou de existir, e a identidade
   * mais a `serverRevision` são tudo que o outro aparelho precisa para aplicar isso. O estado
   * anterior continua no change log, nas sequências que vieram antes.
   */
  readonly payload: unknown;
}

export interface SyncPullResponse {
  readonly changes: readonly SyncChangeResponse[];
  /** Onde o cliente deve retomar. Igual ao cursor pedido quando nada veio. */
  readonly nextCursor: number;
  readonly hasMore: boolean;
}

/**
 * Códigos de erro do sync, no envelope da T16.0 (`{ error: { code, message, requestId } }`).
 *
 * São erros de **requisição inteira**. O desfecho de uma mutação individual é
 * [SyncMutationStatus], dentro de um `200` — um item stale não é um erro HTTP.
 */
export const SYNC_ERROR_CODES = {
  /** Corpo, `deviceId` ou lista de mutações fora do contrato. */
  INVALID_SYNC_REQUEST: 'INVALID_SYNC_REQUEST',
  /** Mais mutações, ou payload maior, do que o servidor aceita. */
  SYNC_PAYLOAD_TOO_LARGE: 'SYNC_PAYLOAD_TOO_LARGE',
  /** Cursor negativo, não inteiro ou além do que o servidor já emitiu. */
  INVALID_CURSOR: 'INVALID_CURSOR',
  /**
   * O cursor aponta para antes da mudança mais antiga que o servidor ainda guarda desta conta.
   *
   * Hoje nada compacta o change log e nada apaga tombstone, então isto só acontece se o banco do
   * servidor for restaurado de uma cópia mais nova que o aparelho. O aparelho precisa de um
   * rebaseline explícito — e **não** de um `cursor = 0` silencioso, que faria ele reprocessar a
   * conta inteira sem ninguém saber por quê.
   */
  CURSOR_EXPIRED: 'CURSOR_EXPIRED',
  /** Proteção simples por conta contra um app em laço. */
  SYNC_RATE_LIMITED: 'SYNC_RATE_LIMITED',
} as const;

export type SyncErrorCode = (typeof SYNC_ERROR_CODES)[keyof typeof SYNC_ERROR_CODES];

/** Motivos curtos devolvidos em `SyncMutationResult.reason`. Vocabulário fechado. */
export const SYNC_MUTATION_REASONS = {
  /** Exclusão de um agregado cuja política não permite `DELETE` remoto (`sync.policy.ts`). */
  DELETE_NOT_ALLOWED: 'DELETE_NOT_ALLOWED',
  /** `UPSERT` contra uma entidade com tombstone. */
  ENTITY_DELETED: 'ENTITY_DELETED',
  UNKNOWN_ENTITY_TYPE: 'UNKNOWN_ENTITY_TYPE',
  UNSUPPORTED_ENTITY_SCHEMA_VERSION: 'UNSUPPORTED_ENTITY_SCHEMA_VERSION',
  INVALID_PAYLOAD: 'INVALID_PAYLOAD',
  IDENTITY_MISMATCH: 'IDENTITY_MISMATCH',
  PAYLOAD_TOO_LARGE: 'PAYLOAD_TOO_LARGE',
  BASE_REVISION_AHEAD: 'BASE_REVISION_AHEAD',
  IMMUTABLE_HISTORY: 'IMMUTABLE_HISTORY',
} as const;
