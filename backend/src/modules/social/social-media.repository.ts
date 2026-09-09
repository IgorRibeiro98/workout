import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import { VIEWER_SCOPE_CTE, groupShareVisibleSql } from './workout-checkin.access-policy';

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
  /**
   * SHA-256 dos bytes **como chegaram**, antes de qualquer processamento (T17.13.1 §39–§42).
   *
   * É a impressão digital da *requisição*, e responde "este retry é o mesmo upload?". Não confundir
   * com [contentHash], que é o hash do WebP **gravado** e responde "é a mesma imagem armazenada?".
   *
   * `null` nas linhas anteriores à migration 0023: os bytes originais já não existem, e §62 proíbe
   * reprocessar mídia histórica para preenchê-la. Ver o tratamento de replay legado em
   * `social-media.service.ts`.
   */
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
  readonly byte_size: number;
  readonly width: number;
  readonly height: number;
  readonly content_hash: string;
  readonly input_content_hash: string | null;
  readonly status: 'PENDING' | 'ATTACHED' | 'DELETED';
  readonly created_at: number;
  readonly expires_at: number | null;
  readonly attached_checkin_id: string | null;
  readonly deleted_at: number | null;
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
    byteSize: row.byte_size,
    width: row.width,
    height: row.height,
    contentHash: row.content_hash,
    inputContentHash: row.input_content_hash,
    status: row.status,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    attachedCheckInId: row.attached_checkin_id,
    deletedAt: row.deleted_at,
  };
}

@Injectable()
export class SocialMediaRepository {
  constructor(private readonly sqlite: SqliteService) {}

  create(item: StoredCheckInMedia): void {
    this.sqlite.connection
      .prepare(
        `INSERT INTO social_checkin_media (
           id, owner_uid, source_session_sync_id, client_upload_id, storage_key, mime_type,
           byte_size, width, height, content_hash, input_content_hash, status, created_at,
           expires_at, attached_checkin_id, deleted_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
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
      );
  }

  /** A idempotência de §36: mesmo dono, mesmo `clientUploadId` → a mesma mídia. */
  findByOwnerAndUpload(ownerUid: string, clientUploadId: string): StoredCheckInMedia | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT ${SELECT_COLUMNS} FROM social_checkin_media
          WHERE owner_uid = ? AND client_upload_id = ? LIMIT 1`,
      )
      .get(ownerUid, clientUploadId) as MediaRow | undefined;
    return row ? toDomain(row) : null;
  }

  findById(mediaId: string): StoredCheckInMedia | null {
    const row = this.sqlite.connection
      .prepare(`SELECT ${SELECT_COLUMNS} FROM social_checkin_media WHERE id = ? LIMIT 1`)
      .get(mediaId) as MediaRow | undefined;
    return row ? toDomain(row) : null;
  }

  /**
   * Anexa a mídia ao check-in — a transição `PENDING → ATTACHED` (§41).
   *
   * Escrita **condicional**, e a condição carrega as três regras de uma vez: a mídia é do mesmo
   * dono (§34), é da mesma sessão (§34) e ainda está `PENDING` — ou seja, nunca foi anexada a
   * outro check-in (§35). O `changes` é a resposta; o serviço não relê para saber o que aconteceu,
   * e duas requisições simultâneas não conseguem anexar a mesma mídia a dois posts.
   *
   * `expires_at = NULL` porque o prazo era do upload órfão: uma mídia publicada não expira.
   */
  attach(mediaId: string, ownerUid: string, sessionSyncId: string, checkInId: string): boolean {
    const result = this.sqlite.connection
      .prepare(
        `UPDATE social_checkin_media
            SET status = 'ATTACHED', attached_checkin_id = ?, expires_at = NULL
          WHERE id = ?
            AND owner_uid = ?
            AND source_session_sync_id = ?
            AND status = 'PENDING'`,
      )
      .run(checkInId, mediaId, ownerUid, sessionSyncId);
    return result.changes > 0;
  }

  /**
   * A mídia de um check-in deixa de ser servível (§99/§100).
   *
   * A **visibilidade** é revogada aqui, na hora, dentro da mesma transação em que o check-in é
   * excluído. O arquivo sai depois, pelo cleaner: apagar em disco no caminho do `DELETE` faria a
   * resposta esperar I/O de sistema de arquivos por nada, e o que o usuário precisa é que ninguém
   * mais consiga abrir a foto — o que já é verdade assim que esta linha muda.
   */
  markDeletedByCheckIn(checkInId: string, now: number): void {
    this.sqlite.connection
      .prepare(
        `UPDATE social_checkin_media
            SET status = 'DELETED', deleted_at = ?
          WHERE attached_checkin_id = ? AND status <> 'DELETED'`,
      )
      .run(now, checkInId);
  }

