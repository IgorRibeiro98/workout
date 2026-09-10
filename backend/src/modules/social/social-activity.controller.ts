import { Controller, Get, Req, UseGuards } from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import {
  FRIEND_RANKING_LAST_7_DAYS_ROUTE,
  SOCIAL_ACTIVITY_ROUTE,
  SOCIAL_ROUTE_PREFIX,
  type FriendRankingResponse,
  type SocialActivityResponse,
} from './social.contract';
import { SocialActivityService } from './social-activity.service';
import { FriendRankingService } from './friend-ranking.service';

/**
 * Endpoints de Atividade dos Amigos e Rankings Contextuais (T17.4).
 *
 * GET /v1/social/activity
 * GET /v1/social/rankings/last-7-days
 */
@Controller(SOCIAL_ROUTE_PREFIX)
export class SocialActivityController {
  constructor(
    private readonly activityService: SocialActivityService,
    private readonly rankingService: FriendRankingService,
  ) {}

  @Get(SOCIAL_ACTIVITY_ROUTE)
  @UseGuards(BearerAuthGuard)
  async getActivity(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() req: Request,
  ): Promise<SocialActivityResponse> {
    const requestId = (req as RequestWithId).requestId ?? 'unknown';
    return await this.activityService.getActivity(principal, requestId);
  }

  @Get(FRIEND_RANKING_LAST_7_DAYS_ROUTE)
  @UseGuards(BearerAuthGuard)
  async getRankingLast7Days(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() req: Request,
  ): Promise<FriendRankingResponse> {
    const requestId = (req as RequestWithId).requestId ?? 'unknown';
    return await this.rankingService.getRanking(principal, requestId);
  }
}
