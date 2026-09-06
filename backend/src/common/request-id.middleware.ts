import { randomUUID } from 'node:crypto';
import { Injectable, NestMiddleware } from '@nestjs/common';
import type { NextFunction, Request, Response } from 'express';

export const REQUEST_ID_HEADER = 'x-request-id';

/**
 * Um request ID aceito do cliente só pode ser opaco e curto: ele aparece em log e em resposta de
 * erro, então tratá-lo como texto livre transformaria um header em vetor de injeção de log.
 * Qualquer coisa fora deste formato é descartada e substituída por um UUID do servidor.
 */
const SAFE_REQUEST_ID = /^[A-Za-z0-9._-]{8,128}$/;

export interface RequestWithId extends Request {
  requestId: string;
}

export function resolveRequestId(candidate: unknown): string {
  return typeof candidate === 'string' && SAFE_REQUEST_ID.test(candidate)
    ? candidate
    : randomUUID();
}

@Injectable()
export class RequestIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction): void {
    const requestId = resolveRequestId(req.headers[REQUEST_ID_HEADER]);
    (req as RequestWithId).requestId = requestId;
    res.setHeader(REQUEST_ID_HEADER, requestId);
    next();
  }
}
