import {
  Body,
  Controller,
  Delete,
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
  CheckInGroupSharesDto,
  SocialGroupDetailDto,
  SocialGroupFeedDto,
  SocialGroupInvitationDto,
  SocialGroupInvitationListDto,
  SocialGroupListDto,
  SocialGroupMembersDto,
  SocialGroupShareDto,
  SocialGroupSummaryDto,
} from './social-group.contract';
import { SocialGroupService } from './social-group.service';
import {
  assertGroupBodyWithinLimit,
  parseCreateGroupRequest,
  parseCreateInvitationRequest,
  parseGroupFeedQuery,
  parseTransferOwnershipRequest,
  requirePathIdentifier,
} from './social-group.validator';

/**
 * As rotas de Squad privado e do feed de grupo (T17.11 §127), sob `/v1/social`.
 */
@Controller('social')
@UseGuards(BearerAuthGuard)
export class SocialGroupController {
  constructor(private readonly service: SocialGroupService) {}

  // ------------------------------------------------------------------ squads

  @Post('groups')
  @HttpCode(HttpStatus.CREATED)
  async create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<SocialGroupSummaryDto> {
    assertGroupBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.createGroup(
      principal.uid,
      requestIdOf(request),
      parseCreateGroupRequest(body),
    );
  }

  @Get('groups')
  @HttpCode(HttpStatus.OK)
  async list(@Principal() principal: AuthenticatedPrincipal): Promise<SocialGroupListDto> {
    return await this.service.listGroups(principal.uid);
  }

  @Get('groups/invitations')
  @HttpCode(HttpStatus.OK)
  async listInvitations(@Principal() principal: AuthenticatedPrincipal): Promise<SocialGroupInvitationListDto> {
    return await this.service.listInvitations(principal.uid);
  }

  @Get('groups/:groupId')
  @HttpCode(HttpStatus.OK)
  async detail(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('groupId') groupId: string,
  ): Promise<SocialGroupDetailDto> {
    return await this.service.getGroup(principal.uid, requirePathIdentifier(groupId, 'groupId'));
  }

  @Get('groups/:groupId/members')
  @HttpCode(HttpStatus.OK)
  async members(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('groupId') groupId: string,
  ): Promise<SocialGroupMembersDto> {
    return await this.service.listMembers(principal.uid, requirePathIdentifier(groupId, 'groupId'));
  }

  @Delete('groups/:groupId')
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
  ): Promise<void> {
    await this.service.deleteGroup(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
    );
  }

  // ------------------------------------------------------------------ convites

  @Post('groups/:groupId/invitations')
  @HttpCode(HttpStatus.CREATED)
  async invite(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Body() body: unknown,
  ): Promise<SocialGroupInvitationDto> {
    assertGroupBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.invite(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      parseCreateInvitationRequest(body),
    );
  }

  @Post('group-invitations/:invitationId/accept')
  @HttpCode(HttpStatus.OK)
  async accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): Promise<SocialGroupSummaryDto> {
    return await this.service.acceptInvitation(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(invitationId, 'invitationId'),
    );
  }

  @Post('group-invitations/:invitationId/decline')
  @HttpCode(HttpStatus.NO_CONTENT)
  async decline(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): Promise<void> {
    await this.service.declineInvitation(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(invitationId, 'invitationId'),
    );
  }

  @Post('group-invitations/:invitationId/cancel')
  @HttpCode(HttpStatus.NO_CONTENT)
  async cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): Promise<void> {
    await this.service.cancelInvitation(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(invitationId, 'invitationId'),
    );
  }

  // ------------------------------------------------------------------ composição

  @Post('groups/:groupId/leave')
  @HttpCode(HttpStatus.NO_CONTENT)
  async leave(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
  ): Promise<void> {
    await this.service.leaveGroup(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
    );
  }

  @Delete('groups/:groupId/members/:membershipId')
  @HttpCode(HttpStatus.NO_CONTENT)
  async removeMember(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Param('membershipId') membershipId: string,
  ): Promise<void> {
    await this.service.removeMember(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      requirePathIdentifier(membershipId, 'membershipId'),
    );
  }

  @Post('groups/:groupId/transfer-ownership')
  @HttpCode(HttpStatus.NO_CONTENT)
  async transferOwnership(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Body() body: unknown,
  ): Promise<void> {
    assertGroupBodyWithinLimit((request as RequestWithRawBody).rawBody);
    await this.service.transferOwnership(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      parseTransferOwnershipRequest(body).membershipId,
    );
  }

  // ------------------------------------------------------------------ feed

  @Post('groups/:groupId/checkins/:checkInId')
  @HttpCode(HttpStatus.CREATED)
  async share(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Param('checkInId') checkInId: string,
  ): Promise<SocialGroupShareDto> {
    return await this.service.shareCheckIn(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      requirePathIdentifier(checkInId, 'checkInId'),
    );
  }

  @Delete('groups/:groupId/checkins/:checkInId')
  @HttpCode(HttpStatus.NO_CONTENT)
  async unshare(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Param('checkInId') checkInId: string,
  ): Promise<void> {
    await this.service.unshareCheckIn(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      requirePathIdentifier(checkInId, 'checkInId'),
    );
  }

  @Get('groups/:groupId/feed')
  @HttpCode(HttpStatus.OK)
  async feed(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Query() query: Record<string, unknown>,
  ): Promise<SocialGroupFeedDto> {
    const { limit } = parseGroupFeedQuery(query);
    return await this.service.getFeed(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      limit,
    );
  }

  @Get('workout-checkins/:checkInId/groups')
  @HttpCode(HttpStatus.OK)
  async groupsForCheckIn(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('checkInId') checkInId: string,
  ): Promise<CheckInGroupSharesDto> {
    return await this.service.listGroupsForCheckIn(
      principal.uid,
      requirePathIdentifier(checkInId, 'checkInId'),
    );
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId;
}
