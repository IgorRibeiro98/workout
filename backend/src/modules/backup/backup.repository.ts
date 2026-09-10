import { randomUUID } from 'node:crypto';
import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import type { BackupMetadataResponse } from './backup.contract';
import type { ValidatedSnapshot } from './backup.validator';

export interface StoredSnapshot extends BackupMetadataResponse {
  /** Sequência interna do servidor. Não sai na API — ordena retenção e "o mais recente". */
  readonly sequence: number;
  readonly ownerUid: string;
}

/**
 * A persistência dos snapshots (T16.4 / T18.0 PostgreSQL).
 *
 * Duas garantias vivem aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **atomicidade** — snapshot e itens entram na mesma transação PostgreSQL. Falhar no item N não
 *    deixa N-1 itens nem um snapshot vazio para trás;
 * 2. **ordem da retenção** — o novo backup é gravado e confirmado **antes** de qualquer limpeza.
 */
@Injectable()
export class BackupRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * Os backups retidos **daquela conta**, do mais recente para o mais antigo.
   */
  async listFor(ownerUid: string): Promise<StoredSnapshot[]> {
    const res = await this.db.query<SnapshotRow>(
      `SELECT id, backup_id, owner_uid, client_backup_id, backup_schema_version,
              payload_hash, item_count, size_bytes, created_at
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
      `SELECT id, backup_id, owner_uid, client_backup_id, backup_schema_version,
              payload_hash, item_count, size_bytes, created_at
       FROM backup_snapshots
       WHERE owner_uid = $1 AND backup_id = $2`,
      [ownerUid, backupId],
    );
    const row = res.rows[0];
    return row ? toStored(row) : null;
  }

  /**
   * O documento canônico do snapshot, verbatim.
   */
  async findPayload(ownerUid: string, backupId: string): Promise<string | null> {
    const res = await this.db.query<{ payload: string | null }>(
      `SELECT payload FROM backup_snapshots WHERE owner_uid = $1 AND backup_id = $2`,
      [ownerUid, backupId],
    );
    return res.rows[0]?.payload ?? null;
  }

  /** O backup já existente para aquela tentativa lógica, se houver. */
  async findByClientBackupId(
    ownerUid: string,
    clientBackupId: string,
  ): Promise<StoredSnapshot | null> {
    const res = await this.db.query<SnapshotRow>(
      `SELECT id, backup_id, owner_uid, client_backup_id, backup_schema_version,
              payload_hash, item_count, size_bytes, created_at
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
      `SELECT id, backup_id, owner_uid, client_backup_id, backup_schema_version,
              payload_hash, item_count, size_bytes, created_at
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
   * Grava o snapshot inteiro em uma transação atômica.
   */
  async insert(
    ownerUid: string,
    snapshot: ValidatedSnapshot,
    now: number,
  ): Promise<StoredSnapshot> {
    const backupId = randomUUID();

    const sequence = await this.db.transaction(async (client) => {
      const res = await client.query<{ id: string | number }>(
        `INSERT INTO backup_snapshots
           (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
            payload_hash, item_count, size_bytes, captured_at, created_at, payload)
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
          snapshot.canonicalText,
        ],
      );

      const snapshotId = Number(res.rows[0].id);

      for (const item of snapshot.items) {
        await client.query(
          `INSERT INTO backup_items
             (snapshot_id, entity_type, entity_sync_id, entity_schema_version, payload, content_hash)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [
            snapshotId,
            item.entityType,
            item.entitySyncId,
            item.entitySchemaVersion,
            item.canonicalPayload,
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
    };
  }

  /**
   * Apaga os snapshots que excedem a política, **preservando os [keep] mais recentes**.
   */
  async pruneOlderThan(ownerUid: string, keep: number): Promise<number> {
    const res = await this.db.query(
      `DELETE FROM backup_snapshots
       WHERE owner_uid = $1
         AND id NOT IN (
           SELECT id FROM backup_snapshots WHERE owner_uid = $2 ORDER BY id DESC LIMIT $3
         )`,
      [ownerUid, ownerUid, keep],
    );
    return res.rowCount ?? 0;
  }

  async countFor(ownerUid: string): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      'SELECT COUNT(*) AS total FROM backup_snapshots WHERE owner_uid = $1',
      [ownerUid],
    );
    return Number(res.rows[0]?.total ?? 0);
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
  };
}
