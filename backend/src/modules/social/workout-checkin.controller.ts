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
  parseCommentRequest,
  parseCommentsQuery,
  parseCreateCheckInRequest,
  parseFeedQuery,
  parseReactionRequest,
} from './workout-checkin.validator';

/**
 * As rotas de check-in de treino e do Feed social (T17.8), sob `/v1/social`.
 *
 * ```text
 * Firebase ID Token ──▶ BearerAuthGuard ──▶ uid verificado
 *                                            │
 *   POST   /v1/social/workout-checkins                       ──┤ publica (idempotente)
 *   GET    /v1/social/workout-checkins/{id}                  ──┤ o detalhe de uma publicação
 *   DELETE /v1/social/workout-checkins/{id}                  ──┤ exclui a própria publicação
 *   PUT    /v1/social/workout-checkins/{id}/reaction         ──┤ adiciona ou troca a reação
 *   DELETE /v1/social/workout-checkins/{id}/reaction         ──┤ remove a reação
 *   GET    /v1/social/workout-checkins/{id}/comments         ──┤ os comentários visíveis
 *   POST   /v1/social/workout-checkins/{id}/comments         ──┤ comenta
 *   DELETE /v1/social/workout-checkins/{id}/comments/{cid}   ──┤ apaga (autor ou dono do post)
 *   GET    /v1/social/feed                                   ──┘ o feed do próprio viewer
 * ```
 *
 * A T17.9 acrescentou rotas ao **mesmo** agregado (§5): não existe `/v1/social/posts`, não existe
 * um segundo Feed e não existe um segundo tipo de publicação. As rotas de mídia moram em
 * `SocialMediaController` porque o corpo delas é binário e precisa de outro parser — a política de
 * acesso continua sendo a mesma (§128/§129).
 *
 * ## Nenhuma rota é pública
 *
 * Não existe `GET /users/{socialId}/posts` (§72), não existe `GET /feed?users=A,B,C` (§71) e não
 * existe feed sem token. A audiência de leitura é **derivada** do `uid` autenticado, das amizades
 * atuais e da política de bloqueio — o cliente não a propõe em nenhuma forma. Uma lista de
 * usuários no query string seria o cliente escolhendo de quem ler, que é exatamente o feed público
 * que a T17.8 não é.
 *
 * ## O corpo cru também é lido, só para medir
 *
 * O teto global do processo é o do backup (4 MiB), e uma rota que recebe dois identificadores não
 * tem por que aceitar isso. O conteúdo do corpo nunca vai para log: `SparkLogger` redige
 * `req.body`/`body`, e nenhum ponto deste módulo registra o corpo.
 */
@Controller('social')
@UseGuards(BearerAuthGuard)
export class WorkoutCheckInController {
  constructor(private readonly service: WorkoutCheckInService) {}

  @Post('workout-checkins')
  @HttpCode(HttpStatus.CREATED)
  create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): WorkoutCheckInDto {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.createCheckIn(
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
  remove(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
  ): void {
    this.service.deleteCheckIn(principal.uid, requestIdOf(request), checkInId);
  }

  @Get('feed')
  @HttpCode(HttpStatus.OK)
  feed(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query() query: Record<string, unknown>,
  ): SocialFeedDto {
    const { limit } = parseFeedQuery(query);
    return this.service.getFeed(principal.uid, requestIdOf(request), limit);
  }

  // ================================================================ T17.9

  /** O detalhe de uma publicação (§118). Mesmo DTO do Feed, mesma política. */
  @Get('workout-checkins/:checkInId')
  @HttpCode(HttpStatus.OK)
  detail(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('checkInId') checkInId: string,
  ): WorkoutCheckInDto {
    return this.service.getCheckIn(principal.uid, checkInId);
  }

  /**
   * Adiciona ou troca a reação (§64/§66).
   *
   * `PUT`, e não `POST`: a operação é idempotente e descreve o **estado** da reação desta pessoa
   * nesta publicação. Enviar `FIRE` duas vezes deixa o mesmo estado, e `MUSCLE` depois de `FIRE`
   * substitui — que é exatamente a semântica do verbo.
   */
  @Put('workout-checkins/:checkInId/reaction')
  @HttpCode(HttpStatus.OK)
  putReaction(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Body() body: unknown,
  ): WorkoutCheckInDto {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.putReaction(
      principal.uid,
      requestIdOf(request),
      checkInId,
      parseReactionRequest(body),
    );
  }

  /** Remove a reação (§65). Idempotente: remover o que já não existe é sucesso. */
  @Delete('workout-checkins/:checkInId/reaction')
  @HttpCode(HttpStatus.OK)
  removeReaction(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
  ): WorkoutCheckInDto {
    return this.service.removeReaction(principal.uid, requestIdOf(request), checkInId);
  }

  @Get('workout-checkins/:checkInId/comments')
  @HttpCode(HttpStatus.OK)
  listComments(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('checkInId') checkInId: string,
    @Query() query: Record<string, unknown>,
  ): CheckInCommentsDto {
    const { limit } = parseCommentsQuery(query);
    return this.service.listComments(principal.uid, checkInId, limit);
  }

  @Post('workout-checkins/:checkInId/comments')
  @HttpCode(HttpStatus.CREATED)
  createComment(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Body() body: unknown,
  ): CheckInCommentDto {
    assertCheckInBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.createComment(
      principal.uid,
      requestIdOf(request),
      checkInId,
      parseCommentRequest(body),
    );
  }

  /**
   * `204` também quando o comentário já estava apagado: repetir converge (§97), e um `404` no
   * segundo `DELETE` faria um retry de resposta perdida parecer falha.
   */
  @Delete('workout-checkins/:checkInId/comments/:commentId')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteComment(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('checkInId') checkInId: string,
    @Param('commentId') commentId: string,
  ): void {
    this.service.deleteComment(principal.uid, requestIdOf(request), checkInId, commentId);
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId;
}
