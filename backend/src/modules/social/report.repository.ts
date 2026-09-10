import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../database/postgres.service';
import type { ReportReason, ReportTargetType } from './report.contract';

@Injectable()
export class ReportRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * Salva nova denúncia (T17.6, com alvo desde a T17.9 §101/§103).
   *
   * `reportedUid` continua sendo o **autor real**, resolvido pelo serviço a partir do alvo — nunca
   * um valor vindo do cliente. Para `USER` ele é o dono do `socialId`; para `CHECKIN` e `COMMENT`,
   * o autor do conteúdo.
   */
  async createReport(
    id: string,
    reporterUid: string,
    reportedUid: string,
    reason: ReportReason,
    targetType: ReportTargetType,
    targetId: string,
    now: number,
  ): Promise<void> {
    await this.db.query(
      `INSERT INTO social_reports
         (id, reporter_uid, reported_uid, reason, status, created_at, target_type, target_id)
       VALUES ($1, $2, $3, $4, 'PENDING', $5, $6, $7)`,
      [id, reporterUid, reportedUid, reason, now, targetType, targetId],
    );
  }

  /** Conta denúncias feitas pelo reporter desde um timestamp. */
  async countReportsByReporterSince(reporterUid: string, sinceMs: number): Promise<number> {
    const res = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM social_reports
       WHERE reporter_uid = $1 AND created_at >= $2`,
      [reporterUid, sinceMs],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  /**
   * Confere se já existe denúncia recente **do mesmo alvo**, pelo mesmo motivo (§175).
   *
   * Por alvo, e não por par: denunciar dois comentários diferentes da mesma pessoa pelo mesmo
   * motivo são duas denúncias legítimas, e colapsá-las esconderia a segunda da revisão. A
   * anti-duplicata existe para o toque duplo e o retry de resposta perdida, não para limitar
   * quantas coisas de alguém podem ser reportadas.
   */
  async hasRecentReport(
    reporterUid: string,
    targetType: ReportTargetType,
    targetId: string,
    reason: ReportReason,
    sinceMs: number,
  ): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM social_reports
       WHERE reporter_uid = $1 AND target_type = $2 AND target_id = $3 AND reason = $4
         AND created_at >= $5
       LIMIT 1`,
      [reporterUid, targetType, targetId, reason, sinceMs],
    );
    return res.rows.length > 0;
  }

  /** Verifica se existe contexto social legítimo entre os dois usuários (amigos, request, desafio compartilhado). */
  async hasLegitimateContext(uidA: string, uidB: string): Promise<boolean> {
    // 1. Amigos
    const friendship = await this.db.query(
      `SELECT 1 FROM friendships
       WHERE (user_a_uid = $1 AND user_b_uid = $2) OR (user_a_uid = $3 AND user_b_uid = $4)
       LIMIT 1`,
      [uidA, uidB, uidB, uidA],
    );
    if (friendship.rows.length > 0) return true;

    // 2. Pedido de amizade pendente
    const request = await this.db.query(
      `SELECT 1 FROM friend_requests
       WHERE status = 'PENDING'
         AND ((requester_uid = $1 AND recipient_uid = $2) OR (requester_uid = $3 AND recipient_uid = $4))
       LIMIT 1`,
      [uidA, uidB, uidB, uidA],
    );
    if (request.rows.length > 0) return true;

    // 3. Desafio compartilhado (onde ambos participam)
    const sharedChallenge = await this.db.query(
      `SELECT 1 FROM challenge_participants p1
       JOIN challenge_participants p2 ON p1.challenge_id = p2.challenge_id
       WHERE p1.participant_uid = $1 AND p2.participant_uid = $2
       LIMIT 1`,
      [uidA, uidB],
    );
    if (sharedChallenge.rows.length > 0) return true;

    // 4. Convite de desafio recente entre eles
    const challengeInvite = await this.db.query(
      `SELECT 1 FROM challenge_invitations
       WHERE (inviter_uid = $1 AND recipient_uid = $2) OR (inviter_uid = $3 AND recipient_uid = $4)
       LIMIT 1`,
      [uidA, uidB, uidB, uidA],
    );
    return challengeInvite.rows.length > 0;
  }
}
