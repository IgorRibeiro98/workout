import { randomUUID } from 'node:crypto';
import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { BackupMetadataResponse } from './backup.contract';
import type { ValidatedSnapshot } from './backup.validator';

export interface StoredSnapshot extends BackupMetadataResponse {
  /** Sequência interna do servidor. Não sai na API — ordena retenção e "o mais recente". */
  readonly sequence: number;
  readonly ownerUid: string;
}

/**
 * A persistência dos snapshots (T16.4).
 *
 * Duas garantias vivem aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **atomicidade** — snapshot e itens entram na mesma transação SQLite. Falhar no item N não
 *    deixa N-1 itens nem um snapshot vazio para trás;
 * 2. **ordem da retenção** — o novo backup é gravado e confirmado **antes** de qualquer limpeza.
 *    Apagar o antigo primeiro e falhar em gravar o novo deixaria o usuário sem backup nenhum, que
 *    é o oposto do que a feature promete.
 */
@Injectable()
export class BackupRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /** O backup já existente para aquela tentativa lógica, se houver. */
  findByClientBackupId(ownerUid: string, clientBackupId: string): StoredSnapshot | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT id, backup_id, owner_uid, client_backup_id, backup_schema_version,
                payload_hash, item_count, size_bytes, created_at
         FROM backup_snapshots
         WHERE owner_uid = ? AND client_backup_id = ?`,
      )
      .get(ownerUid, clientBackupId);
    return row ? toStored(row as SnapshotRow) : null;
  }

  /**
   * O mais recente **daquela conta**.
   *
   * Ordenado por `id` — sequência do servidor — e não por `captured_at`: relógio de aparelho
   * diverge, e um celular adiantado esconderia backups reais para sempre.
   */
  findLatest(ownerUid: string): StoredSnapshot | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT id, backup_id, owner_uid, client_backup_id, backup_schema_version,
                payload_hash, item_count, size_bytes, created_at
         FROM backup_snapshots
         WHERE owner_uid = ?
         ORDER BY id DESC
         LIMIT 1`,
      )
      .get(ownerUid);
    return row ? toStored(row as SnapshotRow) : null;
  }

  /**
   * Grava o snapshot inteiro em uma transação.
   *
   * `createdAt` vem do **servidor**. O `capturedAt` do aparelho é guardado ao lado como metadado
   * informativo e nunca decide nada.
   */
  insert(ownerUid: string, snapshot: ValidatedSnapshot, now: number): StoredSnapshot {
    const db = this.sqlite.connection;
    const backupId = randomUUID();

    const transaction = db.transaction((): number => {
      const result = db
        .prepare(
          `INSERT INTO backup_snapshots
             (backup_id, owner_uid, client_backup_id, device_id, backup_schema_version,
              payload_hash, item_count, size_bytes, captured_at, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
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
        );

      const snapshotId = Number(result.lastInsertRowid);
      const insertItem = db.prepare(
        `INSERT INTO backup_items
           (snapshot_id, entity_type, entity_sync_id, entity_schema_version, payload, content_hash)
         VALUES (?, ?, ?, ?, ?, ?)`,
      );
      for (const item of snapshot.items) {
        insertItem.run(
          snapshotId,
          item.entityType,
          item.entitySyncId,
          item.entitySchemaVersion,
          item.canonicalPayload,
          item.contentHash,
        );
      }
      return snapshotId;
    });

    const sequence = transaction();

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
   *
   * Chamado só depois do commit do novo backup. O `id > 0` implícito na subconsulta garante que o
   * recém-criado — que é o de maior `id` — nunca esteja entre os candidatos.
   *
   * Devolve quantos foram removidos. Falhar aqui não invalida o backup novo: quem chama trata
   * isso como limpeza pendente, não como backup perdido.
   */
  pruneOlderThan(ownerUid: string, keep: number): number {
    const result = this.sqlite.connection
      .prepare(
        `DELETE FROM backup_snapshots
         WHERE owner_uid = ?
           AND id NOT IN (
             SELECT id FROM backup_snapshots WHERE owner_uid = ? ORDER BY id DESC LIMIT ?
           )`,
      )
      .run(ownerUid, ownerUid, keep);
    return result.changes;
  }

  countFor(ownerUid: string): number {
    const row = this.sqlite.connection
      .prepare('SELECT COUNT(*) AS total FROM backup_snapshots WHERE owner_uid = ?')
      .get(ownerUid) as { total: number } | undefined;
    return row?.total ?? 0;
  }
}

interface SnapshotRow {
  id: number;
  backup_id: string;
  owner_uid: string;
  client_backup_id: string;
  backup_schema_version: number;
  payload_hash: string;
  item_count: number;
  size_bytes: number;
  created_at: number;
}

function toStored(row: SnapshotRow): StoredSnapshot {
  return {
    sequence: row.id,
    ownerUid: row.owner_uid,
    backupId: row.backup_id,
    clientBackupId: row.client_backup_id,
    backupSchemaVersion: row.backup_schema_version,
    createdAt: row.created_at,
    itemCount: row.item_count,
    sizeBytes: row.size_bytes,
    payloadHash: row.payload_hash,
  };
}
