import {
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import type { BackupListResponse, BackupMetadataResponse } from './backup.contract';
import { BackupErrors } from './backup.errors';
import { BackupService } from './backup.service';

/**
 * `POST /v1/backups` e `GET /v1/backups/latest` (T16.4).
 *
 * ```text
 * Spark Android → snapshot completo → Bearer <Firebase ID Token> → aqui
 *                                        ├── Object Storage: o documento canônico (T18.1)
 *                                        └── PostgreSQL: metadata, hashes, ownership (imutável)
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
 * ## Leitura para o restore (T16.5)
 *
 * ```text
 * GET /v1/backups                    → metadata dos backups retidos daquela conta
 * GET /v1/backups/latest             → metadata do mais recente
 * GET /v1/backups/{backupId}         → metadata de um
 * GET /v1/backups/{backupId}/content → o snapshot canônico, verbatim
 * ```
 *
 * As quatro são **read-only**: nenhuma altera snapshot, item, `createdAt`, hash ou retenção.
 * Restaurar não consome o backup, e baixar não o marca.
 *
 * O conteúdo mora em rota separada da metadata de propósito: montar a lista da tela não pode
 * custar o download de todos os snapshots, e o app só baixa o documento inteiro quando o usuário
 * escolhe um para restaurar.
 */
@Controller('backups')
export class BackupController {
  constructor(private readonly service: BackupService) {}

  @UseGuards(BearerAuthGuard)
  @Post()
  async create(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Res() response: Response,
  ): Promise<void> {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    const rawBody = (request as RequestWithRawBody).rawBody;
    if (rawBody === undefined) {
      // Sem corpo cru não há como calcular o hash canônico, e um hash "quase" não serve para
      // idempotência. Recusar é a única resposta honesta.
      throw BackupErrors.invalid('corpo do backup ausente');
    }

    const result = await this.service.create(principal, requestId, rawBody);
    response.status(result.created ? HttpStatus.CREATED : HttpStatus.OK).json(result.metadata);
  }

  /**
   * Os backups retidos **da conta autenticada**.
   *
   * Sem `?uid=`, sem `ownerUid`, sem `X-User-Id`: a única identidade que existe aqui é a do token.
   * Uma conta sem backup recebe `{ "items": [] }` — estado normal, não erro.
   */
  @UseGuards(BearerAuthGuard)
  @Get()
  @HttpCode(HttpStatus.OK)
  async list(@Principal() principal: AuthenticatedPrincipal): Promise<BackupListResponse> {
    return this.service.list(principal);
  }

  @UseGuards(BearerAuthGuard)
  @Get('latest')
  @HttpCode(HttpStatus.OK)
  async latest(@Principal() principal: AuthenticatedPrincipal): Promise<BackupMetadataResponse> {
    return this.service.latest(principal);
  }

  /**
   * A metadata de um backup específico da conta autenticada.
   *
   * Declarado **depois** de `latest` porque a rota com parâmetro casaria com `/latest` primeiro se
   * viesse antes — e a lista de rotas do Nest é ordenada por declaração.
   */
  @UseGuards(BearerAuthGuard)
  @Get(':backupId')
  @HttpCode(HttpStatus.OK)
  async metadata(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('backupId') backupId: string,
  ): Promise<BackupMetadataResponse> {
    return this.service.metadata(principal, backupId);
  }

  /**
   * O snapshot canônico, verbatim — o corpo que o restore da T16.5 valida e aplica.
   */
  @UseGuards(BearerAuthGuard)
  @Get(':backupId/content')
  async content(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('backupId') backupId: string,
    @Req() request: Request,
    @Res() response: Response,
  ): Promise<void> {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';
    const payload = await this.service.content(principal, requestId, backupId);
    response
      .status(HttpStatus.OK)
      .type('application/json')
      // Os bytes vão como estão — um `Buffer`, e não um texto reencodado. `res.json(...)`
      // reserializaria o documento e desfaria a forma canônica — e com ela o hash que o cliente
      // vai conferir. Desde a T18.1 o serviço já conferiu tamanho e SHA-256 contra a metadata
      // antes de devolver (§24): nada corrompido chega aqui.
      .send(payload);
  }
}
