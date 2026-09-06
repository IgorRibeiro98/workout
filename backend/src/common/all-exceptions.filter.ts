import { ArgumentsHost, Catch, ExceptionFilter, HttpException, HttpStatus } from '@nestjs/common';
import type { Request, Response } from 'express';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { Inject } from '@nestjs/common';
import { errorEnvelope, INTERNAL_ERROR_CODE, INTERNAL_ERROR_MESSAGE } from './error-envelope';
import { SparkLogger } from './logger';
import type { RequestWithId } from './request-id.middleware';

/**
 * Toda resposta de erro sai por aqui, no envelope único.
 *
 * Stack trace nunca vai para o cliente — em nenhum ambiente. Em produção, a mensagem de um 5xx
 * também é genérica: a mensagem real do erro pode carregar caminho de arquivo, fragmento de SQL ou
 * valor de configuração, e o cliente não precisa disso. O `requestId` é a ponte para o log.
 */
/** Menor status considerado erro do servidor. Numérico para comparar com `number` sem enum-cast. */
const SERVER_ERROR_FLOOR = 500;

/** Nome legível do status, usado como `code` quando o erro não traz um código próprio. */
const HTTP_STATUS_NAMES: Record<number, string> = Object.fromEntries(
  Object.entries(HttpStatus)
    .filter(([, value]) => typeof value === 'number')
    .map(([name, value]) => [value as number, name]),
);

@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
  ) {}

  catch(exception: unknown, host: ArgumentsHost): void {
    const context = host.switchToHttp();
    const request = context.getRequest<Request>();
    const response = context.getResponse<Response>();
    const requestId = (request as RequestWithId).requestId ?? 'unknown';

    const status =
      exception instanceof HttpException ? exception.getStatus() : HttpStatus.INTERNAL_SERVER_ERROR;

    const isServerError = status >= SERVER_ERROR_FLOOR;

    if (isServerError) {
      // A mensagem do erro fica no log do servidor, com o requestId, e não na resposta.
      this.logger.error('http.error', {
        requestId,
        status,
        errorName: exception instanceof Error ? exception.name : 'UnknownError',
        errorMessage: exception instanceof Error ? exception.message : undefined,
      });
    }

    response
      .status(status)
      .json(
        errorEnvelope(
          codeFor(exception, status),
          messageFor(exception, status, this.config),
          requestId,
        ),
      );
  }
}

function codeFor(exception: unknown, status: number): string {
  if (status >= SERVER_ERROR_FLOOR) {
    return INTERNAL_ERROR_CODE;
  }
  if (exception instanceof HttpException) {
    const body = exception.getResponse();
    if (typeof body === 'object' && body !== null && 'code' in body) {
      const code = (body as { code: unknown }).code;
      if (typeof code === 'string') {
        return code;
      }
    }
  }
  return HTTP_STATUS_NAMES[status] ?? 'ERROR';
}

function messageFor(exception: unknown, status: number, config: AppConfig): string {
  if (status >= SERVER_ERROR_FLOOR) {
    return config.isProduction
      ? INTERNAL_ERROR_MESSAGE
      : exception instanceof Error
        ? exception.message
        : INTERNAL_ERROR_MESSAGE;
  }
  if (exception instanceof HttpException) {
    const body = exception.getResponse();
    if (typeof body === 'string') {
      return body;
    }
    if (typeof body === 'object' && body !== null && 'message' in body) {
      const message = (body as { message: unknown }).message;
      if (typeof message === 'string') {
        return message;
      }
      if (Array.isArray(message)) {
        return message.join('; ');
      }
    }
    return exception.message;
  }
  return INTERNAL_ERROR_MESSAGE;
}
