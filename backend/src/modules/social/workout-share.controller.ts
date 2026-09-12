import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import {
  CreateWorkoutShareRequest,
  WorkoutShareDetailDto,
  WorkoutShareItemDto,
  WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';
import {
  assertWorkoutShareBodyWithinLimit,
  parseCreateWorkoutShareRequest,
} from './workout-share.validator';
import { WorkoutShareService } from './workout-share.service';

@Controller('social/workout-shares')
@UseGuards(BearerAuthGuard)
export class WorkoutShareController {
  constructor(private readonly service: WorkoutShareService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async create(
    @Principal() principal: AuthenticatedPrincipal,
    @Body() body: CreateWorkoutShareRequest,
    @Req() req: Request,
  ): Promise<WorkoutShareDetailDto> {
    // Tamanho antes de forma, e forma antes do serviço: um corpo malformado é `400` do cliente, e
    // nunca uma exceção não tratada dentro do caso de uso.
    assertWorkoutShareBodyWithinLimit((req as RequestWithRawBody).rawBody);
    return this.service.createShare(principal.uid, parseCreateWorkoutShareRequest(body as unknown));
  }

  @Get('received')
  async listReceived(
    @Principal() principal: AuthenticatedPrincipal,
  ): Promise<WorkoutShareItemDto[]> {
    return this.service.listReceived(principal.uid);
  }

  @Get('sent')
  async listSent(@Principal() principal: AuthenticatedPrincipal): Promise<WorkoutShareItemDto[]> {
    return this.service.listSent(principal.uid);
  }

  @Get(':shareId')
  async getDetail(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): Promise<WorkoutShareDetailDto> {
    return this.service.getShareDetail(principal.uid, shareId);
  }

  @Post(':shareId/accept')
  @HttpCode(HttpStatus.OK)
  async accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): Promise<WorkoutTemplateShareSnapshotV1> {
    return this.service.acceptShare(principal.uid, shareId);
  }

  @Post(':shareId/complete-import')
  @HttpCode(HttpStatus.OK)
  async completeImport(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): Promise<{ success: boolean }> {
    return this.service.completeImport(principal.uid, shareId);
  }

  @Post(':shareId/decline')
  @HttpCode(HttpStatus.OK)
  async decline(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): Promise<{ success: boolean }> {
    return this.service.declineShare(principal.uid, shareId);
  }

  @Post(':shareId/cancel')
  @HttpCode(HttpStatus.OK)
  async cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): Promise<{ success: boolean }> {
    return this.service.cancelShare(principal.uid, shareId);
  }
}
