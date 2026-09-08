/**
 * Contrato do Módulo de Denúncias Sociais (T17.6).
 *
 * Denúncia minimalista para auditoria e revisão operacional no backend/VPS.
 * - Sem texto livre.
 * - Sem banimento automático.
 * - Exige contexto social legítimo (amigos, solicitação pendente ou desafio compartilhado).
 * - Usuário denunciado não recebe notificação.
 */

export const REPORTS_ROUTE_PREFIX = 'social/reports';

export const REPORT_REASONS = ['SPAM', 'HARASSMENT', 'INAPPROPRIATE_BEHAVIOR', 'OTHER'] as const;

export type ReportReason = (typeof REPORT_REASONS)[number];

export interface CreateReportRequestDto {
  readonly reportedSocialId: string;
  readonly reason: ReportReason;
}

export interface CreateReportResponseDto {
  readonly result: 'REPORT_RECEIVED';
  readonly reportId: string;
}
