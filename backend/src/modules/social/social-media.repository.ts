import { Injectable } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { fenceAccountMutation } from '../../database/account-mutation-fence';
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

/** O recorte que o migrador `local → GCS` precisa de cada linha (T18.1.1). */
export interface MediaMigrationRow {
  readonly id: string;
  readonly ownerUid: string;
  readonly storageKey: string;
  readonly contentHash: string;
  readonly byteSize: number;
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

  /**
   * Insere a linha `PENDING`, dentro do **Account Mutation Fence** (T18.1.1 §2): a conta pode ter
   * sido excluída enquanto o upload processava a imagem (a etapa cara do fluxo, entre o
   * `BearerAuthGuard` e este `INSERT`), e a escrita do objeto já aconteceu antes disto — o
   * chamador (`SocialMediaService.upload`) apaga o objeto quando esta função recusa.
   */
  async create(item: StoredCheckInMedia, uidHash: string): Promise<void> {
    await this.db.transaction(async (client) => {
      await fenceAccountMutation(client, item.ownerUid, uidHash);
      await client.query(
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
    });
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

  /**
   * Reivindica, atomicamente, a mídia `PENDING` cujo prazo passou e a mídia já `DELETED` — bounded,
   * sempre (T18.1.1 §8).
   *
   * ## Por que uma reivindicação, e não um `SELECT` seguido de `DELETE`
   *
   * Um `SELECT` que decide o que é "expirado" e um `DELETE`/`remove()` posteriores deixam uma
   * janela: entre os dois, `attach()` pode publicar exatamente a mídia que o coletor já decidiu
   * apagar (`SELECT` viu `PENDING` expirada; `attach()` transiciona para `ATTACHED` antes do
   * `DELETE`). O coletor então apagaria o arquivo e a linha de um check-in recém-publicado.
   *
   * Este `UPDATE` é a reivindicação inteira: a subconsulta seleciona e **bloqueia** (`FOR UPDATE`)
   * as linhas candidatas, e o `UPDATE` externo só as transiciona para `DELETED` enquanto ainda
   * estiverem no estado que ele espera. Sob READ COMMITTED, uma `UPDATE` concorrente que dispute a
   * mesma linha (o `attach()`) espera o lock e, ao liberar, **reavalia sua própria cláusula
   * `WHERE`** contra a versão recém-commitada — é assim que o Postgres garante, sem lock consultivo
   * nenhum, que só um dos dois vence: ou a reivindicação transiciona a linha para `DELETED` antes
   * (e o `attach()` que chegar depois não encontra mais `status = 'PENDING'`, e falha), ou o
   * `attach()` já publicou (e a reivindicação, ao reavaliar, não encontra mais `PENDING` nem
   * `DELETED` — encontra `ATTACHED` — e não a inclui).
   *
   * Uma mídia já `DELETED` é reivindicada de novo, sem problema: `COALESCE` preserva o
   * `deleted_at` original, e reprocessá-la é o que torna a limpeza convergente quando a remoção do
   * objeto falhou numa varredura anterior (§4/§8) — a linha continua `DELETED` até o objeto
   * realmente sair do armazenamento e a linha ser apagada.
   */
  async claimCollectable(
    now: number,
    limit: number,
  ): Promise<Array<{ id: string; storageKey: string }>> {
    const res = await this.db.query<{ id: string; storageKey: string }>(
      `UPDATE social_checkin_media
          SET status = 'DELETED', deleted_at = COALESCE(deleted_at, $1)
        WHERE id IN (
          SELECT id FROM social_checkin_media
           WHERE (status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= $1)
              OR status = 'DELETED'
           ORDER BY created_at ASC
           LIMIT $2
           FOR UPDATE
        )
        RETURNING id, storage_key AS "storageKey"`,
      [now, limit],
    );
    return res.rows;
  }

  async deleteRow(mediaId: string): Promise<void> {
    await this.db.query(`DELETE FROM social_checkin_media WHERE id = $1`, [mediaId]);
  }

  /**
   * Quais destas chaves **existem** em metadata, para a varredura de órfãos (§140, T18.1 §14).
   *
   * Bounded pela página de objetos que o coletor está examinando — e não "todas as chaves do
   * banco", que cresceria com a tabela e seria carregada inteira a cada varredura.
   */
  async findExistingStorageKeys(storageKeys: readonly string[]): Promise<Set<string>> {
    if (storageKeys.length === 0) {
      return new Set();
    }
    const res = await this.db.query<{ key: string }>(
      `SELECT storage_key AS key FROM social_checkin_media WHERE storage_key = ANY($1::text[])`,
      [storageKeys as string[]],
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

  // ------------------------------------------------------------------ migração legada (T18.1.1)

  /**
   * Uma página de mídia, em ordem estável por `id`, para o migrador `local → GCS`.
   *
   * O PostgreSQL continua sendo a autoridade sobre o que existe (T18.1.1 requisito 4): a fonte da
   * migração é esta consulta, nunca uma listagem do sistema de arquivos. `id` é UUID aleatório —
   * não é ordem cronológica, mas é uma ordem total estável, suficiente para paginar sem pular nem
   * repetir entre execuções.
   */
  async listForMigration(afterId: string | null, limit: number): Promise<MediaMigrationRow[]> {
    const res = await this.db.query<{
      id: string;
      owner_uid: string;
      storage_key: string;
      content_hash: string;
      byte_size: string | number;
    }>(
      `SELECT id, owner_uid, storage_key, content_hash, byte_size
         FROM social_checkin_media
        WHERE ($1::text IS NULL OR id > $1)
        ORDER BY id ASC
        LIMIT $2`,
      [afterId, limit],
    );
    return res.rows.map((row) => ({
      id: row.id,
      ownerUid: row.owner_uid,
      storageKey: row.storage_key,
      contentHash: row.content_hash,
      byteSize: Number(row.byte_size),
    }));
  }
}
