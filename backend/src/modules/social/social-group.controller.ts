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
 *
 * ```text
 * Firebase ID Token ──▶ BearerAuthGuard ──▶ uid verificado
 *                                            │
 *  POST   /v1/social/groups                                     ──┤ cria (idempotente)
 *  GET    /v1/social/groups                                     ──┤ os squads deste usuário
 *  GET    /v1/social/groups/invitations                         ──┤ convites recebidos
 *  GET    /v1/social/groups/{groupId}                           ──┤ o cabeçalho do detalhe
 *  GET    /v1/social/groups/{groupId}/members                   ──┤ participantes
 *  POST   /v1/social/groups/{groupId}/invitations               ──┤ convida (só o dono)
 *  POST   /v1/social/group-invitations/{id}/accept              ──┤ aceita (só o destinatário)
 *  POST   /v1/social/group-invitations/{id}/decline             ──┤ recusa
 *  POST   /v1/social/group-invitations/{id}/cancel              ──┤ cancela (só quem enviou)
 *  POST   /v1/social/groups/{groupId}/leave                     ──┤ sai (o dono não)
 *  DELETE /v1/social/groups/{groupId}/members/{membershipId}    ──┤ remove (só o dono)
 *  POST   /v1/social/groups/{groupId}/transfer-ownership        ──┤ transfere a posse
 *  DELETE /v1/social/groups/{groupId}                           ──┤ exclui (só o dono)
 *  POST   /v1/social/groups/{groupId}/checkins/{checkInId}      ──┤ compartilha o próprio check-in
 *  DELETE /v1/social/groups/{groupId}/checkins/{checkInId}      ──┤ desfaz o compartilhamento
 *  GET    /v1/social/groups/{groupId}/feed                      ──┤ o feed privado do squad
 *  GET    /v1/social/workout-checkins/{id}/groups               ──┘ em quais squads ele já está
 * ```
 *
 * ## O que **não** existe aqui, e é o ponto da fase (§4/§5)
 *
 * Não existe `GET /v1/social/groups/search`. Não existe `GET /v1/social/public/groups`. Não existe
 * rota que aceite um código, um link ou um QR de entrada. Um Squad é conhecido por quem está
 * dentro dele e por quem recebeu um convite, e não há terceira porta.
 *
 * ## O `groupId` da rota não autoriza nada (§59/§83)
 *
 * Toda rota de grupo começa por uma consulta de participação contra as tabelas. Quem não é membro
 * ativo recebe o mesmo `404` de "não existe" (§60) — em `GET`, em `POST` e em `DELETE`. O mesmo
 * vale para o `checkInId` e para o `mediaId`: conhecer um identificador nunca foi, e não pode
 * virar, permissão.
 *
 * ## O corpo cru também é lido, só para medir
 *
 * O teto global do processo é o do backup (4 MiB), e uma rota que recebe um nome e um
 * identificador não tem por que aceitar isso. O conteúdo do corpo nunca vai para log: `SparkLogger`
 * redige `req.body`, e nenhum ponto deste módulo registra o corpo (§124).
 */
@Controller('social')
@UseGuards(BearerAuthGuard)
export class SocialGroupController {
  constructor(private readonly service: SocialGroupService) {}

  // ------------------------------------------------------------------ squads

