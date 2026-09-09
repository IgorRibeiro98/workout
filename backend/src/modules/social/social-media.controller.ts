import {
  Controller,
  Get,
  Header,
  HttpCode,
  HttpStatus,
  NotFoundException,
  Param,
  Post,
  Query,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import type { Request, Response } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import { SocialMediaService } from './social-media.service';
import type { UploadedMediaDto } from './workout-checkin.contract';
import { WorkoutCheckInErrors } from './workout-checkin.errors';
import { parseUploadQuery } from './workout-checkin.validator';

/**
 * O caminho da rota de upload. Constante para que o middleware do parser binário e o controller
 * não possam discordar sobre onde ele está montado.
 */
export const CHECKIN_MEDIA_UPLOAD_PATH = '/v1/social/checkin-media';

/**
 * As rotas de mídia social (T17.9 §33/§49).
 *
 * ```text
 * POST /v1/social/checkin-media?sessionSyncId=&clientUploadId=   ← corpo = bytes da imagem
 * GET  /v1/social/media/{mediaId}                                ← bytes, para quem pode ver
 * ```
 *
 * ## Nenhuma das duas é pública (§48/§49/§50)
 *
 * Não existe `https://spark.example/media/<uuid>.webp`. Não existe diretório servido
 * estaticamente, não existe URL assinada e não existe caminho que o Caddy sirva do disco. A única
 * forma de obter os bytes é `GET /v1/social/media/{mediaId}` com um Firebase ID Token válido — e,
 * depois disso, com a mesma política de visibilidade do Feed (§128).
 *
 * ## O corpo é binário, e o parser é **só desta rota**
 *
 * O processo tem um parser JSON global (o do backup, 4 MiB). Uma imagem não é JSON, e transformá-la
 * em base64 para caber num campo cresceria 33% e ainda faria o servidor decodificar duas vezes.
 * `SocialModule` registra `express.raw` **apenas** em [CHECKIN_MEDIA_UPLOAD_PATH]: nenhuma outra
 * rota passa a bufferizar corpo, e o teto de bytes do upload é o da mídia, não o do backup.
 *
 * Multipart continua **fora**: ele traria `multer` para o caminho de execução por um ganho de
 * zero — o corpo tem um arquivo e nenhum campo, e os dois identificadores cabem no query string.
 */
@Controller()
@UseGuards(BearerAuthGuard)
export class SocialMediaController {
  constructor(private readonly service: SocialMediaService) {}

  @Post('social/checkin-media')
  @HttpCode(HttpStatus.CREATED)
  async upload(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    @Query() query: Record<string, unknown>,
  ): Promise<UploadedMediaDto> {
    const { sessionSyncId, clientUploadId } = parseUploadQuery(query);

    // `express.raw` entrega um `Buffer`. Quando o `Content-Type` não bate com o do parser, o corpo
    // chega como `{}` — e isso é uma requisição malformada, não uma imagem vazia.
    const body = request.body as unknown;
    if (!Buffer.isBuffer(body)) {
      throw WorkoutCheckInErrors.invalidImage('envie os bytes da imagem no corpo da requisição');
    }

    return this.service.upload({
      ownerUid: principal.uid,
      requestId: (request as RequestWithId).requestId,
      sessionSyncId,
      clientUploadId,
      bytes: body,
    });
  }

  /**
   * `GET /v1/social/media/{mediaId}` (§49/§50/§55).
   *
   * ## `Cache-Control: private, no-store` (§55)
   *
   * `private` porque a resposta é de **um** viewer: um proxy compartilhado que a guardasse
   * entregaria a foto de um amigo para quem passasse depois pela mesma cadeia. `no-store` porque a
   * autorização é reavaliada a cada leitura (§53/§54) — desfazer a amizade, bloquear e desativar o
   * Social revogam o acesso **na próxima requisição**, e um cache que respondesse por conta
   * própria transformaria "revogado" em "revogado quando a cópia expirar".
   *
   * É por isso que também não há `ETag` nem `Last-Modified` aqui: uma revalidação condicional
   * devolveria `304` sem que a política corresse, que é o mesmo problema com outro nome.
   *
   * ## `404` para tudo (§51/§52/§53/§54)
   *
   * Inexistente, de não-amigo, de par bloqueado (nas duas direções), de autor com Social
   * desativado, de check-in excluído e com arquivo ausente após um restore parcial (§141): todos
   * respondem `404`. Conhecer o `mediaId` não concede nada, e distinguir os casos diria a quem
   * perguntou o que existe na conta dos outros.
   */
  @Get('social/media/:mediaId')
  @Header('Cache-Control', 'private, no-store')
  @Header('Content-Security-Policy', "default-src 'none'; sandbox")
  // Sem sniffing: o navegador/WebView respeita o `Content-Type` que declaramos, e não uma
  // adivinhação sobre os bytes. A saída é sempre WebP produzido por este servidor, mas a defesa
  // não custa nada e fecha a classe inteira do problema.
  @Header('X-Content-Type-Options', 'nosniff')
  async download(
    @Principal() principal: AuthenticatedPrincipal,
    @Param('mediaId') mediaId: string,
    @Res() response: Response,
  ): Promise<void> {
    const found = await this.service.openViewable(principal.uid, mediaId);
    if (!found) {
      throw new NotFoundException({ code: 'MEDIA_NOT_FOUND', message: 'imagem não encontrada' });
    }

    response.setHeader('Content-Type', found.mimeType);
    response.setHeader('Content-Length', String(found.byteSize));
    found.stream.pipe(response);
  }
}
