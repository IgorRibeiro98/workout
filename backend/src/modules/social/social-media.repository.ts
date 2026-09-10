import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import { viewerScopeCte, groupShareVisibleSql } from './workout-checkin.access-policy';

/** Uma mídia como ela mora no banco (T17.9 §40). Metadata; os bytes vivem no `SocialMediaStore`. */
export interface StoredCheckInMedia {
  readonly id: string;
  readonly ownerUid: string;
  readonly sourceSessionSyncId: string;
  readonly clientUploadId: string;
  readonly storageKey: string;
  readonly mimeType: string;
  readonly byteSize: number;
  readonly width: number;
  readonly height: number;
  readonly contentHash: string;
  readonly inputContentHash: string | null;
  readonly status: 'PENDING' | 'ATTACHED' | 'DELETED';
  readonly createdAt: number;
  readonly expiresAt: number | null;
  readonly attachedCheckInId: string | null;
  readonly deletedAt: number | null;
}

/** O recorte que o Feed publica de uma foto (§58). Sem chave de armazenamento, sem hash. */
export interface CheckInMediaProjection {
  readonly checkInId: string;
  readonly mediaId: string;
  readonly width: number;
  readonly height: number;
}

interface MediaRow {
  readonly id: string;
  readonly owner_uid: string;
  readonly source_session_sync_id: string;
  readonly client_upload_id: string;
  readonly storage_key: string;
  readonly mime_type: string;
  readonly byte_size: string | number;
  readonly width: number;
  readonly height: number;
  readonly content_hash: string;
  readonly input_content_hash: string | null;
  readonly status: 'PENDING' | 'ATTACHED' | 'DELETED';
  readonly created_at: string | number;
  readonly expires_at: string | number | null;
  readonly attached_checkin_id: string | null;
  readonly deleted_at: string | number | null;
}

const SELECT_COLUMNS = `id,
       owner_uid,
       source_session_sync_id,
       client_upload_id,
       storage_key,
       mime_type,
       byte_size,
       width,
       height,
       content_hash,
       input_content_hash,
       status,
       created_at,
       expires_at,
       attached_checkin_id,
       deleted_at`;

function toDomain(row: MediaRow): StoredCheckInMedia {
  return {
    id: row.id,
    ownerUid: row.owner_uid,
    sourceSessionSyncId: row.source_session_sync_id,
    clientUploadId: row.client_upload_id,
    storageKey: row.storage_key,
    mimeType: row.mime_type,
    byteSize: Number(row.byte_size),
    width: Number(row.width),
    height: Number(row.height),
    contentHash: row.content_hash,
    inputContentHash: row.input_content_hash,
    status: row.status,
    createdAt: Number(row.created_at),
    expiresAt: row.expires_at !== null ? Number(row.expires_at) : null,
    attachedCheckInId: row.attached_checkin_id,
    deletedAt: row.deleted_at !== null ? Number(row.deleted_at) : null,
  };
}

@Injectable()
export class SocialMediaRepository {
  constructor(private readonly db: PostgresService) {}

  async create(item: StoredCheckInMedia): Promise<void> {
    await this.db.query(
      `INSERT INTO social_checkin_media (
         id, owner_uid, source_session_sync_id, client_upload_id, storage_key, mime_type,
         byte_size, width, height, content_hash, input_content_hash, status, created_at,
         expires_at, attached_checkin_id, deleted_at
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)`,
      [
        item.id,
        item.ownerUid,
        item.sourceSessionSyncId,
        item.clientUploadId,
        item.storageKey,
        item.mimeType,
        item.byteSize,
        item.width,
        item.height,
        item.contentHash,
        item.inputContentHash,
        item.status,
        item.createdAt,
        item.expiresAt,
        item.attachedCheckInId,
        item.deletedAt,
      ],
    );
  }

  /** A idempotência de §36: mesmo dono, mesmo `clientUploadId` → a mesma mídia. */
  async findByOwnerAndUpload(
    ownerUid: string,
    clientUploadId: string,
  ): Promise<StoredCheckInMedia | null> {
    const res = await this.db.query<MediaRow>(
      `SELECT ${SELECT_COLUMNS} FROM social_checkin_media
        WHERE owner_uid = $1 AND client_upload_id = $2 LIMIT 1`,
      [ownerUid, clientUploadId],
    );
    const row = res.rows[0];
    return row ? toDomain(row) : null;
  }

  async findById(mediaId: string): Promise<StoredCheckInMedia | null> {
    const res = await this.db.query<MediaRow>(
      `SELECT ${SELECT_COLUMNS} FROM social_checkin_media WHERE id = $1 LIMIT 1`,
      [mediaId],
    );
    const row = res.rows[0];
    return row ? toDomain(row) : null;
  }

  /**
   * Anexa a mídia ao check-in — a transição `PENDING → ATTACHED` (§41).
   */
  async attach(
    mediaId: string,
    ownerUid: string,
    sessionSyncId: string,
    checkInId: string,
    client?: PoolClient,
  ): Promise<boolean> {
    const runner: DbClient = (client ?? this.db) as DbClient;
    const result = await runner.query(
      `UPDATE social_checkin_media
          SET status = 'ATTACHED', attached_checkin_id = $1, expires_at = NULL
        WHERE id = $2
          AND owner_uid = $3
          AND source_session_sync_id = $4
          AND status = 'PENDING'`,
      [checkInId, mediaId, ownerUid, sessionSyncId],
    );
    return (result.rowCount ?? 0) > 0;
  }

