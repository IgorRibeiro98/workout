import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  UseGuards,
} from '@nestjs/common';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import {
  CreateWorkoutShareRequest,
  WorkoutShareDetailDto,
  WorkoutShareItemDto,
  WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';
import { WorkoutShareService } from './workout-share.service';

@Controller('social/workout-shares')
@UseGuards(BearerAuthGuard)
export class WorkoutShareController {
  constructor(private readonly service: WorkoutShareService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  create(
    @Principal() principal: AuthenticatedPrincipal,
    @Body() body: CreateWorkoutShareRequest,
  ): WorkoutShareDetailDto {
    return this.service.createShare(principal.uid, body);
  }

  @Get('received')
  listReceived(@Principal() principal: AuthenticatedPrincipal): WorkoutShareItemDto[] {
    return this.service.listReceived(principal.uid);
  }

  @Get('sent')
  listSent(@Principal() principal: AuthenticatedPrincipal): WorkoutShareItemDto[] {
    return this.service.listSent(principal.uid);
  }

  @Get(':shareId')
  getDetail(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): WorkoutShareDetailDto {
    return this.service.getShareDetail(principal.uid, shareId);
  }

  @Post(':shareId/accept')
  @HttpCode(HttpStatus.OK)
  accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): WorkoutTemplateShareSnapshotV1 {
    return this.service.acceptShare(principal.uid, shareId);
  }

  @Post(':shareId/complete-import')
  @HttpCode(HttpStatus.OK)
  completeImport(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): { success: boolean } {
    return this.service.completeImport(principal.uid, shareId);
  }

  @Post(':shareId/decline')
  @HttpCode(HttpStatus.OK)
  decline(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): { success: boolean } {
    return this.service.declineShare(principal.uid, shareId);
  }

  @Post(':shareId/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('shareId') shareId: string,
  ): { success: boolean } {
    return this.service.cancelShare(principal.uid, shareId);
  }
}
