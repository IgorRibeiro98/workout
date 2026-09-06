import { Body, Controller, PayloadTooLargeException, Post, Req, UseGuards } from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithRawBody } from '../../common/raw-body';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import { AiCoachService } from './ai-coach.service';
import { AI_ERROR_CODES, type AiCoachHttpResponse } from './ai-coach.contract';
import { MAX_AI_REQUEST_BODY_BYTES } from './ai-coach.limits';

/**
 * `POST /v1/ai/coach` — a nova fronteira online do Coach IA.
 *
 * ```text
 * Android (Room → ContextBuilder) → Firebase ID Token + contexto
 *   → aqui → prompt/modelo do servidor → Gemini → validação → Android valida de novo → UI
 * ```
 *
 * **Um** endpoint, e não cinco: o `AiCoachGateway` do Android já trabalha com request
 * discriminado por tipo, e um endpoint só mantém uma fronteira, um guard, um lugar de quota, um
 * lugar de concorrência e um lugar de log. Cinco rotas seriam cinco cópias das mesmas proteções.
 *
 * A rota exige Bearer: sem conta não há chamada ao Gemini. O núcleo do Spark — treino, execução,
 * histórico, templates e gamificação — continua funcionando sem conta e sem este servidor.
 *
 * O `uid` vem **do token verificado**, pelo `@Principal()`. Nenhum campo do corpo, query ou header
 * influencia a identidade.
 */
@Controller('ai')
export class AiCoachController {
  constructor(private readonly service: AiCoachService) {}

  @UseGuards(BearerAuthGuard)
  @Post('coach')
  async coach(
    @Principal() principal: AuthenticatedPrincipal,
    @Req() request: Request,
    // `unknown` de propósito: quem decide a forma é o `zod` do serviço, em um lugar só.
    @Body() body: unknown,
  ): Promise<AiCoachHttpResponse> {
    const requestId = (request as RequestWithId).requestId ?? 'unknown';

    // O teto de corpo do Coach é dele, e é bem menor que o global — que desde a T16.4 precisa
    // caber um snapshot de backup inteiro. Um contexto do Coach com todos os tetos internos
    // preenchidos não chega perto de 128 KB, então um corpo acima disso é recusado aqui, antes de
    // virar trabalho e antes de qualquer coisa custar dinheiro.
    const rawBody = (request as RequestWithRawBody).rawBody;
    if (rawBody !== undefined && Buffer.byteLength(rawBody, 'utf8') > MAX_AI_REQUEST_BODY_BYTES) {
      throw new PayloadTooLargeException({
        code: AI_ERROR_CODES.INVALID_AI_REQUEST,
        message: 'corpo da requisição acima do teto do Coach',
      });
    }

    return this.service.handle(principal, requestId, body);
  }
}
