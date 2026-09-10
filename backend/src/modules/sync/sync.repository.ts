import { Injectable } from '@nestjs/common';
import { PostgresService, type PoolClient } from '../../database/postgres.service';
import { sha256Hex } from '../backup/canonical-json';
import {
  SYNC_MUTATION_REASONS,
  type SyncChangeResponse,
  type SyncEntityType,
  type SyncMutationResult,
  type SyncOperation,
} from './sync.contract';
import { SyncEntityPolicyRegistry } from './sync.policy';
import { validateMutation, type ParsedMutation } from './sync.validator';

/** O estado atual de um agregado no servidor. */
export interface StoredSyncEntity {
  readonly entityType: SyncEntityType;
  readonly entitySyncId: string;
  readonly entitySchemaVersion: number;
  readonly serverRevision: number;
  readonly lastServerSequence: number;
  readonly payloadHash: string;
  /** `true` quando a linha é um tombstone: a entidade existiu e foi excluída (T16.7). */
  readonly deleted: boolean;
}

/**
 * O estado atual de um agregado **com o conteúdo** (T16.7.1).
 *
 * Separado de [StoredSyncEntity] de propósito: o push consulta a entidade uma vez por mutação e
 * precisa só de revision, hash e tombstone. Carregar o payload — até 256 KiB — em todas essas
 * leituras seria pagar o conteúdo inteiro para responder "qual é a revision?".
 */
export interface StoredSyncEntitySnapshot extends StoredSyncEntity {
  /** O texto canônico guardado, ou `null` num tombstone (que não afirma conteúdo). */
  readonly payload: string | null;
}

/** Uma tentativa de mutação já registrada no ledger. */
export interface StoredMutation {
  readonly clientMutationId: string;
  readonly entityType: string;
  readonly entitySyncId: string;
  readonly operation: string;
  readonly payloadHash: string;
  readonly resultRevision: number;
  readonly resultSequence: number;
}

export interface ApplyMutationInput {
  readonly ownerUid: string;
  readonly deviceId: string;
  readonly clientMutationId: string;
  readonly entityType: SyncEntityType;
  readonly entitySyncId: string;
  readonly entitySchemaVersion: number;
  readonly operation: SyncOperation;
  readonly baseRevision: number | null;
  readonly canonicalPayload: string;
  readonly payloadHash: string;
  /** A revision que esta aplicação produz. Calculada pelo serviço, aplicada aqui. */
  readonly nextRevision: number;
  readonly now: number;
}

/** Uma exclusão pronta para virar tombstone. Não há payload: uma exclusão não afirma conteúdo. */
export interface ApplyDeleteInput {
  readonly ownerUid: string;
  readonly deviceId: string;
  readonly clientMutationId: string;
  readonly entityType: SyncEntityType;
  readonly entitySyncId: string;
  readonly entitySchemaVersion: number;
  readonly baseRevision: number | null;
  /** A revision que o tombstone produz. Exclusão também gasta revision (T16.7). */
  readonly nextRevision: number;
  readonly now: number;
}

export interface AppliedMutation {
  readonly serverRevision: number;
  readonly serverSequence: number;
}

/**
 * A persistência do sync (T16.6 / T18.0 PostgreSQL).
 *
 * Três garantias vivem aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **atomicidade por mutação** — `sync_entities`, `sync_changes` e `sync_mutations` são
 *    escritas na **mesma** transação PostgreSQL.
 * 2. **sequência do servidor** — `server_sequence` é `BIGSERIAL`, gerada pelo banco dentro da
 *    transação. Ela nunca vem de relógio, de contador em memória nem de `updated_at`;
 * 3. **isolamento por conta** — `owner_uid` está na cláusula `WHERE` de toda consulta.
 */
@Injectable()
export class SyncRepository {
  constructor(private readonly db: PostgresService) {}

