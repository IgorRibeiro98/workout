import { Controller, Get, HttpCode, HttpStatus, Post, Query, Req, UseGuards } from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { SyncPullResponse, SyncPushResponse } from './sync.contract';
import { SyncErrors } from './sync.errors';
import { SyncService } from './sync.service';

/**
 * `POST /v1/sync/push` e `GET /v1/sync/pull` (T16.6).
 *
 * ```text
 * Device A  ──mutações──▶  push  ──▶  estado remoto + change log
 *                                              │
 * Device B  ◀──mudanças──  pull  ◀─────────────┘
 * ```
 *
 * As duas rotas exigem Bearer, e o dono de tudo é `@Principal().uid`. Não existe rota de sync
 * pública, não existe `?uid=`, e o corpo não tem campo de dono. O `deviceId` que o cliente envia é
 * metadado de diagnóstico: conhecer o `deviceId` de outro aparelho não dá acesso a nada.
 *
 * ## Por que o corpo cru no push
 *
 * O `payloadHash` de cada agregado é calculado sobre a **forma canônica do texto recebido**,
 * preservando os tokens numéricos originais — é isso que faz Kotlin e TypeScript chegarem ao mesmo
 * hash sem que um imite o formatador de ponto flutuante do outro (`backup/canonical-json.ts`). Um
 * corpo já parseado teria perdido essa informação, e o hash deixaria de servir para idempotência,
 * detecção de conteúdo convergente e supressão de eco.
 */
@Controller('sync')
export class SyncController {
  constructor(private readonly service: SyncService) {}

  @UseGuards(BearerAuthGuard)
  @Post('push')
  @HttpCode(HttpStatus.OK)
  push(@Principal() principal: AuthenticatedPrincipal, @Req() request: Request): SyncPushResponse {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    const rawBody = (request as RequestWithRawBody).rawBody;
    if (rawBody === undefined) {
      // Sem corpo cru não há como calcular o hash canônico, e um hash "quase" não serve para
      // idempotência. Recusar é a única resposta honesta.
      throw SyncErrors.invalid('corpo do push ausente');
    }
    return this.service.push(principal, requestId, rawBody);
  }

  /**
   * As mudanças **da conta autenticada** depois do cursor.
   *
   * Sem parâmetro de usuário: a única identidade que existe aqui é a do token. Uma conta nunca
   * recebe o change log de outra, e não há como pedir.
   */
  @UseGuards(BearerAuthGuard)
  @Get('pull')
  @HttpCode(HttpStatus.OK)
  pull(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('cursor') cursor?: string,
    @Query('limit') limit?: string,
  ): SyncPullResponse {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    return this.service.pull(principal, requestId, cursor, limit);
  }
}
