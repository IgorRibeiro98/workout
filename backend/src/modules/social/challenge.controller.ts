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
 *
 * ```text
 * Firebase ID Token ──▶ BearerAuthGuard ──▶ uid verificado
 *                                             │
 *  POST /v1/social/challenges                 ─┤ cria (transacional, idempotente)
 *  GET  /v1/social/challenges                 ─┤ os meus
 *  GET  /v1/social/challenges/:id             ─┤ regras + placar (só participantes)
 *  POST /v1/social/challenges/:id/cancel      ─┤ só o criador
 *  POST /v1/social/challenges/:id/leave       ─┤ só membro
 *  GET  /v1/social/challenge-invitations      ─┤ os convites que recebi (preview, sem placar)
 *  POST /v1/social/challenge-invitations/:id/accept  ─┤ só o destinatário
 *  POST /v1/social/challenge-invitations/:id/decline ─┘ só o destinatário
 * ```
 *
 * ## Nenhuma rota é pública, e nenhuma aceita pontuação
 *
 * Toda rota exige Bearer (§112). O dono é sempre `@Principal().uid`, saído de um token verificado:
 * não existe `?uid=`, não existe `creatorUid` no corpo, e **não existe rota de pontuação
 * parametrizada por uid** (§199) — o placar sai do detalhe do desafio, para quem participa dele.
 *
 * O validador recusa `score`, `progress`, `rank` e `winner` **por nome**, invalidando a
 * requisição inteira. Não há caminho por onde um cliente afirme quanto fez.
 *
 * ## Nenhum identificador social no caminho
 *
 * `challengeId` e `invitationId` são UUIDs opacos que só os participantes conhecem, e a
 * autorização não vem deles — vem de quem o token diz que você é. Um `socialId` no caminho
 * apareceria em log de proxy e de acesso; por isso ele só circula no **corpo** da criação, como
 * na T17.1.
 */
@Controller()
export class ChallengeController {
  constructor(
    private readonly service: ChallengeService,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  // ------------------------------------------------------------------------------- desafios

  /**
   * Cria um desafio e convida amigos, em uma transação (§44).
   *
   * O relógio do servidor chega ao validador porque "começa pelo menos amanhã" (§15) é uma
   * comparação com **agora** — e agora é do servidor, nunca um instante que o cliente tenha
   * mandado (§14). O corpo não tem campo de data absoluta nenhum, só datas de calendário.
   */
  @UseGuards(BearerAuthGuard)
  @Post(CHALLENGES_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): CreateChallengeResponseDto {
    assertChallengeBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.create(
      principal,
      requestIdOf(request),
      parseCreateChallengeRequest(body, this.clock.now()),
    );
  }

  @UseGuards(BearerAuthGuard)
  @Get(CHALLENGES_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  list(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): ChallengeListResponseDto {
    return this.service.list(
      principal,
      requestIdOf(request),
      parseChallengeListQuery(limit, cursor),
    );
  }

  /**
   * O desafio e o placar.
   *
   * Só participantes. Quem tem convite pendente recebe `404` e vê o preview pela rota de convites
   * (§98/§99): ver o progresso dos outros antes de consentir em mostrar o próprio é a assimetria
   * que o consentimento existe para impedir.
   */
  @UseGuards(BearerAuthGuard)
  @Get(`${CHALLENGES_ROUTE_PREFIX}/:challengeId`)
  @HttpCode(HttpStatus.OK)
  detail(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('challengeId') challengeId: string,
  ): ChallengeDetailResponseDto {
    return this.service.detail(principal, requestIdOf(request), parseChallengeId(challengeId));
  }

  /** Cancela. Só o criador; idempotente no toque duplo (§194). */
  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGES_ROUTE_PREFIX}/:challengeId/cancel`)
  @HttpCode(HttpStatus.OK)
  cancel(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('challengeId') challengeId: string,
  ): CancelChallengeResponseDto {
    return this.service.cancel(principal, requestIdOf(request), parseChallengeId(challengeId));
  }

  /** Sai. Só membro; o criador cancela (§62). Idempotente (§193). */
  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGES_ROUTE_PREFIX}/:challengeId/leave`)
  @HttpCode(HttpStatus.OK)
  leave(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('challengeId') challengeId: string,
  ): LeaveChallengeResponseDto {
    return this.service.leave(principal, requestIdOf(request), parseChallengeId(challengeId));
  }

  // ------------------------------------------------------------------------------- convites

  @UseGuards(BearerAuthGuard)
  @Get(CHALLENGE_INVITATIONS_ROUTE_PREFIX)
  @HttpCode(HttpStatus.OK)
  invitations(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): ChallengeInvitationListResponseDto {
    return this.service.invitations(
      principal,
      requestIdOf(request),
      parseChallengeListQuery(limit, cursor),
    );
  }

  /** Aceita. Só o destinatário, e só antes de o desafio começar (§51/§54). */
  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGE_INVITATIONS_ROUTE_PREFIX}/:invitationId/accept`)
  @HttpCode(HttpStatus.OK)
  accept(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): AcceptChallengeResponseDto {
    return this.service.accept(principal, requestIdOf(request), parseInvitationId(invitationId));
  }

  /** Recusa. Só o destinatário (§52). Idempotente (§192). */
  @UseGuards(BearerAuthGuard)
  @Post(`${CHALLENGE_INVITATIONS_ROUTE_PREFIX}/:invitationId/decline`)
  @HttpCode(HttpStatus.OK)
  decline(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('invitationId') invitationId: string,
  ): DeclineChallengeResponseDto {
    return this.service.decline(principal, requestIdOf(request), parseInvitationId(invitationId));
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId ?? 'unknown';
}
