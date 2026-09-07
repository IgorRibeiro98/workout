import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { SyncChangeResponse, SyncEntityType, SyncOperation } from './sync.contract';

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
 * A persistência do sync (T16.6).
 *
 * Três garantias vivem aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **atomicidade por mutação** — `sync_entities`, `sync_changes` e `sync_mutations` são
 *    escritas na **mesma** transação SQLite. Atualizar a entidade e falhar ao registrar a mudança
 *    deixaria os outros aparelhos sem nunca saber da alteração; registrar o ledger sem aplicar
 *    faria um reenvio devolver um resultado que não existe;
 * 2. **sequência do servidor** — `server_sequence` é `AUTOINCREMENT`, gerada pelo banco dentro da
 *    transação. Ela nunca vem de relógio, de contador em memória nem de `updated_at`;
 * 3. **isolamento por conta** — `owner_uid` está na cláusula `WHERE` de toda consulta, e não em
 *    uma verificação depois da leitura. A consulta que não pode devolver dado de outra conta é a
 *    que nunca o carrega.
 */
@Injectable()
export class SyncRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /** O estado atual de um agregado **daquela conta**. */
  findEntity(ownerUid: string, entityType: string, entitySyncId: string): StoredSyncEntity | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT entity_type, entity_sync_id, entity_schema_version, server_revision,
                last_server_sequence, payload_hash, deleted
         FROM sync_entities
         WHERE owner_uid = ? AND entity_type = ? AND entity_sync_id = ?`,
      )
      .get(ownerUid, entityType, entitySyncId) as EntityRow | undefined;
    if (!row) return null;
    return {
      entityType: row.entity_type as SyncEntityType,
      entitySyncId: row.entity_sync_id,
      entitySchemaVersion: row.entity_schema_version,
      serverRevision: row.server_revision,
      lastServerSequence: row.last_server_sequence,
      payloadHash: row.payload_hash,
      deleted: row.deleted === 1,
    };
  }

  /** A tentativa já registrada para aquele `clientMutationId`, se houver. */
  findMutation(ownerUid: string, clientMutationId: string): StoredMutation | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT client_mutation_id, entity_type, entity_sync_id, operation, payload_hash,
                result_revision, result_sequence
         FROM sync_mutations
         WHERE owner_uid = ? AND client_mutation_id = ?`,
      )
      .get(ownerUid, clientMutationId) as MutationRow | undefined;
    if (!row) return null;
    return {
      clientMutationId: row.client_mutation_id,
      entityType: row.entity_type,
      entitySyncId: row.entity_sync_id,
      operation: row.operation,
      payloadHash: row.payload_hash,
      resultRevision: row.result_revision,
      resultSequence: row.result_sequence,
    };
  }

  /**
   * Aplica a mutação: estado atual, log de mudança e ledger — **em uma transação**.
   *
   * A ordem dentro dela não é estética. A mudança é anexada depois de a entidade existir, e o
   * ledger depois das duas, porque é ele que autoriza um reenvio a devolver `ALREADY_APPLIED`:
   * um ledger gravado antes descreveria um resultado que a transação ainda podia desfazer.
   */
  applyMutation(input: ApplyMutationInput): AppliedMutation {
    const db = this.sqlite.connection;

    const transaction = db.transaction((): AppliedMutation => {
      const change = db
        .prepare(
          `INSERT INTO sync_changes
             (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
              operation, payload, payload_hash, origin_device_id, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
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
        );
      const serverSequence = Number(change.lastInsertRowid);

      // `INSERT ... ON CONFLICT DO UPDATE` e não "verificar e então escrever": a constraint de
      // identidade é quem resolve duas requisições simultâneas, não uma checagem em código.
      db.prepare(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (owner_uid, entity_type, entity_sync_id) DO UPDATE SET
           entity_schema_version = excluded.entity_schema_version,
           server_revision       = excluded.server_revision,
           last_server_sequence  = excluded.last_server_sequence,
           payload               = excluded.payload,
           payload_hash          = excluded.payload_hash,
           origin_device_id      = excluded.origin_device_id,
           updated_at            = excluded.updated_at
         WHERE sync_entities.server_revision < excluded.server_revision
           -- Ressurreição é impossível no banco, e não só por disciplina do serviço: um UPSERT
           -- que chegasse a um tombstone não atualiza linha nenhuma (T16.7).
           AND sync_entities.deleted = 0`,
      ).run(
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
      );

      db.prepare(
        `INSERT INTO sync_mutations
           (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
            base_revision, result_revision, result_sequence, payload_hash, applied_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).run(
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
      );

      return { serverRevision: input.nextRevision, serverSequence };
    });

    return transaction();
  }

  /**
   * Registra no ledger uma tentativa cujo resultado **já existia**.
   *
   * Acontece quando um `clientMutationId` novo carrega exatamente o conteúdo que o servidor já
   * tem: nada é aplicado, nenhuma revision é gasta e nenhuma mudança é anexada ao log — mas a
   * tentativa passa a ter resposta guardada, para que um reenvio dela não recomece o raciocínio.
   */
  recordConverged(input: {
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
  }): void {
    this.sqlite.connection
      .prepare(
        `INSERT OR IGNORE INTO sync_mutations
           (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
            base_revision, result_revision, result_sequence, payload_hash, applied_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
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
      );
  }

  /**
   * Marca a entidade como excluída — tombstone, change log e ledger, **na mesma transação**
   * (T16.7).
   *
   * A linha de `sync_entities` continua existindo, uma `revision` adiante: é ela que responde
   * "esta identidade morreu" para todo push futuro. O payload é esvaziado porque um tombstone não
   * afirma conteúdo nenhum — e o estado anterior continua no change log, nas sequências que vieram
   * antes, para quem ainda não as leu.
   *
   * A mudança anexada ao log carrega `payload = 'null'`: o outro aparelho precisa da identidade e
   * da `serverRevision`, e mandar de volta o conteúdo do que foi apagado só duplicaria dado
   * pessoal sem ninguém ter o que fazer com ele.
   */
  applyDelete(input: ApplyDeleteInput): AppliedMutation {
    const db = this.sqlite.connection;

    const transaction = db.transaction((): AppliedMutation => {
      const change = db
        .prepare(
          `INSERT INTO sync_changes
             (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
              operation, payload, payload_hash, origin_device_id, created_at)
           VALUES (?, ?, ?, ?, ?, 'DELETE', 'null', '', ?, ?)`,
        )
        .run(
          input.ownerUid,
          input.entityType,
          input.entitySyncId,
          input.entitySchemaVersion,
          input.nextRevision,
          input.deviceId,
          input.now,
        );
      const serverSequence = Number(change.lastInsertRowid);

      // `INSERT ... ON CONFLICT DO UPDATE` de novo, e não `UPDATE`: uma exclusão pode chegar para
      // uma identidade que este servidor nunca viu (dois aparelhos com o mesmo dataset restaurado,
      // e o primeiro push é o delete). Sem o tombstone nesse caso, o outro aparelho recriaria a
      // entidade depois — que é exatamente a ressurreição que a T16.7 existe para impedir.
      db.prepare(
        `INSERT INTO sync_entities
           (owner_uid, entity_type, entity_sync_id, entity_schema_version, server_revision,
            last_server_sequence, payload, payload_hash, origin_device_id, created_at, updated_at,
            deleted, deleted_at, deleted_by_device_id)
         VALUES (?, ?, ?, ?, ?, ?, '', '', ?, ?, ?, 1, ?, ?)
         ON CONFLICT (owner_uid, entity_type, entity_sync_id) DO UPDATE SET
           server_revision      = excluded.server_revision,
           last_server_sequence = excluded.last_server_sequence,
           payload              = '',
           payload_hash         = '',
           origin_device_id     = excluded.origin_device_id,
           updated_at           = excluded.updated_at,
           deleted              = 1,
           deleted_at           = excluded.deleted_at,
           deleted_by_device_id = excluded.deleted_by_device_id
         WHERE sync_entities.server_revision < excluded.server_revision`,
      ).run(
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
      );

      db.prepare(
        `INSERT INTO sync_mutations
           (owner_uid, client_mutation_id, device_id, entity_type, entity_sync_id, operation,
            base_revision, result_revision, result_sequence, payload_hash, applied_at)
         VALUES (?, ?, ?, ?, ?, 'DELETE', ?, ?, ?, '', ?)`,
      ).run(
        input.ownerUid,
        input.clientMutationId,
        input.deviceId,
        input.entityType,
        input.entitySyncId,
        input.baseRevision,
        input.nextRevision,
        serverSequence,
        input.now,
      );

      return { serverRevision: input.nextRevision, serverSequence };
    });

    return transaction();
  }

  /**
   * A **menor** sequência que o servidor ainda guarda desta conta, ou `0` se ela não tem mudanças.
   *
   * É contra ela que um cursor expirado é reconhecido. Hoje nada compacta o log, então o valor é
   * sempre a primeira mudança da conta — e a verificação só dispara se o banco do servidor for
   * restaurado de uma cópia mais nova que o aparelho.
   */
  oldestSequence(ownerUid: string): number {
    const row = this.sqlite.connection
      .prepare(
        'SELECT COALESCE(MIN(server_sequence), 0) AS min FROM sync_changes WHERE owner_uid = ?',
      )
      .get(ownerUid) as { min: number } | undefined;
    return row?.min ?? 0;
  }

  /**
   * A maior sequência já emitida pelo servidor.
   *
   * Global, e não por conta: `server_sequence` é uma coluna `AUTOINCREMENT` única, e é contra ela
   * que um cursor impossível é reconhecido. Ela não vaza dado de outra conta — é um número.
   */
  maxSequence(): number {
    const row = this.sqlite.connection
      .prepare('SELECT COALESCE(MAX(server_sequence), 0) AS max FROM sync_changes')
      .get() as { max: number } | undefined;
    return row?.max ?? 0;
  }

  /**
   * A página de mudanças **daquela conta** depois do cursor.
   *
   * Lê `limit + 1` para saber se há mais sem uma segunda consulta de contagem.
   */
  changesAfter(
    ownerUid: string,
    cursor: number,
    limit: number,
  ): { changes: SyncChangeResponse[]; hasMore: boolean } {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT server_sequence, entity_type, entity_sync_id, entity_schema_version,
                server_revision, operation, payload, payload_hash, origin_device_id, created_at
         FROM sync_changes
         WHERE owner_uid = ? AND server_sequence > ?
         ORDER BY server_sequence ASC
         LIMIT ?`,
      )
      .all(ownerUid, cursor, limit + 1) as ChangeRow[];

    const hasMore = rows.length > limit;
    const page = hasMore ? rows.slice(0, limit) : rows;

    return {
      hasMore,
      changes: page.map((row) => ({
        serverSequence: row.server_sequence,
        entityType: row.entity_type as SyncEntityType,
        entitySyncId: row.entity_sync_id,
        entitySchemaVersion: row.entity_schema_version,
        serverRevision: row.server_revision,
        operation: row.operation as SyncOperation,
        payloadHash: row.payload_hash,
        originDeviceId: row.origin_device_id,
        createdAt: row.created_at,
        // O texto canônico guardado volta como valor JSON. O que o cliente confere é o
        // `payloadHash`, calculado sobre a forma canônica — reserializar aqui não o afeta.
        payload: JSON.parse(row.payload) as unknown,
      })),
    };
  }

  /** Quantos agregados a conta tem no servidor. Diagnóstico e teste. */
  countEntities(ownerUid: string): number {
    const row = this.sqlite.connection
      .prepare('SELECT COUNT(*) AS total FROM sync_entities WHERE owner_uid = ?')
      .get(ownerUid) as { total: number } | undefined;
    return row?.total ?? 0;
  }
}

interface EntityRow {
  entity_type: string;
  entity_sync_id: string;
  entity_schema_version: number;
  server_revision: number;
  last_server_sequence: number;
  payload_hash: string;
  deleted: number;
}

interface MutationRow {
  client_mutation_id: string;
  entity_type: string;
  entity_sync_id: string;
  operation: string;
  payload_hash: string;
  result_revision: number;
  result_sequence: number;
}

interface ChangeRow {
  server_sequence: number;
  entity_type: string;
  entity_sync_id: string;
  entity_schema_version: number;
  server_revision: number;
  operation: string;
  payload: string;
  payload_hash: string;
  origin_device_id: string;
  created_at: number;
}
