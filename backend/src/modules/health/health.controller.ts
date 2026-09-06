import { Controller, Get, HttpStatus, Res, VERSION_NEUTRAL } from '@nestjs/common';
import type { Response } from 'express';
import { HealthService, type LivenessResult } from './health.service';

/**
 * Health check é infraestrutura, não produto: fica fora de `/v1`, que é reservado para as APIs de
 * produto (T16.1+). Orquestrador e proxy não deveriam ter que acompanhar a versão da API para
 * saber se o processo está de pé.
 *
 * A resposta é deliberadamente pobre: booleanos por verificação, sem caminho de arquivo, variável
 * de ambiente, credencial ou mensagem de exceção.
 */
@Controller({ path: 'health', version: VERSION_NEUTRAL })
export class HealthController {
  constructor(private readonly health: HealthService) {}

  @Get('live')
  live(): LivenessResult {
    return this.health.liveness();
  }

  @Get('ready')
  ready(@Res() res: Response): void {
    const result = this.health.readiness();
    const status = result.status === 'ok' ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
    res.status(status).json(result);
  }
}
