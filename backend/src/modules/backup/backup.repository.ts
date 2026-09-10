import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import type { BackupMetadataResponse } from './backup.contract';
import type { ValidatedSnapshot } from './backup.validator';

export interface StoredSnapshot extends BackupMetadataResponse {
  /** Sequência interna do servidor. Não sai na API — ordena retenção e "o mais recente". */
  readonly sequence: number;
  readonly ownerUid: string;
  /**
   * O objeto que guarda o documento canônico (T18.1). `null` num snapshot anterior à T18.1 que
   * ainda não foi migrado. Nunca sai na API: o Android não conhece bucket nem chave.
   */
  readonly storageKey: string | null;
}

/** De onde o documento canônico de um snapshot pode ser lido (T18.1 §31). */
export interface PayloadSource {
  /** O objeto no Object Storage, quando o snapshot é da T18.1 ou já foi migrado. */
  readonly storageKey: string | null;
  /** O texto guardado no PostgreSQL pela T16.5, quando o snapshot ainda não foi migrado. */
  readonly legacyPayload: string | null;
}

/** O que a retenção removeu: quantas linhas, e quais objetos ficaram para apagar (T18.1 §28). */
export interface PrunedSnapshots {
  readonly count: number;
  readonly storageKeys: readonly string[];
}

/** Um snapshot anterior à T18.1 ainda com o documento no banco — o alvo do migrador (§32). */
export interface LegacySnapshot {
  readonly sequence: number;
  readonly backupId: string;
  readonly ownerUid: string;
  readonly payloadHash: string;
  readonly sizeBytes: number;
}

const SNAPSHOT_COLUMNS = `id, backup_id, owner_uid, client_backup_id, backup_schema_version,
              payload_hash, item_count, size_bytes, created_at, storage_key`;

/**
 * A persistência dos snapshots (T16.4 / T18.0 PostgreSQL / T18.1 Object Storage).
 *
 * Duas garantias vivem aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **atomicidade** — snapshot e itens entram na mesma transação PostgreSQL. Falhar no item N não
 *    deixa N-1 itens nem um snapshot vazio para trás;
 * 2. **ordem da retenção** — o novo backup é gravado e confirmado **antes** de qualquer limpeza.
 *
 * ## O que o banco guarda desde a T18.1
 *
 * Metadata, hashes e a chave do objeto. O documento canônico inteiro vive no Object Storage
 * (`BackupPayloadStore`), e `backup_items` guarda só identidade, versão de schema e
 * `content_hash` de cada agregado — o payload deles já está no documento, e duplicá-lo aqui era
 * o que consumia o armazenamento do PostgreSQL sem servir a leitura nenhuma. As colunas
 * `backup_snapshots.payload` e `backup_items.payload` continuam existindo para os snapshots
 * anteriores, até o migrador (`migrate-backup-payloads-to-object-storage`) esvaziá-las.
 */
