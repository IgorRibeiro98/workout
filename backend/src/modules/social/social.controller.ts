import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Patch,
  Post,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { SocialMeResponse, SocialProfileResponse } from './social.contract';
import { SOCIAL_ROUTE_PREFIX } from './social.contract';
import { SocialService } from './social.service';
import {
  assertBodyWithinLimit,
  parseActivateRequest,
  parseUpdatePrivacyRequest,
  parseUpdateProfileRequest,
} from './social.validator';

/**
 * As rotas do domínio social (T17.0), sob `/v1/social`.
 *
 * ```text
 * Firebase ID Token ──▶ BearerAuthGuard ──▶ uid verificado
 *                                            │
 *   GET   /v1/social/me            ──────────┤  { enabled: false } | { enabled: true, profile }
 *   POST  /v1/social/me/activate   ──────────┤  cria identidade social (idempotente)
 *   PATCH /v1/social/me            ──────────┤  nome social
 *   PATCH /v1/social/me/privacy    ──────────┤  privacidade
 *   POST  /v1/social/me/disable    ──────────┤  desativa (preserva identidade)
 *   POST  /v1/social/me/enable     ──────────┘  reativa (mesma identidade)
 * ```
 *
 * ## Todas exigem Bearer, e todas falam do `me`
 *
 * Não existe rota social pública nesta fase. Não existe `?uid=`, `?socialId=`, `?friendCode=`,
 * busca por nome, busca por e-mail nem listagem: o dono de tudo é `@Principal().uid`, e a conta A
 * não tem como **pedir** o perfil da conta B — a pergunta não foi escrita.
 *
 * Lookup por `friendCode` é T17.1, e vai reusar a mesma normalização
 * (`social.identity.ts#normalizeFriendCode`) com match exato, sem fuzzy matching e com rate limit.
 *
 * ## Por que o corpo cru também é lido
 *
 * Só para medir. O teto global do processo é o do backup (4 MiB), e uma rota que escreve um nome
 * não tem por que aceitar isso. O conteúdo do corpo nunca vai para log: `SparkLogger` redige
 * `req.body`/`body`, e nenhum ponto deste módulo registra o corpo.
 */
@Controller(SOCIAL_ROUTE_PREFIX)
export class SocialController {
  constructor(private readonly service: SocialService) {}

  /**
   * O perfil social **da conta autenticada**.
   *
   * `{ enabled: false }` para quem nunca ativou: um estado normal do produto, e não um `404`
   * usado como fluxo feliz. Ler **não** cria nada.
   */
  @UseGuards(BearerAuthGuard)
  @Get('me')
  @HttpCode(HttpStatus.OK)
  async me(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
  ): Promise<SocialMeResponse> {
    return await this.service.me(principal, requestIdOf(request));
  }

  /**
   * Ativa os recursos sociais — o único caminho que cria identidade social.
   *
   * `200`, e não `201`, porque a rota é idempotente: a segunda chamada devolve o mesmo perfil, e
   * alternar entre `201` e `200` faria um reenvio depois de resposta perdida parecer diferente de
   * uma criação. O corpo traz **só** `displayName`; `socialId`, `friendCode`, `status` e os
   * timestamps são do servidor, e enviá-los recusa a requisição.
   */
  @UseGuards(BearerAuthGuard)
  @Post('me/activate')
  @HttpCode(HttpStatus.OK)
  async activate(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<SocialProfileResponse> {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.activate(principal, requestIdOf(request), parseActivateRequest(body));
  }

  /** Renomeia. Identidade (`socialId`, `friendCode`) não muda por `PATCH`, e nunca vai mudar. */
  @UseGuards(BearerAuthGuard)
  @Patch('me')
  @HttpCode(HttpStatus.OK)
  async updateProfile(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<SocialProfileResponse> {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.updateProfile(
      principal,
      requestIdOf(request),
      parseUpdateProfileRequest(body),
    );
  }

  /** Privacidade. Parcial: o que não veio no corpo continua como estava. */
  @UseGuards(BearerAuthGuard)
  @Patch('me/privacy')
  @HttpCode(HttpStatus.OK)
  async updatePrivacy(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): Promise<SocialProfileResponse> {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return await this.service.updatePrivacy(
      principal,
      requestIdOf(request),
      parseUpdatePrivacyRequest(body),
    );
  }

  /**
   * Desativa os recursos sociais.
   *
   * Não apaga Conta Spark, backup, sync, treino nem histórico — este módulo não tem acesso a
   * nenhum deles. O perfil continua existindo, desativado, para que reativar devolva a mesma
   * identidade.
   */
  @UseGuards(BearerAuthGuard)
  @Post('me/disable')
  @HttpCode(HttpStatus.OK)
  async disable(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
  ): Promise<SocialProfileResponse> {
    return await this.service.disable(principal, requestIdOf(request));
  }

  /** Reativa, com o mesmo `socialId` e o mesmo `friendCode`. */
  @UseGuards(BearerAuthGuard)
  @Post('me/enable')
  @HttpCode(HttpStatus.OK)
  async enable(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
  ): Promise<SocialProfileResponse> {
    return await this.service.enable(principal, requestIdOf(request));
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId ?? 'unknown';
}
