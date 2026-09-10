import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  Put,
  Query,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type {
  CheckInCommentDto,
  CheckInCommentsDto,
  SocialFeedDto,
  WorkoutCheckInDto,
} from './workout-checkin.contract';
import { WorkoutCheckInService } from './workout-checkin.service';
import {
  assertCheckInBodyWithinLimit,
  parseCheckInDetailQuery,
  parseCommentRequest,
  parseCommentsQuery,
  parseCreateCheckInRequest,
  parseFeedQuery,
  parseReactionRequest,
  parseRemoveReactionRequest,
} from './workout-checkin.validator';

/**
 * As rotas de check-in de treino e do Feed social (T17.8), sob `/v1/social`.
 */
@Controller('social')
@UseGuards(BearerAuthGuard)
export class WorkoutCheckInController {
  constructor(private readonly service: WorkoutCheckInService) {}

  @Post('workout-checkins')
  @HttpCode(HttpStatus.CREATED)
  async create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<WorkoutCheckInDto> {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.createCheckIn(
      principal.uid,
      requestIdOf(request),
      parseCreateCheckInRequest(body),
    );
  }

  /**
   * `204` também quando a publicação já estava excluída: repetir converge (§66), e um `404` no
   * segundo `DELETE` faria um retry de resposta perdida parecer falha.
   */
  @Delete('workout-checkins/:checkInId')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
  ): Promise<void> {
    await this.service.deleteCheckIn(principal.uid, requestIdOf(request), checkInId);
  }

  @Get('feed')
  @HttpCode(HttpStatus.OK)
  async feed(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query() query: Record<string, unknown>,
  ): Promise<SocialFeedDto> {
    const { limit } = parseFeedQuery(query);
    return await this.service.getFeed(principal.uid, requestIdOf(request), limit);
  }

  // ================================================================ T17.9

  /**
   * O detalhe de uma publicação (§118). Mesmo DTO do Feed, mesma política.
   */
  @Get('workout-checkins/:checkInId')
  @HttpCode(HttpStatus.OK)
  async detail(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('checkInId') checkInId: string,
    @Query() query: Record<string, unknown>,
  ): Promise<WorkoutCheckInDto> {
    const { context } = parseCheckInDetailQuery(query);
    return await this.service.getCheckIn(principal.uid, checkInId, context);
  }

  /**
   * Adiciona ou troca a reação (§64/§66).
   */
  @Put('workout-checkins/:checkInId/reaction')
  @HttpCode(HttpStatus.OK)
  async putReaction(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Body() body: unknown,
  ): Promise<WorkoutCheckInDto> {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    const { type, context } = parseReactionRequest(body);
    return await this.service.putReaction(principal.uid, requestIdOf(request), checkInId, type, context);
  }

  /**
   * Remove a reação (§65). Idempotente: remover o que já não existe é sucesso.
   */
  @Delete('workout-checkins/:checkInId/reaction')
  @HttpCode(HttpStatus.OK)
  async removeReaction(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Body() body: unknown,
  ): Promise<WorkoutCheckInDto> {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.removeReaction(
      principal.uid,
      requestIdOf(request),
      checkInId,
      parseRemoveReactionRequest(body),
    );
  }

  @Get('workout-checkins/:checkInId/comments')
  @HttpCode(HttpStatus.OK)
  async listComments(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('checkInId') checkInId: string,
    @Query() query: Record<string, unknown>,
  ): Promise<CheckInCommentsDto> {
    const { limit, context } = parseCommentsQuery(query);
    return await this.service.listComments(principal.uid, checkInId, limit, context);
  }

  @Post('workout-checkins/:checkInId/comments')
  @HttpCode(HttpStatus.CREATED)
  async createComment(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Body() body: unknown,
  ): Promise<CheckInCommentDto> {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    const { body: commentBody, context } = parseCommentRequest(body);
    return await this.service.createComment(
      principal.uid,
      requestIdOf(request),
      checkInId,
      commentBody,
      context,
    );
  }

  /**
   * `204` também quando o comentário já estava apagado: repetir converge (§97), e um `404` no
   * segundo `DELETE` faria um retry de resposta perdida parecer falha.
   */
  @Delete('workout-checkins/:checkInId/comments/:commentId')
  @HttpCode(HttpStatus.NO_CONTENT)
  async deleteComment(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Param('commentId') commentId: string,
  ): Promise<void> {
    await this.service.deleteComment(principal.uid, requestIdOf(request), checkInId, commentId);
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId;
}