@Injectable()
export class BackupRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * Os backups retidos **daquela conta**, do mais recente para o mais antigo.
   */
  async listFor(ownerUid: string): Promise<StoredSnapshot[]> {
    const res = await this.db.query<SnapshotRow>(
      `SELECT ${SNAPSHOT_COLUMNS}
       FROM backup_snapshots
       WHERE owner_uid = $1
       ORDER BY id DESC`,
      [ownerUid],
    );
    return res.rows.map(toStored);
  }

  /**
   * Um backup **da conta informada**, pelo `backupId` opaco.
   */
  async findByBackupId(ownerUid: string, backupId: string): Promise<StoredSnapshot | null> {
    const res = await this.db.query<SnapshotRow>(
      `SELECT ${SNAPSHOT_COLUMNS}
       FROM backup_snapshots
       WHERE owner_uid = $1 AND backup_id = $2`,
      [ownerUid, backupId],
    );
    const row = res.rows[0];
    return row ? toStored(row) : null;
  }

  /**
   * De onde ler o documento canônico do snapshot (T18.1 §31).
   *
   * `storage_key` presente → Object Storage. Ausente com `payload` presente → o texto legado da
   * T16.5, verbatim. Nenhum dos dois → o snapshot é anterior à T16.5 e não tem documento.
   */
  async findPayloadSource(ownerUid: string, backupId: string): Promise<PayloadSource | null> {
    const res = await this.db.query<{ storage_key: string | null; payload: string | null }>(
      `SELECT storage_key, payload FROM backup_snapshots WHERE owner_uid = $1 AND backup_id = $2`,
      [ownerUid, backupId],
    );
    const row = res.rows[0];
    if (!row) {
      return null;
    }
    return { storageKey: row.storage_key, legacyPayload: row.payload };
  }

  /** O backup já existente para aquela tentativa lógica, se houver. */
  async findByClientBackupId(
    ownerUid: string,
    clientBackupId: string,
  ): Promise<StoredSnapshot | null> {
    const res = await this.db.query<SnapshotRow>(
      `SELECT ${SNAPSHOT_COLUMNS}
       FROM backup_snapshots
       WHERE owner_uid = $1 AND client_backup_id = $2`,
      [ownerUid, clientBackupId],
    );
    const row = res.rows[0];
    return row ? toStored(row) : null;
  }

  /**
   * O mais recente **daquela conta**.
   */
  async findLatest(ownerUid: string): Promise<StoredSnapshot | null> {
    const res = await this.db.query<SnapshotRow>(
      `SELECT ${SNAPSHOT_COLUMNS}
       FROM backup_snapshots
       WHERE owner_uid = $1
       ORDER BY id DESC
       LIMIT 1`,
      [ownerUid],
    );
    const row = res.rows[0];
    return row ? toStored(row) : null;
  }

  /**
   * Grava a metadata do snapshot inteiro em uma transação atômica (T18.1 §25).
   *
   * Chamado **depois** de o objeto existir no Object Storage: a metadata nunca aponta para um
   * objeto que ainda não foi criado. O documento canônico não entra aqui — nem em
   * `backup_snapshots.payload`, nem em `backup_items.payload`, que ficam `NULL` de propósito. Há
   * teste estrutural e de comportamento sobre isso.
   */
  async insert(
    ownerUid: string,
    snapshot: ValidatedSnapshot,
    now: number,
    identity: { readonly backupId: string; readonly storageKey: string },
  ): Promise<StoredSnapshot> {
    const { backupId, storageKey } = identity;

    const sequence = await this.db.transaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
        ownerUid,
        `backup:${snapshot.clientBackupId}`,
      ]);

      const res = await client.query<{ id: string | number }>(
        `INSERT INTO backup_snapshots
           (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
            payload_hash, item_count, size_bytes, captured_at, created_at, storage_key)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
         RETURNING id`,
        [
          backupId,
          ownerUid,
          snapshot.clientBackupId,
          snapshot.deviceId,
          snapshot.backupSchemaVersion,
          snapshot.payloadHash,
          snapshot.items.length,
          snapshot.sizeBytes,
          snapshot.capturedAt,
          now,
          storageKey,
        ],
      );

      const snapshotId = Number(res.rows[0].id);

      for (const item of snapshot.items) {
        await client.query(
          `INSERT INTO backup_items
             (snapshot_id, entity_type, entity_sync_id, entity_schema_version, content_hash)
           VALUES ($1, $2, $3, $4, $5)`,
          [
            snapshotId,
            item.entityType,
            item.entitySyncId,
            item.entitySchemaVersion,
            item.contentHash,
          ],
        );
      }

      return snapshotId;
    });

    return {
      sequence,
      ownerUid,
      backupId,
      clientBackupId: snapshot.clientBackupId,
      backupSchemaVersion: snapshot.backupSchemaVersion,
      createdAt: now,
      itemCount: snapshot.items.length,
      sizeBytes: snapshot.sizeBytes,
      payloadHash: snapshot.payloadHash,
      storageKey,
    };
  }

  /**
   * Apaga os snapshots que excedem a política, **preservando os [keep] mais recentes**.
   *
   * Devolve as chaves dos objetos que ficaram sem linha (T18.1 §28): quem chama os apaga do
   * Object Storage **depois** deste commit — nunca antes. Um objeto que resista vira órfão, e a
   * coleta de órfãos o recolhe.
   */
  async pruneOlderThan(ownerUid: string, keep: number): Promise<PrunedSnapshots> {
    const res = await this.db.query<{ storage_key: string | null }>(
      `DELETE FROM backup_snapshots
       WHERE owner_uid = $1
         AND id NOT IN (
           SELECT id FROM backup_snapshots WHERE owner_uid = $2 ORDER BY id DESC LIMIT $3
         )
       RETURNING storage_key`,
      [ownerUid, ownerUid, keep],
    );
    return {
      count: res.rowCount ?? 0,
      storageKeys: res.rows.flatMap((row) => (row.storage_key !== null ? [row.storage_key] : [])),
    };
  }

  async countFor(ownerUid: string): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      'SELECT COUNT(*) AS total FROM backup_snapshots WHERE owner_uid = $1',
      [ownerUid],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  // ------------------------------------------------------------------ órfãos (T18.1 §30)

  /**
   * Quais destas chaves **existem** em metadata — bounded pela página que o coletor examina.
   */
  async findExistingStorageKeys(storageKeys: readonly string[]): Promise<Set<string>> {
    if (storageKeys.length === 0) {
      return new Set();
    }
    const res = await this.db.query<{ key: string }>(
      `SELECT storage_key AS key FROM backup_snapshots WHERE storage_key = ANY($1::text[])`,
      [storageKeys as string[]],
    );
    return new Set(res.rows.map((row) => row.key));
  }

  // ------------------------------------------------------------------ migração legada (§32–§35)

  /** Os snapshots ainda com documento no banco e sem objeto, do mais antigo para o mais novo. */
  async listLegacySnapshots(limit: number): Promise<LegacySnapshot[]> {
    const res = await this.db.query<{
      id: string | number;
      backup_id: string;
      owner_uid: string;
      payload_hash: string;
      size_bytes: number;
    }>(
      `SELECT id, backup_id, owner_uid, payload_hash, size_bytes
         FROM backup_snapshots
        WHERE storage_key IS NULL AND payload IS NOT NULL
        ORDER BY id ASC
        LIMIT $1`,
      [limit],
    );
    return res.rows.map((row) => ({
      sequence: Number(row.id),
      backupId: row.backup_id,
      ownerUid: row.owner_uid,
      payloadHash: row.payload_hash,
      sizeBytes: Number(row.size_bytes),
    }));
  }

  /** Quantos snapshots não têm documento em lugar nenhum — anteriores à T16.5. Só diagnóstico. */
  async countWithoutAnyPayload(): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM backup_snapshots WHERE storage_key IS NULL AND payload IS NULL`,
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  /** O texto legado de um snapshot, verbatim. */
  async findLegacyPayload(sequence: number): Promise<string | null> {
    const res = await this.db.query<{ payload: string | null }>(
      `SELECT payload FROM backup_snapshots WHERE id = $1`,
      [sequence],
    );
    return res.rows[0]?.payload ?? null;
  }

  /**
   * Marca o snapshot como migrado: chave gravada, documento e payloads dos itens apagados do
   * banco — numa transação, e **só** enquanto o snapshot ainda estiver na forma legada (§34).
   *
   * `false` quando nada foi alterado: outro processo migrou antes, ou o snapshot foi removido
   * pela retenção no meio. Nos dois casos o objeto que quem chama acabou de gravar é órfão, e é
   * a coleta de órfãos quem o recolhe.
   */
  async markMigrated(sequence: number, storageKey: string): Promise<boolean> {
    return this.db.transaction(async (client) => {
      const updated = await client.query(
        `UPDATE backup_snapshots
            SET storage_key = $1, payload = NULL
          WHERE id = $2 AND storage_key IS NULL AND payload IS NOT NULL`,
        [storageKey, sequence],
      );
      if ((updated.rowCount ?? 0) === 0) {
        return false;
      }
      await client.query(`UPDATE backup_items SET payload = NULL WHERE snapshot_id = $1`, [
        sequence,
      ]);
      return true;
    });
  }
}

interface SnapshotRow {
  id: string | number;
  backup_id: string;
  owner_uid: string;
  client_backup_id: string;
  backup_schema_version: number;
  payload_hash: string;
  item_count: number;
  size_bytes: number;
  created_at: string | number;
  storage_key: string | null;
}

function toStored(row: SnapshotRow): StoredSnapshot {
  return {
    sequence: Number(row.id),
    ownerUid: row.owner_uid,
    backupId: row.backup_id,
    clientBackupId: row.client_backup_id,
    backupSchemaVersion: row.backup_schema_version,
    createdAt: Number(row.created_at),
    itemCount: row.item_count,
    sizeBytes: row.size_bytes,
    payloadHash: row.payload_hash,
    storageKey: row.storage_key,
  };
}
