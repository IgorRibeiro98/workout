import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BlockService } from './block.service';
import type {
  BlockUserRequestDto,
  BlockUserResponseDto,
  ListBlockedUsersResponseDto,
  UnblockUserResponseDto,
} from './block.contract';
import { BLOCKS_ROUTE_PREFIX } from './block.contract';
import { assertBodyWithinLimit } from './social.validator';
import type { RequestWithRawBody } from '../../common/raw-body';

@Controller(BLOCKS_ROUTE_PREFIX)
@UseGuards(BearerAuthGuard)
export class BlockController {
  constructor(private readonly blockService: BlockService) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  async blockUser(
    @Principal() principal: AuthenticatedPrincipal,
    @Body() body: BlockUserRequestDto,
    @Req() req: Request,
  ): Promise<BlockUserResponseDto> {
    assertBodyWithinLimit((req as RequestWithRawBody).rawBody);
    return await this.blockService.blockUser(principal.uid, body.blockedSocialId);
  }

  @Delete(':socialId')
  @HttpCode(HttpStatus.OK)
  async unblockUser(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('socialId') socialId: string,
  ): Promise<UnblockUserResponseDto> {
    return await this.blockService.unblockUser(principal.uid, socialId);
  }

  @Get()
  async listBlocked(
    @Principal() principal: AuthenticatedPrincipal,
  ): Promise<ListBlockedUsersResponseDto> {
    return await this.blockService.listBlocked(principal.uid);
  }
}
