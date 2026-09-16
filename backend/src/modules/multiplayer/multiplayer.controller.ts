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
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type {
  MultiplayerEventsPageDto,
  MultiplayerInvitationDto,
  MultiplayerRoomDto,
  PublishMultiplayerEventsResponse,
} from './multiplayer.contract';
import { MultiplayerService } from './multiplayer.service';
import {
  assertMultiplayerBodyWithinLimit,
  parseAfterCursor,
  parseCreateRoomRequest,
  parsePublishEventsRequest,
  parseWaitMs,
} from './multiplayer.validator';

/**
 * `/v1/multiplayer/rooms` (T19.5).
 *
 * Toda rota exige o token; a membership é conferida no serviço em **todas** as operações, e quem
 * não é membro recebe `404` — nunca "essa sala é de outra pessoa".
 */
@Controller('multiplayer/rooms')
@UseGuards(BearerAuthGuard)
export class MultiplayerController {
  constructor(private readonly service: MultiplayerService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async create(
    @Principal() principal: AuthenticatedPrincipal,
    @Body() body: unknown,
    @Req() req: Request,
  ): Promise<MultiplayerRoomDto> {
    assertMultiplayerBodyWithinLimit((req as RequestWithRawBody).rawBody);
    return this.service.createRoom(principal.uid, parseCreateRoomRequest(body));
  }

  @Get('invitations')
  async listInvitations(
    @Principal() principal: AuthenticatedPrincipal,
  ): Promise<MultiplayerInvitationDto[]> {
    return this.service.listInvitations(principal.uid);
  }

  @Get(':roomId')
  async getRoom(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('roomId') roomId: string,
  ): Promise<MultiplayerRoomDto> {
    return this.service.getRoom(principal.uid, roomId);
  }

  @Post(':roomId/join')
  @HttpCode(HttpStatus.OK)
  async join(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('roomId') roomId: string,
  ): Promise<MultiplayerRoomDto> {
    return this.service.join(principal.uid, roomId);
  }

  @Post(':roomId/leave')
  @HttpCode(HttpStatus.OK)
  async leave(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('roomId') roomId: string,
  ): Promise<{ success: boolean }> {
    return this.service.leave(principal.uid, roomId);
  }

  @Post(':roomId/close')
  @HttpCode(HttpStatus.OK)
  async close(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('roomId') roomId: string,
  ): Promise<{ success: boolean }> {
    return this.service.close(principal.uid, roomId);
  }

  @Post(':roomId/events')
  @HttpCode(HttpStatus.OK)
  async publishEvents(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('roomId') roomId: string,
    @Body() body: unknown,
    @Req() req: Request,
  ): Promise<PublishMultiplayerEventsResponse> {
    assertMultiplayerBodyWithinLimit((req as RequestWithRawBody).rawBody);
    return this.service.publishEvents(principal.uid, roomId, parsePublishEventsRequest(body));
  }

  /** `?after=<sequence>&wait=<ms>` — long-polling; sem `wait`, responde na hora. */
  @Get(':roomId/events')
  async pollEvents(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('roomId') roomId: string,
    @Query('after') after?: string,
    @Query('wait') wait?: string,
  ): Promise<MultiplayerEventsPageDto> {
    return this.service.pollEvents(
      principal.uid,
      roomId,
      parseAfterCursor(after),
      parseWaitMs(wait),
    );
  }
}