  /** O estado atual de um agregado **daquela conta**. */
  async findEntity(
    ownerUid: string,
    entityType: string,
    entitySyncId: string,
  ): Promise<StoredSyncEntity | null> {
    const res = await this.db.query<EntityRow>(
      `SELECT entity_type, entity_sync_id, entity_schema_version, server_revision,
              last_server_sequence, payload_hash, deleted
       FROM sync_entities
       WHERE owner_uid = $1 AND entity_type = $2 AND entity_sync_id = $3`,
      [ownerUid, entityType, entitySyncId],
    );
    const row = res.rows[0];
    if (!row) return null;
    return {
      entityType: row.entity_type as SyncEntityType,
      entitySyncId: row.entity_sync_id,
      entitySchemaVersion: row.entity_schema_version,
      serverRevision: row.server_revision,
      lastServerSequence: Number(row.last_server_sequence),
      payloadHash: row.payload_hash,
      deleted: Boolean(row.deleted),
    };
  }

  /**
   * O estado atual de um agregado **daquela conta**, com o conteúdo (T16.7.1).
   */
  async findEntitySnapshot(
    ownerUid: string,
    entityType: string,
    entitySyncId: string,
  ): Promise<StoredSyncEntitySnapshot | null> {
    const res = await this.db.query<EntityRow & { payload: string }>(
      `SELECT entity_type, entity_sync_id, entity_schema_version, server_revision,
              last_server_sequence, payload, payload_hash, deleted
       FROM sync_entities
       WHERE owner_uid = $1 AND entity_type = $2 AND entity_sync_id = $3`,
      [ownerUid, entityType, entitySyncId],
    );
    const row = res.rows[0];
    if (!row) return null;
    const deleted = Boolean(row.deleted);
    return {
      entityType: row.entity_type as SyncEntityType,
      entitySyncId: row.entity_sync_id,
      entitySchemaVersion: row.entity_schema_version,
      serverRevision: row.server_revision,
      lastServerSequence: Number(row.last_server_sequence),
      payloadHash: row.payload_hash,
      deleted,
      payload: deleted || row.payload === '' ? null : row.payload,
    };
  }

  /** A tentativa já registrada para aquele `clientMutationId`, se houver. */
  async findMutation(ownerUid: string, clientMutationId: string): Promise<StoredMutation | null> {
    const res = await this.db.query<MutationRow>(
      `SELECT client_mutation_id, entity_type, entity_sync_id, operation, payload_hash,
              result_revision, result_sequence
       FROM sync_mutations
       WHERE owner_uid = $1 AND client_mutation_id = $2`,
      [ownerUid, clientMutationId],
    );
    const row = res.rows[0];
    if (!row) return null;
    return {
      clientMutationId: row.client_mutation_id,
      entityType: row.entity_type,
      entitySyncId: row.entity_sync_id,
      operation: row.operation,
      payloadHash: row.payload_hash,
      resultRevision: row.result_revision,
      resultSequence: Number(row.result_sequence),
    };
  }

  /**
   * Executa a decisão de revisão e mutação de forma estritamente atômica e serializada.
   */
  async executeAtomicMutation(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    now: number,
  ): Promise<SyncMutationResult> {
    return this.db.transaction(async (client) => {
      // 1. Serializa chamadas concorrentes com o mesmo clientMutationId
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
        ownerUid,
        `mut:${mutation.clientMutationId}`,
      ]);

      // Verifica idempotência dentro do lock de mutação
      const ledgerRes = await client.query<MutationRow>(
        `SELECT client_mutation_id, entity_type, entity_sync_id, operation, payload_hash,
                result_revision, result_sequence
         FROM sync_mutations
         WHERE owner_uid = $1 AND client_mutation_id = $2`,
        [ownerUid, mutation.clientMutationId],
      );
      const ledger = ledgerRes.rows[0];
      if (ledger) {
        const hash =
          mutation.operation === 'DELETE'
            ? ''
            : mutation.canonicalPayload
              ? sha256Hex(mutation.canonicalPayload)
              : '';
        const sameIntent =
          ledger.entity_type === mutation.entityType &&
          ledger.entity_sync_id === mutation.entitySyncId &&
          ledger.operation === mutation.operation &&
          ledger.payload_hash === hash;

        if (!sameIntent) {
          return {
            clientMutationId: mutation.clientMutationId,
            status: 'IDEMPOTENCY_CONFLICT',
          };
        }
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'ALREADY_APPLIED',
          serverRevision: ledger.result_revision,
          serverSequence: Number(ledger.result_sequence),
        };
      }

