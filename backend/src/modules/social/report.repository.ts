import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { ReportReason, ReportTargetType } from './report.contract';

@Injectable()
export class ReportRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /**
   * Salva nova denúncia (T17.6, com alvo desde a T17.9 §101/§103).
   *
   * `reportedUid` continua sendo o **autor real**, resolvido pelo serviço a partir do alvo — nunca
   * um valor vindo do cliente. Para `USER` ele é o dono do `socialId`; para `CHECKIN` e `COMMENT`,
   * o autor do conteúdo.
   */
  createReport(
    id: string,
    reporterUid: string,
    reportedUid: string,
    reason: ReportReason,
    targetType: ReportTargetType,
    targetId: string,
    now: number,
  ): void {
    const db = this.sqlite.connection;
    db.prepare(
      `INSERT INTO social_reports
         (id, reporter_uid, reported_uid, reason, status, created_at, target_type, target_id)
       VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)`,
    ).run(id, reporterUid, reportedUid, reason, now, targetType, targetId);
  }

  /** Conta denúncias feitas pelo reporter desde um timestamp. */
  countReportsByReporterSince(reporterUid: string, sinceMs: number): number {
    const db = this.sqlite.connection;
    const row = db
      .prepare(
        `SELECT COUNT(*) AS total FROM social_reports
         WHERE reporter_uid = ? AND created_at >= ?`,
      )
      .get(reporterUid, sinceMs) as { total: number };
    return row.total;
  }

  /**
   * Confere se já existe denúncia recente **do mesmo alvo**, pelo mesmo motivo (§175).
   *
   * Por alvo, e não por par: denunciar dois comentários diferentes da mesma pessoa pelo mesmo
   * motivo são duas denúncias legítimas, e colapsá-las esconderia a segunda da revisão. A
   * anti-duplicata existe para o toque duplo e o retry de resposta perdida, não para limitar
   * quantas coisas de alguém podem ser reportadas.
   */
  hasRecentReport(
    reporterUid: string,
    targetType: ReportTargetType,
    targetId: string,
    reason: ReportReason,
    sinceMs: number,
  ): boolean {
    const db = this.sqlite.connection;
    const row = db
      .prepare(
        `SELECT 1 FROM social_reports
         WHERE reporter_uid = ? AND target_type = ? AND target_id = ? AND reason = ?
           AND created_at >= ?
         LIMIT 1`,
      )
      .get(reporterUid, targetType, targetId, reason, sinceMs);
    return row !== undefined;
  }

  /** Verifica se existe contexto social legítimo entre os dois usuários (amigos, request, desafio compartilhado). */
  hasLegitimateContext(uidA: string, uidB: string): boolean {
    const db = this.sqlite.connection;

    // 1. Amigos
    const friendship = db
      .prepare(
        `SELECT 1 FROM friendships
         WHERE (user_a_uid = ? AND user_b_uid = ?) OR (user_a_uid = ? AND user_b_uid = ?)
         LIMIT 1`,
      )
      .get(uidA, uidB, uidB, uidA);
    if (friendship) return true;

    // 2. Pedido de amizade pendente
    const request = db
      .prepare(
        `SELECT 1 FROM friend_requests
         WHERE status = 'PENDING'
           AND ((requester_uid = ? AND recipient_uid = ?) OR (requester_uid = ? AND recipient_uid = ?))
         LIMIT 1`,
      )
      .get(uidA, uidB, uidB, uidA);
    if (request) return true;

    // 3. Desafio compartilhado (onde ambos participam)
    const sharedChallenge = db
      .prepare(
        `SELECT 1 FROM challenge_participants p1
         JOIN challenge_participants p2 ON p1.challenge_id = p2.challenge_id
         WHERE p1.participant_uid = ? AND p2.participant_uid = ?
         LIMIT 1`,
      )
      .get(uidA, uidB);
    if (sharedChallenge) return true;

    // 4. Convite de desafio recente entre eles
    const challengeInvite = db
      .prepare(
        `SELECT 1 FROM challenge_invitations
         WHERE (inviter_uid = ? AND recipient_uid = ?) OR (inviter_uid = ? AND recipient_uid = ?)
         LIMIT 1`,
      )
      .get(uidA, uidB, uidB, uidA);
    return challengeInvite !== undefined;
  }
}
