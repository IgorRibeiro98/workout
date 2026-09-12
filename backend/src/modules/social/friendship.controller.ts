import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
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
  AcceptFriendRequestResponseDto,
  CancelFriendRequestResponseDto,
  FriendListResponseDto,
  FriendLookupResponseDto,
  FriendRequestListResponseDto,
  RejectFriendRequestResponseDto,
  RemoveFriendResponseDto,
  SendFriendRequestResponseDto,
} from './friendship.contract';
import { FRIEND_REQUESTS_ROUTE_PREFIX, FRIENDS_ROUTE_PREFIX } from './friendship.contract';
import { FriendshipService } from './friendship.service';
import {
  parseFriendLookupRequest,
  parseListQuery,
  parseRequestId,
  parseSocialIdTarget,
} from './friendship.validator';
import { assertBodyWithinLimit } from './social.validator';

/**
 * As rotas do grafo social (T17.1), sob `/v1/social`.
 */
@Controller()
export class FriendshipController {
  constructor(private readonly service: FriendshipService) {}

  // ------------------------------------------------------------------------------- descoberta

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIENDS_ROUTE_PREFIX}/lookup`)
  @HttpCode(HttpStatus.OK)
  async lookup(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<FriendLookupResponseDto> {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.lookup(
      principal,
      requestIdOf(request),
      parseFriendLookupRequest(body).friendCode,
    );
  }

  // ------------------------------------------------------------------------------- pedidos

  @UseGuards(BearerAuthGuard)
  @Post(FRIEND_REQUESTS_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  async send(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<SendFriendRequestResponseDto> {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.send(
      principal,
      requestIdOf(request),
      parseSocialIdTarget(body).socialId,
    );
  }

  @UseGuards(BearerAuthGuard)
  @Get(`${FRIEND_REQUESTS_ROUTE_PREFIX}/incoming`)
  @HttpCode(HttpStatus.OK)
  async incoming(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): Promise<FriendRequestListResponseDto> {
    return await this.service.incoming(
      principal,
      requestIdOf(request),
      // A lista de pedidos ordena por `createdAt`: o primário do cursor é número, e um valor que
      // não seja número é cursor malformado — `400`, e não uma consulta com `NaN`.
      parseListQuery(limit, cursor, { numericPrimary: true }),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Get(`${FRIEND_REQUESTS_ROUTE_PREFIX}/outgoing`)
  @HttpCode(HttpStatus.OK)
  async outgoing(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): Promise<FriendRequestListResponseDto> {
    return await this.service.outgoing(
      principal,
      requestIdOf(request),
      parseListQuery(limit, cursor, { numericPrimary: true }),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIEND_REQUESTS_ROUTE_PREFIX}/:requestId/accept`)
  @HttpCode(HttpStatus.OK)
  async accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('requestId') friendRequestId: string,
  ): Promise<AcceptFriendRequestResponseDto> {
    return await this.service.accept(
      principal,
      requestIdOf(request),
      parseRequestId(friendRequestId),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIEND_REQUESTS_ROUTE_PREFIX}/:requestId/reject`)
  @HttpCode(HttpStatus.OK)
  async reject(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('requestId') friendRequestId: string,
  ): Promise<RejectFriendRequestResponseDto> {
    return await this.service.reject(
      principal,
      requestIdOf(request),
      parseRequestId(friendRequestId),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIEND_REQUESTS_ROUTE_PREFIX}/:requestId/cancel`)
  @HttpCode(HttpStatus.OK)
  async cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('requestId') friendRequestId: string,
  ): Promise<CancelFriendRequestResponseDto> {
    return await this.service.cancel(
      principal,
      requestIdOf(request),
      parseRequestId(friendRequestId),
    );
  }

  // ------------------------------------------------------------------------------- amizade

  @UseGuards(BearerAuthGuard)
  @Get(FRIENDS_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  async friends(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): Promise<FriendListResponseDto> {
    return await this.service.friends(
      principal,
      requestIdOf(request),
      parseListQuery(limit, cursor),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIENDS_ROUTE_PREFIX}/remove`)
  @HttpCode(HttpStatus.OK)
  async remove(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<RemoveFriendResponseDto> {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.remove(
      principal,
      requestIdOf(request),
      parseSocialIdTarget(body).socialId,
    );
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId ?? 'unknown';
}
