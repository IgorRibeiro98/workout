import { HttpStatus, Inject, Injectable, NestMiddleware } from '@nestjs/common';
import type { NextFunction, Request, Response } from 'express';
import { isHealthRoute } from './health-route';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { errorEnvelope } from './error-envelope';
import type { RequestWithId } from './request-id.middleware';

export const MAINTENANCE_ERROR_CODE = 'SERVICE_UNAVAILABLE';

/**
 * Manutenção programada (T16.8 §123).
 *
 * Enquanto `MAINTENANCE_MODE=true`, toda rota de produto responde `503` no envelope de erro de
 * sempre. Não há página, banner nem tela: o consumidor desta API é um app Android que já sabe
 * tratar 503 como "indisponível, tente depois" em todos os caminhos online — Coach, backup,
 * restore e sync.
 *
 * **`/health/*` continua respondendo normalmente**, e isso é o ponto. Quem faz o healthcheck do
 * container e do proxy precisa distinguir "em manutenção" de "morto": um readiness falso faria o
 * Docker reiniciar o container no meio da manutenção, que é exatamente o contrário do que a
 * manutenção quer.
 *
 * O que este interruptor **não** faz: parar o core do Spark. Sem servidor, o usuário continua
 * abrindo o app, executando treino, registrando série e consultando histórico — a manutenção
 * pausa a nuvem, nunca o treino.
 */
@Injectable()
export class MaintenanceMiddleware implements NestMiddleware {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  use(req: Request, res: Response, next: NextFunction): void {
    if (!this.config.maintenanceMode || isHealthRoute(req)) {
      next();
      return;
    }

    const requestId = (req as RequestWithId).requestId ?? 'unknown';
    res
      .status(HttpStatus.SERVICE_UNAVAILABLE)
      .json(
        errorEnvelope(
          MAINTENANCE_ERROR_CODE,
          'servidor em manutenção; tente novamente em instantes',
          requestId,
        ),
      );
  }
}