  /**
   * A mídia de um check-in deixa de ser servível (§99/§100).
   */
  async markDeletedByCheckIn(checkInId: string, now: number, client?: PoolClient): Promise<void> {
    const runner: DbClient = (client ?? this.db) as DbClient;
    await runner.query(
      `UPDATE social_checkin_media
          SET status = 'DELETED', deleted_at = $1
        WHERE attached_checkin_id = $2 AND status <> 'DELETED'`,
      [now, checkInId],
    );
  }

  /**
   * Quantos bytes esta conta ocupa hoje (§29/§30).
   */
  async usedBytes(ownerUid: string): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      `SELECT COALESCE(SUM(byte_size), 0) AS total
         FROM social_checkin_media
        WHERE owner_uid = $1 AND status IN ('PENDING', 'ATTACHED')`,
      [ownerUid],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  /**
   * A foto de cada check-in de uma página do Feed (§58/§131/§132).
   */
  async findAttachedForCheckIns(checkInIds: readonly string[]): Promise<CheckInMediaProjection[]> {
    if (checkInIds.length === 0) {
      return [];
    }
    const res = await this.db.query<{
      checkInId: string;
      mediaId: string;
      width: number;
      height: number;
    }>(
      `SELECT attached_checkin_id AS "checkInId", id AS "mediaId", width, height
         FROM social_checkin_media
        WHERE attached_checkin_id = ANY($1::text[])
          AND status = 'ATTACHED'`,
      [checkInIds as string[]],
    );
    return res.rows.map((r) => ({
      checkInId: r.checkInId,
      mediaId: r.mediaId,
      width: Number(r.width),
      height: Number(r.height),
    }));
  }

  /**
   * A mídia [mediaId], **se** o viewer pode vê-la agora (§50/§51/§52/§53/§54).
   */
  async findViewableStorageKey(
    viewerUid: string,
    mediaId: string,
  ): Promise<{ storageKey: string; mimeType: string; byteSize: number } | null> {
    const res = await this.db.query<{
      storageKey: string;
      mimeType: string;
      byteSize: string | number;
    }>(
      `WITH ${viewerScopeCte('$1')}
       SELECT m.storage_key AS "storageKey",
              m.mime_type   AS "mimeType",
              m.byte_size   AS "byteSize"
         FROM social_checkin_media m
         JOIN social_workout_checkins c ON c.id = m.attached_checkin_id
         JOIN social_profiles p         ON p.owner_uid = c.author_uid
        WHERE m.id = $2
          AND m.status = 'ATTACHED'
          AND c.status = 'PUBLISHED'
          AND p.status = 'ACTIVE'
          AND EXISTS (SELECT 1 FROM social_profiles vp
                       WHERE vp.owner_uid = $1 AND vp.status = 'ACTIVE')
          AND (
            EXISTS (SELECT 1 FROM eligible_authors ea WHERE ea.uid = c.author_uid)
            OR ${groupShareVisibleSql('c.id', 'c.author_uid', '$1')}
          )
        LIMIT 1`,
      [viewerUid, mediaId],
    );
    const row = res.rows[0];
    if (!row) return null;
    return {
      storageKey: row.storageKey,
      mimeType: row.mimeType,
      byteSize: Number(row.byteSize),
    };
  }

  // ------------------------------------------------------------------ limpeza (§39/§140)

  /** Mídia `PENDING` cujo prazo passou, e mídia já marcada `DELETED`. Bounded, sempre. */
  async findCollectable(
    now: number,
    limit: number,
  ): Promise<Array<{ id: string; storageKey: string }>> {
    const res = await this.db.query<{ id: string; storageKey: string }>(
      `SELECT id, storage_key AS "storageKey"
         FROM social_checkin_media
        WHERE (status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= $1)
           OR status = 'DELETED'
        ORDER BY created_at ASC
        LIMIT $2`,
      [now, limit],
    );
    return res.rows;
  }

  async deleteRow(mediaId: string): Promise<void> {
    await this.db.query(`DELETE FROM social_checkin_media WHERE id = $1`, [mediaId]);
  }

  /** As chaves que **existem** em metadata, para a varredura de órfãos (§140). */
  async allStorageKeys(): Promise<Set<string>> {
    const res = await this.db.query<{ key: string }>(
      `SELECT storage_key AS key FROM social_checkin_media`,
    );
    return new Set(res.rows.map((row) => row.key));
  }

  /**
   * As chaves de todos os arquivos de uma conta (§114/§139).
   */
  async storageKeysOfOwner(ownerUid: string): Promise<string[]> {
    const res = await this.db.query<{ key: string }>(
      `SELECT storage_key AS key FROM social_checkin_media WHERE owner_uid = $1`,
      [ownerUid],
    );
    return res.rows.map((row) => row.key);
  }
}
