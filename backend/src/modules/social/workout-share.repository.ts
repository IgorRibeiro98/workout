import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type {
  WorkoutShareItemDto,
  WorkoutShareStatus,
  WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';

export interface StoredWorkoutShare {
  readonly id: string;
  readonly sender_uid: string;
  readonly recipient_uid: string;
  readonly snapshot_version: number;
  readonly snapshot_json: string;
  readonly snapshot_hash: string;
  readonly status: WorkoutShareStatus;
  readonly client_request_id: string;
  readonly created_at: number;
  readonly accepted_at: number | null;
  readonly imported_at: number | null;
  readonly declined_at: number | null;
  readonly cancelled_at: number | null;
  readonly expires_at: number;
}

@Injectable()
export class WorkoutShareRepository {
  constructor(private readonly sqlite: SqliteService) {}

  private get db() {
    return this.sqlite.connection;
  }

  insertShare(share: StoredWorkoutShare): void {
    this.db
      .prepare(
        `INSERT INTO workout_shares
           (id, sender_uid, recipient_uid, snapshot_version, snapshot_json, snapshot_hash,
            status, client_request_id, created_at, accepted_at, imported_at, declined_at,
            cancelled_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        share.id,
        share.sender_uid,
        share.recipient_uid,
        share.snapshot_version,
        share.snapshot_json,
        share.snapshot_hash,
        share.status,
        share.client_request_id,
        share.created_at,
        share.accepted_at,
        share.imported_at,
        share.declined_at,
        share.cancelled_at,
        share.expires_at,
      );
  }

  findBySenderAndClientRequestId(
    senderUid: string,
    clientRequestId: string,
  ): StoredWorkoutShare | undefined {
    return this.db
      .prepare(
        `SELECT * FROM workout_shares
         WHERE sender_uid = ? AND client_request_id = ?`,
      )
      .get(senderUid, clientRequestId) as StoredWorkoutShare | undefined;
  }

  findById(shareId: string): StoredWorkoutShare | undefined {
    return this.db.prepare(`SELECT * FROM workout_shares WHERE id = ?`).get(shareId) as
      | StoredWorkoutShare
      | undefined;
  }

  countPendingBySender(senderUid: string, now: number): number {
    const row = this.db
      .prepare(
        `SELECT COUNT(*) as count FROM workout_shares
         WHERE sender_uid = ? AND status = 'PENDING' AND expires_at > ?`,
      )
      .get(senderUid, now) as { count: number };
    return row.count;
  }

  countCreatedToday(senderUid: string, since: number): number {
    const row = this.db
      .prepare(
        `SELECT COUNT(*) as count FROM workout_shares
         WHERE sender_uid = ? AND created_at >= ?`,
      )
      .get(senderUid, since) as { count: number };
    return row.count;
  }

  isFriendshipActive(uidA: string, uidB: string): boolean {
    const row = this.db
      .prepare(
        `SELECT 1 FROM friendships
         WHERE (user_a_uid = ? AND user_b_uid = ?)
            OR (user_a_uid = ? AND user_b_uid = ?)
         LIMIT 1`,
      )
      .get(uidA, uidB, uidB, uidA);
    return row !== undefined;
  }

  findProfileBySocialId(
    socialId: string,
  ): { ownerUid: string; displayName: string; socialId: string } | undefined {
    const row = this.db
      .prepare(
        `SELECT owner_uid AS ownerUid, display_name AS displayName, social_id AS socialId
         FROM social_profiles
         WHERE social_id = ? AND status = 'ACTIVE'`,
      )
      .get(socialId) as { ownerUid: string; displayName: string; socialId: string } | undefined;
    return row;
  }

  findProfileByUid(ownerUid: string): { socialId: string; displayName: string } | undefined {
    const row = this.db
      .prepare(
        `SELECT social_id AS socialId, display_name AS displayName
         FROM social_profiles
         WHERE owner_uid = ? AND status = 'ACTIVE'`,
      )
      .get(ownerUid) as { socialId: string; displayName: string } | undefined;
    return row;
  }

  /**
   * Uma transição de estado **condicional** ao estado esperado (T17.13.1 §50/§51).
   *
   * ```sql
   * UPDATE workout_shares SET status = :to, <campo> = :ts
   *  WHERE id = :id AND status = :from
   * ```
   *
   * ## Por que o `AND status = :from` importa
   *
   * A versão anterior escrevia `WHERE id = ?` e nada mais. Quem chamava lia a linha, conferia o
   * estado em memória, decidia, e só então escrevia — e entre a leitura e a escrita cabe outra
   * requisição inteira. Duas transições incompatíveis simultâneas **ambas** passavam, e a última a
   * escrever ganhava:
   *
   *   - aceitar e cancelar ao mesmo tempo: o remetente cancela, o destinatário aceita, e o
   *     resultado depende de qual `UPDATE` chegou por último. O aceite podia sobrescrever um
   *     cancelamento já respondido com sucesso ao remetente — e o destinatário levava para casa um
   *     treino que o dono retirou;
   *   - expirar e aceitar: a auto-expiração de uma listagem concorrente podia apagar um `ACCEPTED`
   *     recém-gravado, deixando `EXPIRED` uma oferta que a pessoa já importou;
   *   - concluir importação duas vezes: as duas escritas passavam, e `imported_at` virava o
   *     carimbo da segunda.
   *
   * Com a condição no `WHERE`, o SQLite decide — e ele decide uma vez só. `changes` diz quem
   * ganhou: `true` para quem transicionou, `false` para quem chegou depois. Quem perdeu **relê** e
   * responde a partir do estado real, em vez de assumir que escreveu.
   *
   * ## O que isto não é (§53)
   *
   * Não é uma segunda máquina de estados. A tabela continua sendo a autoridade, os estados válidos
   * continuam declarados no `CHECK` da migration 0014, e as regras de quem pode fazer o quê
   * continuam no serviço. Isto é só a escrita, feita de forma que não possa perder uma corrida.
   */
  transitionStatus(
    shareId: string,
    fromStatus: WorkoutShareStatus,
    newStatus: WorkoutShareStatus,
    timestampField: 'accepted_at' | 'imported_at' | 'declined_at' | 'cancelled_at',
    timestamp: number,
  ): boolean {
    const result = this.db
      .prepare(
        `UPDATE workout_shares
         SET status = ?, ${timestampField} = ?
         WHERE id = ? AND status = ?`,
      )
      .run(newStatus, timestamp, shareId, fromStatus);
    return result.changes > 0;
  }

  /**
   * O compartilhamento e o evento de notificação, numa transação só (T17.13.1 §45–§47).
   *
   * ## Por que os dois precisam ser uma decisão só
   *
   * O evento em `social_notification_events` é o **outbox** do push: ele é o que faz o destinatário
   * saber que a oferta existe. As duas escritas eram sequenciais e independentes, e uma falha entre
   * elas deixava um dos dois estados órfãos:
   *
   *   - share sem evento: a oferta existe no banco, ninguém é avisado, e ela expira em trinta dias
   *     sem que o destinatário jamais tenha sabido dela. É o pior dos dois, porque é **silencioso**
   *     — nada no sistema indica que faltou avisar;
   *   - evento sem share: o push chega, a pessoa abre o app e não encontra nada.
   *
   * Aqui os dois entram ou nenhum entra. `better-sqlite3` faz `ROLLBACK` automático se qualquer
   * `run()` lançar, e as duas escritas usam a **mesma conexão** — há uma só neste processo.
   *
   * ## O FCM continua fora (§46)
   *
   * Esta transação grava a *intenção* de notificar, e não a notificação. A entrega é do
   * `NotificationDispatcher`, que lê o outbox depois, fora de qualquer transação: uma chamada de
   * rede dentro de um `BEGIN` seguraria o banco pelo tempo do timeout do Firebase.
   */
  insertShareWithNotification(share: StoredWorkoutShare, enqueueEvent: () => void): void {
    this.db.transaction(() => {
      this.insertShare(share);
      enqueueEvent();
    })();
  }

  /**
   * Lista recebidos para recipientUid. Auto-expira itens PENDING se now >= expires_at.
   */
  listReceived(recipientUid: string, now: number): WorkoutShareItemDto[] {
    // 1. Auto-expira PENDING passados
    this.db
      .prepare(
        `UPDATE workout_shares
         SET status = 'EXPIRED'
         WHERE recipient_uid = ? AND status = 'PENDING' AND expires_at <= ?`,
      )
      .run(recipientUid, now);

    // 2. Busca shares recebidos excluindo usuários com bloqueio bilateral
    const rows = this.db
      .prepare(
        `SELECT s.id AS shareId, s.status, s.created_at AS createdAt, s.expires_at AS expiresAt,
                s.snapshot_json AS snapshotJson,
                p.social_id AS otherSocialId, p.display_name AS otherDisplayName
         FROM workout_shares s
         JOIN social_profiles p ON s.sender_uid = p.owner_uid
         WHERE s.recipient_uid = ?
           AND NOT EXISTS (
             SELECT 1 FROM social_blocks b
             WHERE (b.blocker_uid = s.sender_uid AND b.blocked_uid = s.recipient_uid)
                OR (b.blocker_uid = s.recipient_uid AND b.blocked_uid = s.sender_uid)
           )
         ORDER BY s.created_at DESC`,
      )
      .all(recipientUid) as Array<{
      shareId: string;
      status: WorkoutShareStatus;
      createdAt: number;
      expiresAt: number;
      snapshotJson: string;
      otherSocialId: string;
      otherDisplayName: string;
    }>;

    return rows.map((r) => {
      const snap = JSON.parse(r.snapshotJson) as WorkoutTemplateShareSnapshotV1;
      return {
        shareId: r.shareId,
        status: r.status,
        createdAt: r.createdAt,
        expiresAt: r.expiresAt,
        templateName: snap.name,
        exerciseCount: snap.exercises?.length ?? 0,
        otherUser: {
          socialId: r.otherSocialId,
          displayName: r.otherDisplayName,
        },
      };
    });
  }

  /**
   * Lista enviados para senderUid. Auto-expira itens PENDING se now >= expires_at.
   */
  listSent(senderUid: string, now: number): WorkoutShareItemDto[] {
    this.db
      .prepare(
        `UPDATE workout_shares
         SET status = 'EXPIRED'
         WHERE sender_uid = ? AND status = 'PENDING' AND expires_at <= ?`,
      )
      .run(senderUid, now);

    const rows = this.db
      .prepare(
        `SELECT s.id AS shareId, s.status, s.created_at AS createdAt, s.expires_at AS expiresAt,
                s.snapshot_json AS snapshotJson,
                p.social_id AS otherSocialId, p.display_name AS otherDisplayName
         FROM workout_shares s
         JOIN social_profiles p ON s.recipient_uid = p.owner_uid
         WHERE s.sender_uid = ?
           AND NOT EXISTS (
             SELECT 1 FROM social_blocks b
             WHERE (b.blocker_uid = s.sender_uid AND b.blocked_uid = s.recipient_uid)
                OR (b.blocker_uid = s.recipient_uid AND b.blocked_uid = s.sender_uid)
           )
         ORDER BY s.created_at DESC`,
      )
      .all(senderUid) as Array<{
      shareId: string;
      status: WorkoutShareStatus;
      createdAt: number;
      expiresAt: number;
      snapshotJson: string;
      otherSocialId: string;
      otherDisplayName: string;
    }>;

    return rows.map((r) => {
      const snap = JSON.parse(r.snapshotJson) as WorkoutTemplateShareSnapshotV1;
      return {
        shareId: r.shareId,
        status: r.status,
        createdAt: r.createdAt,
        expiresAt: r.expiresAt,
        templateName: snap.name,
        exerciseCount: snap.exercises?.length ?? 0,
        otherUser: {
          socialId: r.otherSocialId,
          displayName: r.otherDisplayName,
        },
      };
    });
  }
}
