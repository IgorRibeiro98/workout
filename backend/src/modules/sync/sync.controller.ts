import {
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
import type { SyncEntityStateResponse, SyncPullResponse, SyncPushResponse } from './sync.contract';
import { SyncErrors } from './sync.errors';
import { SyncService } from './sync.service';

/**
 * `POST /v1/sync/push`, `GET /v1/sync/pull` (T16.6) e `GET /v1/sync/entities/...` (T16.7.1).
 *
 * ```text
 * Device A  ──mutações──▶  push  ──▶  estado remoto + change log
 *                                              │
 * Device B  ◀──mudanças──  pull  ◀─────────────┘
 *                                              │
 * Device B  ◀──estado atual── entities ◀───────┘   somente leitura, por identidade
 * ```
 *
 * As três rotas exigem Bearer, e o dono de tudo é `@Principal().uid`. Não existe rota de sync
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
  async push(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
  ): Promise<SyncPushResponse> {
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
  async pull(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query('cursor') cursor?: string,
    @Query('limit') limit?: string,
  ): Promise<SyncPullResponse> {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    return this.service.pull(principal, requestId, cursor, limit);
  }

  /**
   * `GET /v1/sync/entities/{entityType}/{entitySyncId}` — o estado **atual** de um agregado
   * (T16.7.1).
   *
   * Existe para uma pergunta só, e ela é a que faltava: *a cópia da nuvem que o usuário está
   * vendo neste conflito ainda é a que o servidor tem?* Sem ela, "usar a versão da nuvem"
   * aplicaria localmente uma revision que o servidor já sabe estar superada.
   *
   * **Somente leitura, e isso é o contrato.** Ela não gasta revision, não anexa mudança ao change
   * log, não escreve no ledger de idempotência, não mexe em tombstone e não resolve conflito
   * nenhum. Há teste que conta as três tabelas antes e depois.
   *
   * Sem parâmetro de usuário, como o pull: a única identidade que existe aqui é a do token. Uma
   * identidade que pertence a outra conta responde `404` — indistinguível de inexistente.
   */
  @UseGuards(BearerAuthGuard)
  @Get('entities/:entityType/:entitySyncId')
  @HttpCode(HttpStatus.OK)
  async entityState(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Param('entityType') entityType: string,
    @Param('entitySyncId') entitySyncId: string,
  ): Promise<SyncEntityStateResponse> {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    return this.service.entityState(principal, requestId, entityType, entitySyncId);
  }
}
