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
import {
  REPORT_REASONS,
  REPORT_TARGET_TYPES,
  type CreateReportRequestDto,
  type CreateReportResponseDto,
  type ReportTargetType,
} from './report.contract';
import { WorkoutCheckInAccessPolicy } from './workout-checkin.access-policy';
import type { InteractionAudience } from './workout-checkin-context.resolver';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { WorkoutCheckInErrors } from './workout-checkin.errors';
import { COMMENTS_MAX_LIMIT } from './social-media.limits';

const ONE_DAY_MS = 86_400_000;
const MAX_DAILY_REPORTS = 5;

/**
 * A forma da T17.6 e a da T17.9, reduzidas a uma (§101/§107).
 *
 * `{ reportedSocialId }` continua sendo aceito porque um APK já instalado não pode parar de
 * denunciar quando o servidor sobe. Ele significa exatamente `targetType: 'USER'` com o `socialId`
 * como alvo — não é um caminho paralelo, é a mesma coisa escrita do jeito antigo.
 */
function normalizeTarget(request: CreateReportRequestDto): {
  targetType: ReportTargetType;
  targetId: string;
} {
  if (request.targetType !== undefined) {
    if (!REPORT_TARGET_TYPES.includes(request.targetType)) {
      throw new BadRequestException('Tipo de alvo de denúncia inválido.');
    }
    if (typeof request.targetId !== 'string' || request.targetId.trim().length === 0) {
      throw new BadRequestException('targetId é obrigatório.');
    }
    return { targetType: request.targetType, targetId: request.targetId.trim() };
  }

  if (typeof request.reportedSocialId === 'string' && request.reportedSocialId.trim().length > 0) {
    return { targetType: 'USER', targetId: request.reportedSocialId.trim() };
  }

  throw new BadRequestException('Informe targetType e targetId.');
}