      // 2. Validação estrutural e de contrato
      const verdict = validateMutation(mutation);
      if (!verdict.ok) {
        return {
          clientMutationId: mutation.clientMutationId,
          status: verdict.rejected.unsupported ? 'UNSUPPORTED' : 'INVALID',
          reason: verdict.rejected.reason,
        };
      }
      const accepted = verdict.accepted;

      // 3. Serializa operações concorrentes no mesmo agregado da mesma conta
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
        ownerUid,
        `ent:${accepted.entityType}:${mutation.entitySyncId}`,
      ]);

      // 4. Lê o estado atual da entidade dentro da transação e do lock
      const entityRes = await client.query<EntityRow>(
        `SELECT entity_type, entity_sync_id, entity_schema_version, server_revision,
                last_server_sequence, payload_hash, deleted
         FROM sync_entities
         WHERE owner_uid = $1 AND entity_type = $2 AND entity_sync_id = $3`,
        [ownerUid, accepted.entityType, mutation.entitySyncId],
      );
      const entityRow = entityRes.rows[0];
      const entity = entityRow
        ? {
            entityType: entityRow.entity_type as SyncEntityType,
            entitySyncId: entityRow.entity_sync_id,
            entitySchemaVersion: entityRow.entity_schema_version,
            serverRevision: entityRow.server_revision,
            lastServerSequence: Number(entityRow.last_server_sequence),
            payloadHash: entityRow.payload_hash,
            deleted: Boolean(entityRow.deleted),
          }
        : null;

      // 5. Decisão de exclusão (DELETE)
      if (accepted.operation === 'DELETE') {
        if (entity?.deleted) {
          await this.recordConvergedWithClient(client, {
            ownerUid,
            deviceId,
            clientMutationId: mutation.clientMutationId,
            entityType: mutation.entityType,
            entitySyncId: mutation.entitySyncId,
            operation: mutation.operation,
            baseRevision: mutation.baseRevision,
            payloadHash: '',
            resultRevision: entity.serverRevision,
            resultSequence: entity.lastServerSequence,
            now,
          });
          return {
            clientMutationId: mutation.clientMutationId,
            status: 'ALREADY_APPLIED',
            serverRevision: entity.serverRevision,
            serverSequence: entity.lastServerSequence,
          };
        }

        const base = mutation.baseRevision ?? 0;
        if (!entity) {
          return this.applyDeleteWithClient(client, {
            ownerUid,
            deviceId,
            clientMutationId: mutation.clientMutationId,
            entityType: accepted.entityType,
            entitySyncId: mutation.entitySyncId,
            entitySchemaVersion: mutation.entitySchemaVersion,
            baseRevision: mutation.baseRevision,
            nextRevision: 1,
            now,
          });
        }

        if (base > entity.serverRevision) {
          return {
            clientMutationId: mutation.clientMutationId,
            status: 'INVALID',
            currentRevision: entity.serverRevision,
            reason: SYNC_MUTATION_REASONS.BASE_REVISION_AHEAD,
          };
        }

        if (base !== entity.serverRevision) {
          return {
            clientMutationId: mutation.clientMutationId,
            status: 'STALE',
            currentRevision: entity.serverRevision,
          };
        }

        return this.applyDeleteWithClient(client, {
          ownerUid,
          deviceId,
          clientMutationId: mutation.clientMutationId,
          entityType: accepted.entityType,
          entitySyncId: mutation.entitySyncId,
          entitySchemaVersion: mutation.entitySchemaVersion,
          baseRevision: mutation.baseRevision,
          nextRevision: entity.serverRevision + 1,
          now,
        });
      }

      // 6. Decisão de escrita (UPSERT)
      if (entity?.deleted) {
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'REMOTE_DELETED',
          currentRevision: entity.serverRevision,
          reason: SYNC_MUTATION_REASONS.ENTITY_DELETED,
        };
      }

      if (SyncEntityPolicyRegistry.isImmutableHistory(accepted.entityType)) {
        if (!entity) {
          return this.applyMutationWithClient(client, {
            ownerUid,
            deviceId,
            clientMutationId: mutation.clientMutationId,
            entityType: accepted.entityType,
            entitySyncId: mutation.entitySyncId,
            entitySchemaVersion: mutation.entitySchemaVersion,
            operation: 'UPSERT',
            baseRevision: mutation.baseRevision,
            canonicalPayload: accepted.canonicalPayload,
            payloadHash: accepted.payloadHash,
            nextRevision: 1,
            now,
          });
        }
        if (entity.payloadHash === accepted.payloadHash) {
          await this.recordConvergedWithClient(client, {
            ownerUid,
            deviceId,
            clientMutationId: mutation.clientMutationId,
            entityType: mutation.entityType,
            entitySyncId: mutation.entitySyncId,
            operation: mutation.operation,
            baseRevision: mutation.baseRevision,
            payloadHash: accepted.payloadHash,
            resultRevision: entity.serverRevision,
            resultSequence: entity.lastServerSequence,
            now,
          });
          return {
            clientMutationId: mutation.clientMutationId,
            status: 'ALREADY_APPLIED',
            serverRevision: entity.serverRevision,
            serverSequence: entity.lastServerSequence,
          };
        }
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'IMMUTABLE_HISTORY_CONFLICT',
          currentRevision: entity.serverRevision,
          reason: SYNC_MUTATION_REASONS.IMMUTABLE_HISTORY,
        };
      }

      const base = mutation.baseRevision ?? 0;
      if (!entity) {
        if (base === 0) {
          return this.applyMutationWithClient(client, {
            ownerUid,
            deviceId,
            clientMutationId: mutation.clientMutationId,
            entityType: accepted.entityType,
            entitySyncId: mutation.entitySyncId,
            entitySchemaVersion: mutation.entitySchemaVersion,
            operation: 'UPSERT',
            baseRevision: mutation.baseRevision,
            canonicalPayload: accepted.canonicalPayload,
            payloadHash: accepted.payloadHash,
            nextRevision: 1,
            now,
          });
        }
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'STALE',
          currentRevision: 0,
        };
      }

      if (base > entity.serverRevision) {
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'INVALID',
          currentRevision: entity.serverRevision,
          reason: SYNC_MUTATION_REASONS.BASE_REVISION_AHEAD,
        };
      }

      if (accepted.payloadHash === entity.payloadHash) {
        await this.recordConvergedWithClient(client, {
          ownerUid,
          deviceId,
          clientMutationId: mutation.clientMutationId,
          entityType: mutation.entityType,
          entitySyncId: mutation.entitySyncId,
          operation: mutation.operation,
          baseRevision: mutation.baseRevision,
          payloadHash: accepted.payloadHash,
          resultRevision: entity.serverRevision,
          resultSequence: entity.lastServerSequence,
          now,
        });
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'ALREADY_APPLIED',
          serverRevision: entity.serverRevision,
          serverSequence: entity.lastServerSequence,
        };
      }

      if (base === entity.serverRevision && base > 0) {
        return this.applyMutationWithClient(client, {
          ownerUid,
          deviceId,
          clientMutationId: mutation.clientMutationId,
          entityType: accepted.entityType,
          entitySyncId: mutation.entitySyncId,
          entitySchemaVersion: mutation.entitySchemaVersion,
          operation: 'UPSERT',
          baseRevision: mutation.baseRevision,
          canonicalPayload: accepted.canonicalPayload,
          payloadHash: accepted.payloadHash,
          nextRevision: entity.serverRevision + 1,
          now,
        });
      }

      return {
        clientMutationId: mutation.clientMutationId,
        status: 'STALE',
        currentRevision: entity.serverRevision,
      };
    });
  }

  private async applyMutationWithClient(
    client: PoolClient,
    input: ApplyMutationInput,
  ): Promise<SyncMutationResult> {
    const changeRes = await client.query<{ server_sequence: string | number }>(
      `INSERT INTO sync_changes
         (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
          operation, payload, payload_hash, origin_device_id, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       RETURNING server_sequence`,
      [
        input.ownerUid,
        input.entityType,
        input.entitySyncId,
        input.entitySchemaVersion,
        input.nextRevision,
        input.operation,
        input.canonicalPayload,
        input.payloadHash,
        input.deviceId,
        input.now,
      ],
    );
    const serverSequence = Number(changeRes.rows[0].server_sequence);

    const entityRes = await client.query(
      `INSERT INTO sync_entities
         (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
          last_server_sequence, payload, payload_hash, origin_device_id, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
       ON CONFLICT (owner_uid, entity_type, entity_sync_id) DO UPDATE SET
         entity_schema_version = EXCLUDED.entity_schema_version,
         server_revision       = EXCLUDED.server_revision,
         last_server_sequence  = EXCLUDED.last_server_sequence,
         payload               = EXCLUDED.payload,
         payload_hash          = EXCLUDED.payload_hash,
         origin_device_id      = EXCLUDED.origin_device_id,
         updated_at            = EXCLUDED.updated_at
       WHERE sync_entities.server_revision < EXCLUDED.server_revision
         AND sync_entities.deleted = FALSE`,
      [
        input.ownerUid,
        input.entityType,
        input.entitySyncId,
        input.entitySchemaVersion,
        input.nextRevision,
        serverSequence,
        input.canonicalPayload,
        input.payloadHash,
        input.deviceId,
        input.now,
        input.now,
      ],
    );

    if (entityRes.rowCount !== 1) {
      throw new Error(
        `Sync CAS conflict: entity ${input.entityType}:${input.entitySyncId} revision ${input.nextRevision} failed CAS`,
      );
    }

    await client.query(
      `INSERT INTO sync_mutations
         (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
          base_revision, result_revision, result_sequence, payload_hash, applied_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
      [
        input.ownerUid,
        input.clientMutationId,
        input.deviceId,
        input.entityType,
        input.entitySyncId,
        input.operation,
        input.baseRevision,
        input.nextRevision,
        serverSequence,
        input.payloadHash,
        input.now,
      ],
    );

    return {
      clientMutationId: input.clientMutationId,
      status: 'APPLIED',
      serverRevision: input.nextRevision,
      serverSequence,
    };
  }

  private async applyDeleteWithClient(
    client: PoolClient,
    input: ApplyDeleteInput,
  ): Promise<SyncMutationResult> {
    const changeRes = await client.query<{ server_sequence: string | number }>(
      `INSERT INTO sync_changes
         (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
          operation, payload, payload_hash, origin_device_id, created_at)
       VALUES ($1, $2, $3, $4, $5, 'DELETE', 'null', '', $6, $7)
       RETURNING server_sequence`,
      [
        input.ownerUid,
        input.entityType,
        input.entitySyncId,
        input.entitySchemaVersion,
        input.nextRevision,
        input.deviceId,
        input.now,
      ],
    );
    const serverSequence = Number(changeRes.rows[0].server_sequence);

    const entityRes = await client.query(
      `INSERT INTO sync_entities
         (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
          last_server_sequence, payload, payload_hash, origin_device_id, created_at, updated_at,
          deleted, deleted_at, deleted_by_device_id)
       VALUES ($1, $2, $3, $4, $5, $6, '', '', $7, $8, $9, TRUE, $10, $11)
       ON CONFLICT (owner_uid, entity_type, entity_sync_id) DO UPDATE SET
         server_revision      = EXCLUDED.server_revision,
         last_server_sequence = EXCLUDED.last_server_sequence,
         payload              = '',
         payload_hash         = '',
         origin_device_id     = EXCLUDED.origin_device_id,
         updated_at           = EXCLUDED.updated_at,
         deleted              = TRUE,
         deleted_at           = EXCLUDED.deleted_at,
         deleted_by_device_id = EXCLUDED.deleted_by_device_id
       WHERE sync_entities.server_revision < EXCLUDED.server_revision`,
      [
        input.ownerUid,
        input.entityType,
        input.entitySyncId,
        input.entitySchemaVersion,
        input.nextRevision,
        serverSequence,
        input.deviceId,
        input.now,
        input.now,
        input.now,
        input.deviceId,
      ],
    );

    if (entityRes.rowCount !== 1) {
      throw new Error(
        `Sync CAS conflict: entity ${input.entityType}:${input.entitySyncId} revision ${input.nextRevision} failed CAS delete`,
      );
    }

    await client.query(
      `INSERT INTO sync_mutations
         (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
          base_revision, result_revision, result_sequence, payload_hash, applied_at)
       VALUES ($1, $2, $3, $4, $5, 'DELETE', $6, $7, $8, '', $9)`,
      [
        input.ownerUid,
        input.clientMutationId,
        input.deviceId,
        input.entityType,
        input.entitySyncId,
        input.baseRevision,
        input.nextRevision,
        serverSequence,
        input.now,
      ],
    );

    return {
      clientMutationId: input.clientMutationId,
      status: 'APPLIED',
      serverRevision: input.nextRevision,
      serverSequence,
    };
  }

  private async recordConvergedWithClient(
    client: PoolClient,
    input: {
      readonly ownerUid: string;
      readonly deviceId: string;
      readonly clientMutationId: string;
      readonly entityType: string;
      readonly entitySyncId: string;
      readonly operation: string;
      readonly baseRevision: number | null;
      readonly payloadHash: string;
      readonly resultRevision: number;
      readonly resultSequence: number;
      readonly now: number;
    },
  ): Promise<void> {
    await client.query(
      `INSERT INTO sync_mutations
         (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
          base_revision, result_revision, result_sequence, payload_hash, applied_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
       ON CONFLICT (owner_uid, client_mutation_id) DO NOTHING`,
      [
        input.ownerUid,
        input.clientMutationId,
        input.deviceId,
        input.entityType,
        input.entitySyncId,
        input.operation,
        input.baseRevision,
        input.resultRevision,
        input.resultSequence,
        input.payloadHash,
        input.now,
      ],
    );
  }

  /**
   * Aplica a mutação: estado atual, log de mudança e ledger — **em uma transação**.
   */
  async applyMutation(input: ApplyMutationInput): Promise<AppliedMutation> {
    return this.db.transaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
        input.ownerUid,
        `ent:${input.entityType}:${input.entitySyncId}`,
      ]);
      const res = await this.applyMutationWithClient(client, input);
      return {
        serverRevision: res.serverRevision!,
        serverSequence: res.serverSequence!,
      };
    });
  }

  /**
   * Registra no ledger uma tentativa cujo resultado **já existia**.
   */
  async recordConverged(input: {
    readonly ownerUid: string;
    readonly deviceId: string;
    readonly clientMutationId: string;
    readonly entityType: string;
    readonly entitySyncId: string;
    readonly operation: string;
    readonly baseRevision: number | null;
    readonly payloadHash: string;
    readonly resultRevision: number;
    readonly resultSequence: number;
    readonly now: number;
  }): Promise<void> {
    await this.db.query(
      `INSERT INTO sync_mutations
         (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
          base_revision, result_revision, result_sequence, payload_hash, applied_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
       ON CONFLICT (owner_uid, client_mutation_id) DO NOTHING`,
      [
        input.ownerUid,
        input.clientMutationId,
        input.deviceId,
        input.entityType,
        input.entitySyncId,
        input.operation,
        input.baseRevision,
        input.resultRevision,
        input.resultSequence,
        input.payloadHash,
        input.now,
      ],
    );
  }

  /**
   * Marca a entidade como excluída — tombstone, change log e ledger, **na mesma transação** (T16.7).
   */
  async applyDelete(input: ApplyDeleteInput): Promise<AppliedMutation> {
    return this.db.transaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
        input.ownerUid,
        `ent:${input.entityType}:${input.entitySyncId}`,
      ]);
      const res = await this.applyDeleteWithClient(client, input);
      return {
        serverRevision: res.serverRevision!,
        serverSequence: res.serverSequence!,
      };
    });
  }

  /**
   * A menor sequência que o servidor ainda guarda desta conta, ou `0` se ela não tem mudanças.
   */
  async oldestSequence(ownerUid: string): Promise<number> {
    const res = await this.db.query<{ min: string | number | null }>(
      'SELECT COALESCE(MIN(server_sequence), 0) AS min FROM sync_changes WHERE owner_uid = $1',
      [ownerUid],
    );
    return Number(res.rows[0]?.min ?? 0);
  }

  /**
   * A maior sequência já emitida pelo servidor.
   */
  async maxSequence(): Promise<number> {
    const res = await this.db.query<{ max: string | number | null }>(
      'SELECT COALESCE(MAX(server_sequence), 0) AS max FROM sync_changes',
    );
    return Number(res.rows[0]?.max ?? 0);
  }

  /**
   * A página de mudanças **daquela conta** depois do cursor.
   */
  async changesAfter(
    ownerUid: string,
    cursor: number,
    limit: number,
  ): Promise<{ changes: SyncChangeResponse[]; hasMore: boolean }> {
    const res = await this.db.query<ChangeRow>(
      `SELECT server_sequence, entity_type, entity_sync_id, entity_schema_version,
              server_revision, operation, payload, payload_hash, origin_device_id, created_at
       FROM sync_changes
       WHERE owner_uid = $1 AND server_sequence > $2
       ORDER BY server_sequence ASC
       LIMIT $3`,
      [ownerUid, cursor, limit + 1],
    );
    const rows = res.rows;
    const hasMore = rows.length > limit;
    const page = hasMore ? rows.slice(0, limit) : rows;

    return {
      hasMore,
      changes: page.map((row) => ({
        serverSequence: Number(row.server_sequence),
        entityType: row.entity_type as SyncEntityType,
        entitySyncId: row.entity_sync_id,
        entitySchemaVersion: row.entity_schema_version,
        serverRevision: row.server_revision,
        operation: row.operation as SyncOperation,
        payloadHash: row.payload_hash,
        originDeviceId: row.origin_device_id,
        createdAt: Number(row.created_at),
        payload: JSON.parse(row.payload) as unknown,
      })),
    };
  }

  /** Quantos agregados a conta tem no servidor. Diagnóstico e teste. */
  async countEntities(ownerUid: string): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      'SELECT COUNT(*) AS total FROM sync_entities WHERE owner_uid = $1',
      [ownerUid],
    );
    return Number(res.rows[0]?.total ?? 0);
  }
}

interface EntityRow {
  entity_type: string;
  entity_sync_id: string;
  entity_schema_version: number;
  server_revision: number;
  last_server_sequence: string | number;
  payload_hash: string;
  deleted: boolean | number;
}

interface MutationRow {
  client_mutation_id: string;
  entity_type: string;
  entity_sync_id: string;
  operation: string;
  payload_hash: string;
  result_revision: number;
  result_sequence: string | number;
}

interface ChangeRow {
  server_sequence: string | number;
  entity_type: string;
  entity_sync_id: string;
  entity_schema_version: number;
  server_revision: number;
  operation: string;
  payload: string;
  payload_hash: string;
  origin_device_id: string;
  created_at: string | number;
}
