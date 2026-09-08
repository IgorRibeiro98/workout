import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Req,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import { FRIENDS_ROUTE_PREFIX } from './friendship.contract';
import type {
  SocialFriendProfileResponse,
  SocialProgressSharingResponse,
} from './social-profile.contract';
import {
  FRIEND_PROFILE_ROUTE_SUFFIX,
  PROFILE_PREVIEW_ROUTE,
  PROGRESS_SHARING_ROUTE,
} from './social-profile.contract';
import { SocialProfileService } from './social-profile.service';
import { parseSocialIdParam, parseUpdateProgressSharingRequest } from './social-profile.validator';
import { assertBodyWithinLimit } from './social.validator';

/**
 * As rotas do perfil social enriquecido (T17.2), sob `/v1/social`.
 *
 * ```text
 * Firebase ID Token ──▶ BearerAuthGuard ──▶ uid verificado
 *                                             │
 * GET   /v1/social/friends/:socialId/profile ─┤ exige amizade ativa dos dois lados
 * GET   /v1/social/me/profile-preview        ─┤ o que um amigo veria de mim agora
 * GET   /v1/social/me/progress-sharing       ─┤ minhas preferências + disponibilidade
 * PATCH /v1/social/me/progress-sharing       ─┘ altera preferências (parcial)
 * ```
 *
 * ## Nenhuma rota pública, e nenhuma busca (§53/§54)
 *
 * As quatro exigem `Authorization: Bearer`. Não existe `/social/profiles/:socialId` sem amizade,
 * não existe busca por nome, por e-mail ou listagem global, e não existe rota que devolva o
 * progresso de vários amigos de uma vez (§124) — um endpoint de colheita é a diferença entre
 * "meu amigo vê meu progresso" e "qualquer um baixa o progresso de todo mundo".
 *
 * ## O controller não decide nada
 *
 * Ele autentica (guard), valida a forma (validator) e chama o serviço. Ele **não** consulta
 * `friendships`, não lê preferências e não monta DTO: a decisão de acesso mora em um lugar só
 * (§114/§115), e a projeção também. Um `SELECT` daqui seria a segunda cópia da regra, e a segunda
 * cópia é sempre a que envelhece.
 */
@Controller()
export class SocialProfileController {
  constructor(private readonly service: SocialProfileService) {}

  /**
   * O perfil enriquecido de um amigo.
   *
   * O `socialId` vai no caminho — e o log de acesso deste servidor registra o **padrão** da rota
   * (`/friends/:socialId/profile`), nunca o valor. É a mesma escolha já feita para o `requestId`
   * na T17.1; o que continua fora de qualquer URL é o `friendCode`, que é convite.
   */
  @UseGuards(BearerAuthGuard)
  @Get(`${FRIENDS_ROUTE_PREFIX}/:socialId/${FRIEND_PROFILE_ROUTE_SUFFIX}`)
  @HttpCode(HttpStatus.OK)
  friendProfile(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('socialId') socialId: string,
  ): SocialFriendProfileResponse {
    return this.service.friendProfile(
      principal,
      requestIdOf(request),
      parseSocialIdParam(socialId),
    );
  }

  /** Exatamente o que um amigo veria de mim agora — pelo mesmo pipeline (§41). */
  @UseGuards(BearerAuthGuard)
  @Get(PROFILE_PREVIEW_ROUTE)
  @HttpCode(HttpStatus.OK)
  preview(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
  ): SocialFriendProfileResponse {
    return this.service.preview(principal, requestIdOf(request));
  }

  /** O que eu compartilho, e o que o servidor consegue mostrar de cada campo. */
  @UseGuards(BearerAuthGuard)
  @Get(PROGRESS_SHARING_ROUTE)
  @HttpCode(HttpStatus.OK)
  progressSharing(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
  ): SocialProgressSharingResponse {
    return this.service.progressSharing(principal, requestIdOf(request));
  }

  /**
   * Altera o que eu compartilho. Parcial: o que não veio no corpo continua como estava.
   *
   * O corpo carrega **preferência**, nunca progresso: `level`, `streak` e `weeklyWorkoutCount` são
   * recusados por nome pelo validador (§85–§87). O `updatedAt` da resposta é do relógio do
   * servidor (§58).
   */
  @UseGuards(BearerAuthGuard)
  @Patch(PROGRESS_SHARING_ROUTE)
  @HttpCode(HttpStatus.OK)
  updateProgressSharing(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Body() body: unknown,
  ): SocialProgressSharingResponse {
    assertBodyWithinLimit((request as RequestWithRawBody).rawBody);
    return this.service.updateProgressSharing(
      principal,
      requestIdOf(request),
      parseUpdateProgressSharingRequest(body),
    );
  }
}

function requestIdOf(request: Request): string {
  return (request as RequestWithId).requestId ?? 'unknown';
}