  /**
   * Quantos bytes esta conta ocupa hoje (§29/§30).
   *
   * `PENDING` conta junto com `ATTACHED` porque **ocupa disco** junto: um upload abandonado só
   * some no cleanup, e descontá-lo antes contabilizaria uma liberação que ainda não aconteceu.
   * Esse é exatamente o buraco que alguém usaria para encher a partição sem nunca publicar nada.
   */
  usedBytes(ownerUid: string): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COALESCE(SUM(byte_size), 0) AS total
           FROM social_checkin_media
          WHERE owner_uid = ? AND status IN ('PENDING', 'ATTACHED')`,
      )
      .get(ownerUid) as { total: number };
    return row.total;
  }

  /**
   * A foto de cada check-in de uma página do Feed (§58/§131/§132).
   *
   * Uma consulta para a página inteira, e não uma por item: um feed de 20 itens não pode virar 20
   * consultas de mídia. `IN (...)` com placeholders — os ids vêm do próprio servidor, da consulta
   * anterior, e nunca do cliente.
   */
  findAttachedForCheckIns(checkInIds: readonly string[]): CheckInMediaProjection[] {
    if (checkInIds.length === 0) {
      return [];
    }
    const placeholders = checkInIds.map(() => '?').join(', ');
    return this.sqlite.connection
      .prepare(
        `SELECT attached_checkin_id AS checkInId, id AS mediaId, width, height
           FROM social_checkin_media
          WHERE attached_checkin_id IN (${placeholders})
            AND status = 'ATTACHED'`,
      )
      .all(...checkInIds) as CheckInMediaProjection[];
  }

  /**
   * A mídia [mediaId], **se** o viewer pode vê-la agora (§50/§51/§52/§53/§54).
   *
   * A autorização é a mesma do Feed, e mora na mesma CTE (§128/§129): conhecer o UUID não concede
   * nada. Um não-amigo, alguém em bloqueio (nas duas direções), um autor que desativou o Social e
   * um check-in excluído recebem todos `null` — que o controller devolve como `404`.
   *
   * `status = 'ATTACHED'` exclui `PENDING` de propósito: mídia que ainda não foi publicada não é
   * servível para ninguém, nem para o próprio dono por esta rota. Ela existe apenas como candidata
   * a anexo, e servi-la seria dar ao endpoint uma segunda função.
   *
   * ## O que a T17.11 acrescentou (§79/§80/§81)
   *
   * Um segundo caminho de leitura: a foto de um check-in trazido para um Squad precisa ser
   * visível para os membros daquele Squad — senão o card do feed de grupo teria legenda e um
   * retângulo cinza. O caminho é o **mesmo** predicado de `groupShareVisibleSql`, e não uma
   * segunda regra escrita aqui (§77).
   *
   * O que **não** mudou: o `mediaId` continua não concedendo nada (§80). Quem não é membro de
   * nenhum Squad onde o check-in foi compartilhado, e não é amigo do autor, recebe `404` — e o
   * par bloqueado também (§81), porque o bloqueio está dentro do predicado, e não ao lado dele.
   */
  findViewableStorageKey(
    viewerUid: string,
    mediaId: string,
  ): { storageKey: string; mimeType: string; byteSize: number } | null {
    const row = this.sqlite.connection
      .prepare(
        `WITH ${VIEWER_SCOPE_CTE}
         SELECT m.storage_key AS storageKey,
                m.mime_type   AS mimeType,
                m.byte_size   AS byteSize
           FROM social_checkin_media m
           JOIN social_workout_checkins c ON c.id = m.attached_checkin_id
           JOIN social_profiles p         ON p.owner_uid = c.author_uid
          WHERE m.id = :mediaId
            AND m.status = 'ATTACHED'
            AND c.status = 'PUBLISHED'
            AND p.status = 'ACTIVE'
            AND EXISTS (SELECT 1 FROM social_profiles vp
                         WHERE vp.owner_uid = :viewer AND vp.status = 'ACTIVE')
            AND (
              -- T17.11 §76/§79 — os dois caminhos, com a mesma definicao que o detalhe do
              -- check-in usa. A juncao com eligible_authors virou um EXISTS porque ela passou a
              -- ser uma das alternativas e nao mais a unica: como JOIN, ela eliminaria a linha
              -- antes de o caminho de Squad chegar a ser avaliado.
              EXISTS (SELECT 1 FROM eligible_authors ea WHERE ea.uid = c.author_uid)
              OR ${groupShareVisibleSql('c.id', 'c.author_uid')}
            )
          LIMIT 1`,
      )
      .get({ viewer: viewerUid, mediaId }) as
      | { storageKey: string; mimeType: string; byteSize: number }
      | undefined;
    return row ?? null;
  }

  // ------------------------------------------------------------------ limpeza (§39/§140)

  /** Mídia `PENDING` cujo prazo passou, e mídia já marcada `DELETED`. Bounded, sempre. */
  findCollectable(now: number, limit: number): Array<{ id: string; storageKey: string }> {
    return this.sqlite.connection
      .prepare(
        `SELECT id, storage_key AS storageKey
           FROM social_checkin_media
          WHERE (status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= ?)
             OR status = 'DELETED'
          ORDER BY created_at ASC
          LIMIT ?`,
      )
      .all(now, limit) as Array<{ id: string; storageKey: string }>;
  }

  deleteRow(mediaId: string): void {
    this.sqlite.connection.prepare(`DELETE FROM social_checkin_media WHERE id = ?`).run(mediaId);
  }

  /** As chaves que **existem** em metadata, para a varredura de órfãos (§140). */
  allStorageKeys(): Set<string> {
    const rows = this.sqlite.connection
      .prepare(`SELECT storage_key AS key FROM social_checkin_media`)
      .all() as Array<{ key: string }>;
    return new Set(rows.map((row) => row.key));
  }

  /**
   * As chaves de todos os arquivos de uma conta (§114/§139).
   *
   * Lida **antes** do purge, porque depois dele as linhas não existem mais — e o `ON DELETE
   * CASCADE` do SQLite não alcança o sistema de arquivos. Sem esta leitura, excluir a conta
   * deixaria a foto de alguém no disco de um servidor que jura tê-la apagado.
   */
  storageKeysOfOwner(ownerUid: string): string[] {
    const rows = this.sqlite.connection
      .prepare(`SELECT storage_key AS key FROM social_checkin_media WHERE owner_uid = ?`)
      .all(ownerUid) as Array<{ key: string }>;
    return rows.map((row) => row.key);
  }
}
