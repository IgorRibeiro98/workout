import { Injectable, NestMiddleware } from '@nestjs/common';
import type { NextFunction, Request, Response } from 'express';
import { SparkLogger } from './logger';
import type { RequestWithId } from './request-id.middleware';

/**
 * Log de acesso: apenas `requestId`, método, rota, status e duração.
 *
 * O que **não** é registrado, por decisão e não por omissão: header `Authorization`, corpo da
 * requisição ou da resposta, query string, e qualquer dado de domínio. Quando a T16.1 trouxer o
 * Firebase ID Token, ele passa por este caminho sem nunca ser materializado em log.
 */
@Injectable()
export class HttpLoggerMiddleware implements NestMiddleware {
  constructor(private readonly logger: SparkLogger) {}

  use(req: Request, res: Response, next: NextFunction): void {
    const startedAt = process.hrtime.bigint();

    res.on('finish', () => {
      const durationMs = Number(process.hrtime.bigint() - startedAt) / 1_000_000;
      this.logger.info('http.request', {
        requestId: (req as RequestWithId).requestId,
        method: req.method,
        route: req.route?.path ?? req.path,
        status: res.statusCode,
        durationMs: Math.round(durationMs * 100) / 100,
      });
    });

    next();
  }
}
