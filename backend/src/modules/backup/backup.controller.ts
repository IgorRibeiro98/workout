import { Controller, Get, HttpCode, HttpStatus, Post, Req, Res, UseGuards } from '@nestjs/common';
import type { Request, Response } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { BackupMetadataResponse } from './backup.contract';
import { BackupErrors } from './backup.errors';
import { BackupService } from './backup.service';

/**
 * `POST /v1/backups` e `GET /v1/backups/latest` (T16.4).
 *
 * ```text
 * Spark Android → snapshot completo → Bearer <Firebase ID Token> → aqui → SQLite (imutável)
 * ```
 *
 * As duas rotas exigem Bearer, e o dono de tudo é `@Principal().uid`. Não existe rota de backup
 * pública, não existe `?uid=`, e o corpo não tem campo de dono.
 *
 * ## Por que o corpo cru
 *
 * O hash de integridade é calculado sobre a **forma canônica do texto recebido**, preservando os
 * tokens numéricos originais — é isso que faz Kotlin e TypeScript chegarem ao mesmo hash sem que
 * um precise imitar o formatador de ponto flutuante do outro (`canonical-json.ts`). Um corpo já
 * parseado teria perdido essa informação.
 *
 * ## O que **não** existe aqui
 *
 * `GET /v1/backups/{id}/content` não existe. Devolver o snapshot seria implementar metade do
 * restore — que a T16.5 vai desenhar com validação, preview e escrita transacional no Room. Nesta
 * fase, metadata basta e é o suficiente para a descoberta.
 */
@Controller('backups')
export class BackupController {
  constructor(private readonly service: BackupService) {}

  @UseGuards(BearerAuthGuard)
  @Post()
  create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Res() response: Response,
  ): void {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    const rawBody = (request as RequestWithRawBody).rawBody;
    if (rawBody === undefined) {
      // Sem corpo cru não há como calcular o hash canônico, e um hash "quase" não serve para
      // idempotência. Recusar é a única resposta honesta.
      throw BackupErrors.invalid('corpo do backup ausente');
    }

    const result = this.service.create(principal, requestId, rawBody);
    response.status(result.created ? HttpStatus.CREATED : HttpStatus.OK).json(result.metadata);
  }

  /**
   * A metadata do backup mais recente **da conta autenticada**.
   *
   * Sem parâmetro de usuário: a única identidade que existe aqui é a do token. Uma conta nunca vê
   * o backup de outra, e não há como pedir.
   */
  @UseGuards(BearerAuthGuard)
  @Get('latest')
  @HttpCode(HttpStatus.OK)
  latest(@Principal() principal: AuthenticatedPrincipal): BackupMetadataResponse {
    return this.service.latest(principal);
  }
}
