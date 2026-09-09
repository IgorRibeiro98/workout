import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';

export interface StoredDeletionJob {
  readonly id: string;
  readonly firebase_uid: string;
  readonly uid_hash: string;
  readonly attempts: number;
  readonly last_error: string | null;
  readonly next_attempt_at: number;
  readonly created_at: number;
}

@Injectable()
export class AccountDeletionRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * Executa a transação atômica de purge de todas as tabelas account-scoped do SQLite.
   * Não apaga dados privados de outros usuários (B, C).
   */
  purgeAccountData(ownerUid: string): void {
    const db = this.sqlite.connection;
    const tx = db.transaction(() => {
      // 1. Sync
      db.prepare(`DELETE FROM sync_entities WHERE owner_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM sync_changes WHERE owner_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM sync_mutations WHERE owner_uid = ?`).run(ownerUid);

      // 2. Backups
      db.prepare(
        `DELETE FROM backup_items WHERE snapshot_id IN (SELECT id FROM backup_snapshots WHERE owner_uid = ?)`,
      ).run(ownerUid);
      db.prepare(`DELETE FROM backup_snapshots WHERE owner_uid = ?`).run(ownerUid);

      // 3. IA Usage
      db.prepare(`DELETE FROM ai_usage_daily WHERE uid = ?`).run(ownerUid);

      // 4. Notificações
      db.prepare(
        `DELETE FROM social_notification_deliveries
         WHERE event_id IN (SELECT id FROM social_notification_events WHERE recipient_uid = ?)
            OR device_registration_id IN (SELECT id FROM social_push_devices WHERE owner_uid = ?)`,
      ).run(ownerUid, ownerUid);

      db.prepare(`DELETE FROM social_notification_events WHERE recipient_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM social_push_devices WHERE owner_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM social_notification_preferences WHERE owner_uid = ?`).run(ownerUid);

      // 5. Configurações sociais
      db.prepare(`DELETE FROM social_progress_settings WHERE owner_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM social_privacy_settings WHERE owner_uid = ?`).run(ownerUid);

      // 6. Grafo de amizades e solicitações
      db.prepare(`DELETE FROM friend_requests WHERE requester_uid = ? OR recipient_uid = ?`).run(
        ownerUid,
        ownerUid,
      );
      db.prepare(`DELETE FROM friendships WHERE user_a_uid = ? OR user_b_uid = ?`).run(
        ownerUid,
        ownerUid,
      );

      // 7. Bloqueios e denúncias
      db.prepare(`DELETE FROM social_blocks WHERE blocker_uid = ? OR blocked_uid = ?`).run(
        ownerUid,
        ownerUid,
      );
      db.prepare(`DELETE FROM social_reports WHERE reporter_uid = ? OR reported_uid = ?`).run(
        ownerUid,
        ownerUid,
      );

      // 8. Desafios:
      // Participações em desafios criados por terceiros
      db.prepare(`DELETE FROM challenge_participants WHERE participant_uid = ?`).run(ownerUid);
      db.prepare(
        `DELETE FROM challenge_invitations WHERE inviter_uid = ? OR recipient_uid = ?`,
      ).run(ownerUid, ownerUid);
      db.prepare(`DELETE FROM challenge_creation_requests WHERE owner_uid = ?`).run(ownerUid);

      // 9. Workout Shares (T17.7)
      db.prepare(`DELETE FROM workout_shares WHERE sender_uid = ? OR recipient_uid = ?`).run(
        ownerUid,
        ownerUid,
      );

      // 10. Workout Check-ins (T17.8) e o conteúdo da T17.9.
      //
      // A ordem importa e é explícita de propósito. O `ON DELETE CASCADE` de `social_profiles`
      // levaria tudo isso junto no passo 11, mas escrever cada `DELETE` aqui é o que torna a
      // política **legível** — e o que garante §112 e §113: os comentários e as reações que a
      // pessoa deixou em publicações **de outras pessoas** somem, e não só o que estava na dela.
      // Um cascade silencioso funcionaria hoje e deixaria a próxima tabela fora sem que ninguém
      // percebesse.
      db.prepare(`DELETE FROM social_checkin_comments WHERE author_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM social_checkin_reactions WHERE reactor_uid = ?`).run(ownerUid);
      // Comentários e reações **de terceiros** nas publicações desta conta saem junto com elas:
      // sem a publicação, eles não têm onde existir.
      db.prepare(
        `DELETE FROM social_checkin_comments
          WHERE checkin_id IN (SELECT id FROM social_workout_checkins WHERE author_uid = ?)`,
      ).run(ownerUid);
      db.prepare(
        `DELETE FROM social_checkin_reactions
          WHERE checkin_id IN (SELECT id FROM social_workout_checkins WHERE author_uid = ?)`,
      ).run(ownerUid);
      // A metadata de mídia. Os **arquivos** são apagados pelo serviço, com as chaves lidas antes
      // desta transação (§114): o SQLite não alcança o sistema de arquivos.
      db.prepare(`DELETE FROM social_checkin_media WHERE owner_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM social_workout_checkins WHERE author_uid = ?`).run(ownerUid);

      // 11. Squads (T17.11 §100/§101/§102).
      //
      // ## Dono: o Squad inteiro sai
      //
      // §101 explica por quê: a exclusão de conta precisa ser **determinística** e não pode
      // depender da escolha de um terceiro. Transferir a posse em silêncio entregaria um grupo de
      // pessoas reais a alguém que não pediu por ele — e escolher **quem** exigiria uma ordenação
      // arbitrária, exatamente o que §98 recusa na desativação do Social.
      //
      // Diferente da desativação, aqui não há como recusar (§100): a exclusão de conta nunca é
      // bloqueada. Por isso a política é outra — lá a pessoa resolve, aqui o Squad vai junto.
      //
      // ## O que isso **não** apaga (§102)
      //
      // Nada de outro usuário além do vínculo de grupo. Os `WorkoutCheckIn`, as `WorkoutSession`,
      // os `WorkoutTemplate` e as fotos de B e C continuam intactos: o `ON DELETE CASCADE` de
      // `social_groups` alcança participações, convites e arestas de compartilhamento, e para em
      // `social_group_checkin_shares` — que é uma aresta, e não uma publicação.
      db.prepare(
        `DELETE FROM social_group_checkin_shares
          WHERE group_id IN (SELECT id FROM social_groups WHERE owner_uid = ?)`,
      ).run(ownerUid);
      db.prepare(
        `DELETE FROM social_group_memberships
          WHERE group_id IN (SELECT id FROM social_groups WHERE owner_uid = ?)`,
      ).run(ownerUid);
      db.prepare(
        `DELETE FROM social_group_invitations
          WHERE group_id IN (SELECT id FROM social_groups WHERE owner_uid = ?)`,
      ).run(ownerUid);
      db.prepare(`DELETE FROM social_groups WHERE owner_uid = ?`).run(ownerUid);

      // Participante em Squads de **outras** pessoas: só o vínculo sai, e o Squad sobrevive
      // (§100 — "Account Deletion do member preserva Squad"). Os compartilhamentos desta conta
      // naqueles grupos saem junto, pelo mesmo motivo de `leave` (§62/§63).
      db.prepare(`DELETE FROM social_group_checkin_shares WHERE author_uid = ?`).run(ownerUid);
      db.prepare(`DELETE FROM social_group_memberships WHERE member_uid = ?`).run(ownerUid);
      db.prepare(
        `DELETE FROM social_group_invitations WHERE sender_uid = ? OR recipient_uid = ?`,
      ).run(ownerUid, ownerUid);

      // 12. Perfil Social raiz
      db.prepare(`DELETE FROM social_profiles WHERE owner_uid = ?`).run(ownerUid);
    });

    tx();
  }

  /**
   * As chaves de armazenamento de toda a mídia desta conta (T17.9 §114/§139).
   *
   * Lida **antes** de [purgeAccountData], e não depois: o purge remove as linhas, e sem elas não
   * há como saber quais arquivos apagar. Sem esta leitura, excluir a conta deixaria as fotos da
   * pessoa no disco de um servidor que jura tê-las apagado — e um restore posterior as traria de
   * volta com metadata nova.
   */
  listMediaStorageKeys(ownerUid: string): string[] {
    const db = this.sqlite.connection;
    const rows = db
      .prepare(`SELECT storage_key AS key FROM social_checkin_media WHERE owner_uid = ?`)
      .all(ownerUid) as Array<{ key: string }>;
    return rows.map((row) => row.key);
  }

  insertTombstone(id: string, uidHash: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `INSERT INTO account_deletion_tombstones (id, uid_hash, deleted_at)
       VALUES (?, ?, ?)
       ON CONFLICT (uid_hash) DO UPDATE SET deleted_at = excluded.deleted_at`,
    ).run(id, uidHash, now);
  }

  isTombstoned(uidHash: string): boolean {
    const db = this.sqlite.connection;
    const row = db
      .prepare(`SELECT 1 FROM account_deletion_tombstones WHERE uid_hash = ? LIMIT 1`)
      .get(uidHash);
    return row !== undefined;
  }

  insertJob(id: string, firebaseUid: string, uidHash: string, now: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `INSERT INTO account_deletion_jobs (id, firebase_uid, uid_hash, attempts, next_attempt_at, created_at)
       VALUES (?, ?, ?, 0, ?, ?)
       ON CONFLICT (firebase_uid) DO NOTHING`,
    ).run(id, firebaseUid, uidHash, now, now);
  }

  findDueJobs(now: number, limit: number): StoredDeletionJob[] {
    const db = this.sqlite.connection;
    return db
      .prepare(
        `SELECT id, firebase_uid, uid_hash, attempts, last_error, next_attempt_at, created_at
         FROM account_deletion_jobs
         WHERE next_attempt_at <= ?
         ORDER BY next_attempt_at ASC
         LIMIT ?`,
      )
      .all(now, limit) as StoredDeletionJob[];
  }

  hasPendingJob(firebaseUid: string): boolean {
    const db = this.sqlite.connection;
    const row = db
      .prepare(`SELECT 1 FROM account_deletion_jobs WHERE firebase_uid = ? LIMIT 1`)
      .get(firebaseUid);
    return row !== undefined;
  }

  deleteJob(id: string): void {
    const db = this.sqlite.connection;
    db.prepare(`DELETE FROM account_deletion_jobs WHERE id = ?`).run(id);
  }

  /**
   * Encerra o job pendente **daquele uid**, qualquer que seja o `id` dele (T17.10 §83).
   *
   * `insertJob` tem `ON CONFLICT (firebase_uid) DO NOTHING`: numa segunda tentativa de exclusão o
   * serviço gera um `jobId` novo que nunca chega a ser inserido, e apagar por esse id não removia
   * nada. O job da primeira tentativa ficava para sempre, e `deletion-status` respondia
   * `DELETION_PENDING` para uma conta já apagada no Firebase. A chave estável aqui é o uid.
   */
  deleteJobByFirebaseUid(firebaseUid: string): void {
    const db = this.sqlite.connection;
    db.prepare(`DELETE FROM account_deletion_jobs WHERE firebase_uid = ?`).run(firebaseUid);
  }

  incrementJobAttempt(id: string, error: string, nextAttemptAt: number): void {
    const db = this.sqlite.connection;
    db.prepare(
      `UPDATE account_deletion_jobs
       SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?
       WHERE id = ?`,
    ).run(error, nextAttemptAt, id);
  }

  /**
   * Coleta todos os owner_uids conhecidos atualmente no banco para reconciliação anti-ressurreição.
   *
   * ## Por que a lista é redundante de propósito (T17.13 §51)
   *
   * Num snapshot **consistente** bastaria `social_profiles`: toda tabela social referencia
   * `social_profiles(owner_uid)`, e uma conta que tem qualquer linha social tem também o perfil.
   * As outras origens existem porque um restore não é garantidamente consistente — um snapshot
   * copiado com o processo escrevendo, um `.dump` parcial, uma cópia de arquivo sem checkpoint do
   * WAL — e nesse caso a conta precisa ser encontrada por **qualquer** rastro que tenha
   * sobrevivido. É defesa em profundidade, e o custo é uma varredura a mais numa rotina que roda
   * uma vez por restore.
   *
   * `social_groups` e `social_group_memberships` entram pela mesma razão que
   * `social_workout_checkins` já entrava, e a ausência delas era uma lacuna: um Squad que
   * sobrevivesse a um restore parcial sem o perfil do dono não seria enumerado, e a reconciliação
   * passaria por ele sem apagá-lo — deixando de pé um grupo de pessoas reais cujo dono já não
   * existe. Ele não seria **legível** (a política exige perfil `ACTIVE` do outro lado), mas ficaria
   * ocupando identidade e vaga, e §51 pede explicitamente que os domínios da T17.11/T17.12 estejam
   * no reconciliador.
   */
  listAllOwnerUidsInDatabase(): string[] {
    const db = this.sqlite.connection;
    const rows = db
      .prepare(
        `SELECT DISTINCT owner_uid FROM social_profiles
         UNION
         SELECT DISTINCT owner_uid FROM sync_entities
         UNION
         SELECT DISTINCT owner_uid FROM backup_snapshots
         UNION
         SELECT DISTINCT author_uid AS owner_uid FROM social_workout_checkins
         UNION
         SELECT DISTINCT owner_uid FROM social_groups
         UNION
         SELECT DISTINCT member_uid AS owner_uid FROM social_group_memberships
         UNION
         SELECT DISTINCT uid AS owner_uid FROM ai_usage_daily`,
      )
      .all() as Array<{ owner_uid: string }>;
    return rows.map((r) => r.owner_uid);
  }
}
