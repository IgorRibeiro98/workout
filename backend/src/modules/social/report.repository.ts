import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { ReportReason } from './report.contract';

@Injectable()
export class ReportRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /** Salva nova denúncia. */
  createReport(
    id: string,
    reporterUid: string,
    reportedUid: string,
    reason: ReportReason,
    now: number,
  ): void {
    const db = this.sqlite.connection;
    db.prepare(
      `INSERT INTO social_reports (id, reporter_uid, reported_uid, reason, status, created_at)
       VALUES (?, ?, ?, ?, 'PENDING', ?)`,
    ).run(id, reporterUid, reportedUid, reason, now);
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

  /** Confere se já existe denúncia recente com a mesma razão pelo par. */
  hasRecentReport(
    reporterUid: string,
    reportedUid: string,
    reason: ReportReason,
    sinceMs: number,
  ): boolean {
    const db = this.sqlite.connection;
    const row = db
      .prepare(
        `SELECT 1 FROM social_reports
         WHERE reporter_uid = ? AND reported_uid = ? AND reason = ? AND created_at >= ?
         LIMIT 1`,
      )
      .get(reporterUid, reportedUid, reason, sinceMs);
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