  @Post('groups')
  @HttpCode(HttpStatus.CREATED)
  create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): SocialGroupSummaryDto {
    assertGroupBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.createGroup(
      principal.uid,
      requestIdOf(request),
      parseCreateGroupRequest(body),
    );
  }

  @Get('groups')
  @HttpCode(HttpStatus.OK)
  list(@Principal() principal: AuthenticatedPrincipal): SocialGroupListDto {
    return this.service.listGroups(principal.uid);
  }

  /**
   * Declarada **antes** de `groups/:groupId` de propósito.
   *
   * O Nest resolve rotas na ordem de declaração, e `groups/invitations` casaria com
   * `groups/:groupId` se viesse depois — a lista de convites viraria uma consulta por um Squad
   * chamado "invitations", que responderia `404` para todo mundo.
   */
  @Get('groups/invitations')
  @HttpCode(HttpStatus.OK)
  listInvitations(@Principal() principal: AuthenticatedPrincipal): SocialGroupInvitationListDto {
    return this.service.listInvitations(principal.uid);
  }

  @Get('groups/:groupId')
  @HttpCode(HttpStatus.OK)
  detail(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('groupId') groupId: string,
  ): SocialGroupDetailDto {
    return this.service.getGroup(principal.uid, requirePathIdentifier(groupId, 'groupId'));
  }

  @Get('groups/:groupId/members')
  @HttpCode(HttpStatus.OK)
  members(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('groupId') groupId: string,
  ): SocialGroupMembersDto {
    return this.service.listMembers(principal.uid, requirePathIdentifier(groupId, 'groupId'));
  }

  /** `204` sempre que o Squad já não existe: repetir converge (§46). */
  @Delete('groups/:groupId')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
  ): void {
    this.service.deleteGroup(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
    );
  }

  // ------------------------------------------------------------------ convites

  @Post('groups/:groupId/invitations')
  @HttpCode(HttpStatus.CREATED)
  invite(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Body() body: unknown,
  ): SocialGroupInvitationDto {
    assertGroupBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.invite(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      parseCreateInvitationRequest(body),
    );
  }

  /**
   * O aceite mora em `/v1/social/group-invitations/{id}`, e não sob `/groups/{groupId}`.
   *
   * O motivo é de produto: quem aceita ainda **não** é membro, e uma rota aninhada no grupo daria a
   * impressão de que o `groupId` faz parte da autorização. Aqui a autorização é o convite —
   * ele é do destinatário, está pendente e não expirou —, e o Squad é consequência disso.
   */
  @Post('group-invitations/:invitationId/accept')
  @HttpCode(HttpStatus.OK)
  accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): SocialGroupSummaryDto {
    return this.service.acceptInvitation(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(invitationId, 'invitationId'),
    );
  }

  @Post('group-invitations/:invitationId/decline')
  @HttpCode(HttpStatus.NO_CONTENT)
  decline(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): void {
    this.service.declineInvitation(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(invitationId, 'invitationId'),
    );
  }

  @Post('group-invitations/:invitationId/cancel')
  @HttpCode(HttpStatus.NO_CONTENT)
  cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): void {
    this.service.cancelInvitation(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(invitationId, 'invitationId'),
    );
  }

  // ------------------------------------------------------------------ composição

  /** `204` também quando já não se está no Squad: sair é idempotente (§38). */
  @Post('groups/:groupId/leave')
  @HttpCode(HttpStatus.NO_CONTENT)
  leave(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
  ): void {
    this.service.leaveGroup(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
    );
  }

  /** O alvo é o `membershipId` — nunca um uid, nunca um `socialId` (§36/§37/§42). */
  @Delete('groups/:groupId/members/:membershipId')
  @HttpCode(HttpStatus.NO_CONTENT)
  removeMember(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Param('membershipId') membershipId: string,
  ): void {
    this.service.removeMember(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      requirePathIdentifier(membershipId, 'membershipId'),
    );
  }

  @Post('groups/:groupId/transfer-ownership')
  @HttpCode(HttpStatus.NO_CONTENT)
  transferOwnership(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Body() body: unknown,
  ): void {
    assertGroupBodyWithinLimit((request as RequestWithRawBody).rawBody);
    this.service.transferOwnership(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      parseTransferOwnershipRequest(body).membershipId,
    );
  }

  // ------------------------------------------------------------------ feed

  /**
   * Compartilha um check-in **próprio e já publicado** neste Squad (§51/§53/§56).
   *
   * Sem corpo: os dois identificadores estão na rota, e não há nada a propor. Um corpo aqui seria
   * espaço para o cliente tentar propor autoria, data ou audiência — as três decididas no servidor.
   */
  @Post('groups/:groupId/checkins/:checkInId')
  @HttpCode(HttpStatus.CREATED)
  share(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Param('checkInId') checkInId: string,
  ): SocialGroupShareDto {
    return this.service.shareCheckIn(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      requirePathIdentifier(checkInId, 'checkInId'),
    );
  }

  /** Desfaz o compartilhamento (§128). **Não apaga o check-in.** Idempotente. */
  @Delete('groups/:groupId/checkins/:checkInId')
  @HttpCode(HttpStatus.NO_CONTENT)
  unshare(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Param('checkInId') checkInId: string,
  ): void {
    this.service.unshareCheckIn(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      requirePathIdentifier(checkInId, 'checkInId'),
    );
  }

  @Get('groups/:groupId/feed')
  @HttpCode(HttpStatus.OK)
  feed(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('groupId') groupId: string,
    @Query() query: Record<string, unknown>,
  ): SocialGroupFeedDto {
    const { limit } = parseGroupFeedQuery(query);
    return this.service.getFeed(
      principal.uid,
      requestIdOf(request),
      requirePathIdentifier(groupId, 'groupId'),
      limit,
    );
  }

  /**
   * Em quais Squads este check-in **próprio** já está (§141).
   *
   * Mora aqui, e não em `WorkoutCheckInController`, porque a resposta é sobre Squads: o dono do
   * contrato de grupo é este controller, e um segundo lugar montando o caminho seria um segundo
   * dono.
   */
  @Get('workout-checkins/:checkInId/groups')
  @HttpCode(HttpStatus.OK)
  groupsForCheckIn(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('checkInId') checkInId: string,
  ): CheckInGroupSharesDto {
    return this.service.listGroupsForCheckIn(
      principal.uid,
      requirePathIdentifier(checkInId, 'checkInId'),
    );
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId;
}
