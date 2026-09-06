import { Body, Controller, Post, Req, UseGuards } from '@nestjs/common';
import type { Request } from 'express';
import type { RequestWithId } from '../../common/request-id.middleware';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { BearerAuthGuard } from '../auth/bearer-auth.guard';
import { Principal } from '../auth/principal.decorator';
import { AiCoachService } from './ai-coach.service';
import type { AiCoachHttpResponse } from './ai-coach.contract';

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
    return this.service.handle(principal, requestId, body);
  }
}
