import { Body, Controller, HttpCode, HttpStatus, Post, Req, UseGuards } from '@nestjs/common';
import type { Request } from 'express';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { ReportService } from './report.service';
import type { CreateReportRequestDto, CreateReportResponseDto } from './report.contract';
import { REPORTS_ROUTE_PREFIX } from './report.contract';
import { assertBodyWithinLimit } from './social.validator';
import { rejectClientResolvedReportFields } from './report.validator';
import type { RequestWithRawBody } from '../../common/raw-body';

@Controller(REPORTS_ROUTE_PREFIX)
@UseGuards(BearerAuthGuard)
export class ReportController {
  constructor(private readonly reportService: ReportService) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  createReport(
    @Principal() principal: AuthenticatedPrincipal,
    @Body() body: CreateReportRequestDto,
    @Req() req: Request,
  ): CreateReportResponseDto {
    assertBodyWithinLimit((req as RequestWithRawBody).rawBody);
    // §103 — o corpo **não** pode carregar `reportedUid`. Recusar por nome, e não ignorar em
    // silêncio: um cliente que o envia acredita que ele decide quem é denunciado, e atender o
    // resto da requisição seria concordar em parte com essa crença.
    rejectClientResolvedReportFields(body as unknown);
    return this.reportService.createReport(principal.uid, body);
  }
}