@Injectable()
export class ReportService {
  constructor(
    private readonly reportRepo: ReportRepository,
    private readonly friendshipRepo: FriendshipRepository,
    // T17.9 — a mesma política do Feed decide se o conteúdo denunciado é visível para quem
    // denuncia (§104/§105). Reusá-la é o que impede uma sexta cópia de `amigo ∧ ativo ∧
    // ¬bloqueado` (§129/§130).
    private readonly checkInAccess: WorkoutCheckInAccessPolicy,
    private readonly interactions: CheckInInteractionRepository,
    private readonly logger: SparkLogger,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /**
   * `POST /v1/social/reports` (T17.6, com alvo desde a T17.9 §101–§110).
   *
   * ## O alvo é resolvido aqui, e o autor sai do banco (§103)
   *
   * O cliente diz **o que** está denunciando; o servidor descobre **de quem** é. Não existe
   * `reportedUid` no corpo, e a validação o recusa por nome: aceitá-lo deixaria qualquer pessoa
   * registrar uma denúncia contra a conta que quisesse, apontando para conteúdo que nem é dela.
   *
   * ## Só se quem denuncia consegue ver (§104/§105)
   *
   * Um check-in ou comentário invisível para o denunciante responde a mesma coisa que um
   * inexistente. Sem isso, `POST /reports` viraria um oráculo: bastaria denunciar um `checkInId`
   * qualquer e comparar as respostas para descobrir se ele existe.
   *
   * ## O que ela continua não fazendo (§108/§109)
   *
   * Não pune, não bloqueia, não oculta conteúdo e não notifica ninguém — nem o denunciado, nem o
   * denunciante. Ela registra uma linha para revisão operacional.
   */
  createReport(reporterUid: string, request: CreateReportRequestDto): CreateReportResponseDto {
    const reason = request.reason;
    if (!REPORT_REASONS.includes(reason)) {
      throw new BadRequestException('Motivo de denúncia inválido.');
    }

    const { targetType, targetId } = normalizeTarget(request);
    const resolved = this.resolveTarget(reporterUid, targetType, targetId);

    if (resolved.authorUid === reporterUid) {
      // §106 — não se denuncia o próprio conteúdo, nem a própria conta.
      throw new BadRequestException('Não é possível denunciar o próprio conteúdo.');
    }

    const now = this.clock.now();
    const oneDayAgo = now - ONE_DAY_MS;

    // Rate limit por reporter (§175).
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

    // Anti-duplicata por **alvo**: o toque duplo e o retry convergem, e denunciar dois
    // comentários diferentes da mesma pessoa continua sendo duas denúncias.
    const isDuplicate = this.reportRepo.hasRecentReport(
      reporterUid,
      targetType,
      resolved.targetId,
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
    this.reportRepo.createReport(
      reportId,
      reporterUid,
      resolved.authorUid,
      reason,
      targetType,
      resolved.targetId,
      now,
    );

    // §160/§161 — evento, prefixo de uid, motivo (vocabulário fechado) e tipo de alvo. Nunca a
    // legenda, o corpo do comentário, o `socialId` ou o uid completo.
    this.logger.info('social.report.created', {
      reporterUidPrefix: reporterUid.slice(0, 6),
      reason,
      targetType,
      reportId,
    });

    return {
      result: 'REPORT_RECEIVED',
      reportId,
    };
  }

  /**
   * De `(targetType, targetId)` para `(autor real, identificador canônico do alvo)`.
   *
   * Cada tipo tem a sua porta, e as três respondem a mesma coisa quando o alvo não existe ou não é
   * visível: uma recusa indistinguível. É o que impede a rota de virar um oráculo de existência.
   */
  private resolveTarget(
    reporterUid: string,
    targetType: ReportTargetType,
    targetId: string,
  ): { authorUid: string; targetId: string } {
    if (targetType === 'USER') {
      // O caminho da T17.6, preservado inteiro: alvo por `socialId`, com exigência de contexto
      // social legítimo (amizade, pedido pendente ou desafio compartilhado). Denunciar um
      // desconhecido continua não sendo possível.
      const target = this.friendshipRepo.findProfileBySocialId(targetId);
      if (!target) {
        throw new NotFoundException('Perfil do usuário denunciado não encontrado.');
      }
      if (target.ownerUid !== reporterUid) {
        const hasContext = this.reportRepo.hasLegitimateContext(reporterUid, target.ownerUid);
        if (!hasContext) {
          throw new ForbiddenException(
            'Você só pode denunciar usuários com quem possui interação social legítima (amizade, pedido pendente ou desafio compartilhado).',
          );
        }
      }
      return { authorUid: target.ownerUid, targetId };
    }

    if (targetType === 'CHECKIN') {
      // §104 — a regra é "quem enxerga pode denunciar", e desde a T17.12 enxergar inclui alcançar a
      // publicação por um Squad (T17.12 §128). `findAccessibleCheckIn` é a mesma política que serve
      // o detalhe e os bytes da foto: `self ∨ amizade ∨ Squad compartilhado`.
      //
      // Usar `findVisibleCheckIn` aqui deixaria um buraco que a T17.12 abriu: um membro de Squad
      // passou a poder **comentar** naquela publicação, e ficaria sem o mecanismo sancionado para
      // denunciá-la — justamente ele, que é quem a está vendo. Um post que o requisitante não
      // alcança por caminho nenhum continua indistinguível de inexistente.
      const visible = this.checkInAccess.findAccessibleCheckIn(reporterUid, targetId);
      if (!visible) {
        throw WorkoutCheckInErrors.invalidReportTarget('publicação não encontrada');
      }
      return { authorUid: visible.authorUid, targetId };
    }

    // COMMENT (§105; T17.12 §58/§59/§151) — duas condições: o post precisa ser visível **naquela
    // audiência** e o comentário precisa estar entre os que este viewer enxerga ali. A segunda
    // importa por causa do bloqueio de terceiro (§86) e, desde a T17.12, também por causa da
    // audiência: um comentário de `GROUP(X)` só pode ser denunciado por quem consegue lê-lo em `X`.
    //
    // A audiência sai do **próprio comentário**, e nunca de um parâmetro: o denunciante informa um
    // `commentId`, e o servidor deriva o contexto (§58). Conhecer o identificador continua não
    // concedendo nada.
    const comment = this.interactions.findComment(targetId);
    if (!comment || comment.deletedAt !== null) {
      throw WorkoutCheckInErrors.invalidReportTarget('comentário não encontrado');
    }

    const audience: InteractionAudience =
      comment.audienceType === 'GROUP' && comment.groupId !== null
        ? { type: 'GROUP', groupId: comment.groupId }
        : { type: 'FRIEND' };

    const post =
      audience.type === 'GROUP'
        ? this.checkInAccess.findGroupAccessibleCheckIn(
            reporterUid,
            comment.checkInId,
            audience.groupId,
          )
        : this.checkInAccess.findVisibleCheckIn(reporterUid, comment.checkInId);
    if (!post) {
      throw WorkoutCheckInErrors.invalidReportTarget('comentário não encontrado');
    }

    const visibleComments = this.interactions.listComments(
      reporterUid,
      comment.checkInId,
      audience,
      COMMENTS_MAX_LIMIT,
    );
    if (!visibleComments.some((item) => item.commentId === targetId)) {
      throw WorkoutCheckInErrors.invalidReportTarget('comentário não encontrado');
    }
    return { authorUid: comment.authorUid, targetId };
  }
}
