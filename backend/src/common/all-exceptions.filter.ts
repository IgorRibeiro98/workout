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
      exception instanceof HttpException
        ? exception.getStatus()
        : (clientErrorStatusOf(exception) ?? HttpStatus.INTERNAL_SERVER_ERROR);

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

/**
 * O código declarado explicitamente no corpo da exceção, quando existe.
 *
 * A presença de `code` é o que distingue uma exceção **autorada por nós** de qualquer outra: as
 * exceções nativas do Nest respondem `{ statusCode, message, error }`, sem `code`. Por isso um
 * código declarado é confiável mesmo em 5xx — ele foi escrito no ponto do `throw`, não derivado
 * do erro. É o que permite ao Android distinguir `AUTH_UNAVAILABLE` (503, tente de novo) de uma
 * falha genérica do servidor sem que o servidor conte nada sobre si.
 */
function declaredCode(exception: unknown): string | null {
  if (!(exception instanceof HttpException)) {
    return null;
  }
  const body = exception.getResponse();
  if (typeof body === 'object' && body !== null && 'code' in body) {
    const code = (body as { code: unknown }).code;
    if (typeof code === 'string') {
      return code;
    }
  }
  return null;
}

/**
 * O status que um erro **não** nosso já carrega, quando ele é um erro do cliente.
 *
 * O caso real é o body-parser do Express: um corpo acima do teto vira um erro com
 * `status = 413`, e não uma `HttpException`. Sem isto, um payload grande demais responderia 500 —
 * dizendo ao cliente que o servidor quebrou quando quem errou foi a requisição, e registrando
 * como falha do servidor algo que é uma defesa funcionando.
 *
 * Só 4xx: um erro de biblioteca que se declara 5xx não ganha nada em ser repassado.
 */
function clientErrorStatusOf(exception: unknown): number | null {
  if (typeof exception !== 'object' || exception === null) {
    return null;
  }
  const candidate = ('status' in exception ? exception.status : undefined) ?? undefined;
  const status = typeof candidate === 'number' ? candidate : Number.NaN;
  return Number.isInteger(status) && status >= 400 && status < SERVER_ERROR_FLOOR ? status : null;
}

function codeFor(exception: unknown, status: number): string {
  const declared = declaredCode(exception);
  if (declared !== null) {
    return declared;
  }
  if (status >= SERVER_ERROR_FLOOR) {
    return INTERNAL_ERROR_CODE;
  }
  return HTTP_STATUS_NAMES[status] ?? 'ERROR';
}

function messageFor(exception: unknown, status: number, config: AppConfig): string {
  if (status >= SERVER_ERROR_FLOOR) {
    // Mensagem de 5xx continua opaca: ela pode carregar caminho de arquivo, SQL ou configuração.
    // Só um corpo com `code` declarado — escrito por nós — pode falar, e a mensagem vem do mesmo
    // corpo, não da exceção.
    return declaredCode(exception) !== null
      ? (declaredMessage(exception) ?? INTERNAL_ERROR_MESSAGE)
      : config.isProduction
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
  // Erro do cliente vindo de fora (body-parser): a resposta diz o que aconteceu, sem detalhe
  // interno e sem a mensagem crua da biblioteca.
  const name = HTTP_STATUS_NAMES[status];
  return name
    ? `Request rejected: ${name.toLowerCase().replace(/_/g, ' ')}`
    : INTERNAL_ERROR_MESSAGE;
}

/** A mensagem declarada junto com o `code`, quando o corpo da exceção traz uma. */
function declaredMessage(exception: unknown): string | null {
  if (!(exception instanceof HttpException)) {
    return null;
  }
  const body = exception.getResponse();
  if (typeof body === 'object' && body !== null && 'message' in body) {
    const message = (body as { message: unknown }).message;
    if (typeof message === 'string') {
      return message;
    }
  }
  return null;
}
