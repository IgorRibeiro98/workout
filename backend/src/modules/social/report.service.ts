import { randomUUID } from 'node:crypto';
import {
  BadRequestException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { ReportRepository } from './report.repository';
import { FriendshipRepository } from './friendship.repository';
import { type CreateReportResponseDto, REPORT_REASONS, type ReportReason } from './report.contract';

const ONE_DAY_MS = 86_400_000;
const MAX_DAILY_REPORTS = 5;

@Injectable()
export class ReportService {
  constructor(
    private readonly reportRepo: ReportRepository,
    private readonly friendshipRepo: FriendshipRepository,
    private readonly logger: SparkLogger,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  createReport(
    reporterUid: string,
    reportedSocialId: string,
    reason: ReportReason,
  ): CreateReportResponseDto {
    if (!REPORT_REASONS.includes(reason)) {
      throw new BadRequestException('Motivo de denúncia inválido.');
    }

    const target = this.friendshipRepo.findProfileBySocialId(reportedSocialId);
    if (!target) {
      throw new NotFoundException('Perfil do usuário denunciado não encontrado.');
    }

    if (target.ownerUid === reporterUid) {
      throw new BadRequestException('Não é possível denunciar a si mesmo.');
    }

    // Valida contexto social legítimo (amigos, request mútuo, desafio compartilhado)
    const hasContext = this.reportRepo.hasLegitimateContext(reporterUid, target.ownerUid);
    if (!hasContext) {
      throw new ForbiddenException(
        'Você só pode denunciar usuários com quem possui interação social legítima (amizade, pedido pendente ou desafio compartilhado).',
      );
    }

    const now = this.clock.now();
    const oneDayAgo = now - ONE_DAY_MS;

    // Rate limit por reporter
    const dailyCount = this.reportRepo.countReportsByReporterSince(reporterUid, oneDayAgo);
    if (dailyCount >= MAX_DAILY_REPORTS) {
      throw new HttpException(
        {
          code: 'RATE_LIMIT_EXCEEDED',
          message: 'Limite diário de denúncias excedido. Tente novamente mais tarde.',
        },
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    // Prevenção de duplicatas recentes
    const isDuplicate = this.reportRepo.hasRecentReport(
      reporterUid,
      target.ownerUid,
      reason,
      oneDayAgo,
    );
    if (isDuplicate) {
      return {
        result: 'REPORT_RECEIVED',
        reportId: 'duplicate-suppressed',
      };
    }

    const reportId = randomUUID();
    this.reportRepo.createReport(reportId, reporterUid, target.ownerUid, reason, now);

    this.logger.info('social.report.created', {
      reporterUidPrefix: reporterUid.slice(0, 6),
      reason,
      reportId,
    });

    return {
      result: 'REPORT_RECEIVED',
      reportId,
    };
  }
}
