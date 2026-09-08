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
 *
 * ```text
 * Firebase ID Token ──▶ BearerAuthGuard ──▶ uid verificado
 *                                             │
 *  POST /v1/social/friends/lookup             ─┤ resolve um friendCode exato → preview mínimo
 *  POST /v1/social/friends/remove             ─┤ desfaz a amizade (qualquer um do par)
 *  GET  /v1/social/friends                    ─┤ meus amigos
 *  POST /v1/social/friend-requests            ─┤ envia (ou resolve o cruzamento)
 *  GET  /v1/social/friend-requests/incoming   ─┤ recebidos, pendentes
 *  GET  /v1/social/friend-requests/outgoing   ─┤ enviados, pendentes
 *  POST /v1/social/friend-requests/:id/accept ─┤ só o destinatário
 *  POST /v1/social/friend-requests/:id/reject ─┤ só o destinatário
 *  POST /v1/social/friend-requests/:id/cancel ─┘ só quem enviou
 * ```
 *
 * ## Nenhuma rota é pública, e nenhuma aceita "quem sou eu" vindo do cliente
 *
 * O dono é sempre `@Principal().uid`, saído de um token verificado. Não existe `?uid=`, não existe
 * `ownerUid` no corpo (o validador recusa a requisição inteira), e não existe rota que devolva
 * perfis que o chamador não nomeou: nem busca por nome, nem por e-mail, nem listagem global, nem
 * sugestão de pessoas. A única pergunta sobre um perfil alheio é o lookup por código exato.
 *
 * ## Por que `POST` em coisas que parecem leitura
 *
 * `friends/lookup` e `friends/remove` levam o identificador no **corpo**, não na URL. A URL é a
 * parte que vaza mais fácil — log de proxy, log de acesso, histórico —, e um `friendCode` numa
 * lista de logs é uma lista de convites válidos. O corpo não aparece em nenhum desses lugares, e o
 * `SparkLogger` já o redige.
 */
@Controller()
export class FriendshipController {
  constructor(private readonly service: FriendshipService) {}

  // ------------------------------------------------------------------------------- descoberta

  /**
   * Resolve um `friendCode` exato.
   *
   * Match exato sobre a forma normalizada da T17.0 — sem `LIKE`, sem prefixo, sem distância de
   * edição, sem sugestão. Consultar **não** envia pedido: quem envia é a rota abaixo, depois de o
   * usuário ver quem apareceu.
   */
  @UseGuards(BearerAuthGuard)
  @Post(`${FRIENDS_ROUTE_PREFIX}/lookup`)
  @HttpCode(HttpStatus.OK)
  lookup(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): FriendLookupResponseDto {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.lookup(
      principal,
      requestIdOf(request),
      parseFriendLookupRequest(body).friendCode,
    );
  }

  // ------------------------------------------------------------------------------- pedidos

  /** Envia um pedido. Idempotente no reenvio, e resolve o cruzamento em uma transação. */
  @UseGuards(BearerAuthGuard)
  @Post(FRIEND_REQUESTS_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  send(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): SendFriendRequestResponseDto {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.send(principal, requestIdOf(request), parseSocialIdTarget(body).socialId);
  }

  @UseGuards(BearerAuthGuard)
  @Get(`${FRIEND_REQUESTS_ROUTE_PREFIX}/incoming`)
  @HttpCode(HttpStatus.OK)
  incoming(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): FriendRequestListResponseDto {
    return this.service.incoming(principal, requestIdOf(request), parseListQuery(limit, cursor));
  }

  @UseGuards(BearerAuthGuard)
  @Get(`${FRIEND_REQUESTS_ROUTE_PREFIX}/outgoing`)
  @HttpCode(HttpStatus.OK)
  outgoing(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): FriendRequestListResponseDto {
    return this.service.outgoing(principal, requestIdOf(request), parseListQuery(limit, cursor));
  }

  /**
   * Aceita.
   *
   * O `requestId` vai no caminho porque ele é opaco e não identifica ninguém: é um UUID que só os
   * dois participantes conhecem, e a autorização não vem dele — vem de quem o token diz que você
   * é. Um `friendCode` no caminho seria outra história, e por isso ele não está em nenhum.
   */
  @UseGuards(BearerAuthGuard)
  @Post(`${FRIEND_REQUESTS_ROUTE_PREFIX}/:requestId/accept`)
  @HttpCode(HttpStatus.OK)
  accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('requestId') friendRequestId: string,
  ): AcceptFriendRequestResponseDto {
    return this.service.accept(principal, requestIdOf(request), parseRequestId(friendRequestId));
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIEND_REQUESTS_ROUTE_PREFIX}/:requestId/reject`)
  @HttpCode(HttpStatus.OK)
  reject(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('requestId') friendRequestId: string,
  ): RejectFriendRequestResponseDto {
    return this.service.reject(principal, requestIdOf(request), parseRequestId(friendRequestId));
  }

  @UseGuards(BearerAuthGuard)
  @Post(`${FRIEND_REQUESTS_ROUTE_PREFIX}/:requestId/cancel`)
  @HttpCode(HttpStatus.OK)
  cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('requestId') friendRequestId: string,
  ): CancelFriendRequestResponseDto {
    return this.service.cancel(principal, requestIdOf(request), parseRequestId(friendRequestId));
  }

  // ------------------------------------------------------------------------------- amizade

  @UseGuards(BearerAuthGuard)
  @Get(FRIENDS_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  friends(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): FriendListResponseDto {
    return this.service.friends(principal, requestIdOf(request), parseListQuery(limit, cursor));
  }

  /** Desfaz a amizade. Não bloqueia, não apaga treino e não impede uma nova amizade depois. */
  @UseGuards(BearerAuthGuard)
  @Post(`${FRIENDS_ROUTE_PREFIX}/remove`)
  @HttpCode(HttpStatus.OK)
  remove(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): RemoveFriendResponseDto {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.remove(principal, requestIdOf(request), parseSocialIdTarget(body).socialId);
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId ?? 'unknown';
}
