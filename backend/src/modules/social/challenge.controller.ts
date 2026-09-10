import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Inject,
  Param,
  Post,
  Query,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import { CLOCK, type Clock } from '../../common/clock';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type {
  AcceptChallengeResponseDto,
  CancelChallengeResponseDto,
  ChallengeDetailResponseDto,
  ChallengeInvitationListResponseDto,
  ChallengeListResponseDto,
  CreateChallengeResponseDto,
  DeclineChallengeResponseDto,
  LeaveChallengeResponseDto,
} from './challenge.contract';
import { CHALLENGE_INVITATIONS_ROUTE_PREFIX, CHALLENGES_ROUTE_PREFIX } from './challenge.contract';
import { ChallengeService } from './challenge.service';
import {
  assertChallengeBodyWithinLimit,
  parseChallengeId,
  parseChallengeListQuery,
  parseCreateChallengeRequest,
  parseInvitationId,
} from './challenge.validator';

/**
 * As rotas dos desafios (T17.3), sob `/v1/social`.
 */
@Controller()
export class ChallengeController {
  constructor(
    private readonly service: ChallengeService,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  // ------------------------------------------------------------------------------- desafios

  @UseGuards(BearerAuthGuard)
  @Post(CHALLENGES_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  async create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<CreateChallengeResponseDto> {
    assertChallengeBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.create(
      principal,
      requestIdOf(request),
      parseCreateChallengeRequest(body, this.clock.now()),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Get(CHALLENGES_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  async list(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): Promise<ChallengeListResponseDto> {
    return await this.service.list(
      principal,
      requestIdOf(request),
      parseChallengeListQuery(limit, cursor),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Get(`${CHALLENGES_ROUTE_PREFIX}/:challengeId`)
  @HttpCode(HttpStatus.OK)
  async detail(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('challengeId') challengeId: string,
  ): Promise<ChallengeDetailResponseDto> {
    return await this.service.detail(
      principal,
      requestIdOf(request),
      parseChallengeId(challengeId),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGES_ROUTE_PREFIX}/:challengeId/cancel`)
  @HttpCode(HttpStatus.OK)
  async cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('challengeId') challengeId: string,
  ): Promise<CancelChallengeResponseDto> {
    return await this.service.cancel(
      principal,
      requestIdOf(request),
      parseChallengeId(challengeId),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGES_ROUTE_PREFIX}/:challengeId/leave`)
  @HttpCode(HttpStatus.OK)
  async leave(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('challengeId') challengeId: string,
  ): Promise<LeaveChallengeResponseDto> {
    return await this.service.leave(principal, requestIdOf(request), parseChallengeId(challengeId));
  }

  // ------------------------------------------------------------------------------- convites

  @UseGuards(BearerAuthGuard)
  @Get(CHALLENGE_INVITATIONS_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  async invitations(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): Promise<ChallengeInvitationListResponseDto> {
    return await this.service.invitations(
      principal,
      requestIdOf(request),
      parseChallengeListQuery(limit, cursor),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGE_INVITATIONS_ROUTE_PREFIX}/:invitationId/accept`)
  @HttpCode(HttpStatus.OK)
  async accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): Promise<AcceptChallengeResponseDto> {
    return await this.service.accept(
      principal,
      requestIdOf(request),
      parseInvitationId(invitationId),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGE_INVITATIONS_ROUTE_PREFIX}/:invitationId/decline`)
  @HttpCode(HttpStatus.OK)
  async decline(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): Promise<DeclineChallengeResponseDto> {
    return await this.service.decline(
      principal,
      requestIdOf(request),
      parseInvitationId(invitationId),
    );
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId ?? 'unknown';
}
