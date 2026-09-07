import {
  BadRequestException,
  HttpException,
  HttpStatus,
  PayloadTooLargeException,
} from '@nestjs/common';
import { SYNC_ERROR_CODES, type SyncErrorCode } from './sync.contract';

/**
 * Os erros do sync, no envelope da T16.0.
 *
 * **Nenhuma `reason` aqui repete conteúdo do usuário.** As razões descrevem a *forma* do defeito
 * ("cursor não é um inteiro"), nunca o valor: nome de treino, nota de série e medida corporal não
 * podem vazar numa mensagem de erro — que acaba em log do cliente, em tela e em suporte.
 */
function syncException(status: HttpStatus, code: SyncErrorCode, message: string): HttpException {
  const body = { code, message };
  switch (status) {
    case HttpStatus.PAYLOAD_TOO_LARGE:
      return new PayloadTooLargeException(body);
    case HttpStatus.TOO_MANY_REQUESTS:
      return new HttpException(body, HttpStatus.TOO_MANY_REQUESTS);
    default:
      return new BadRequestException(body);
  }
}

export const SyncErrors = {
  invalid: (reason: string) =>
    syncException(HttpStatus.BAD_REQUEST, SYNC_ERROR_CODES.INVALID_SYNC_REQUEST, reason),

  tooLarge: (reason: string) =>
    syncException(HttpStatus.PAYLOAD_TOO_LARGE, SYNC_ERROR_CODES.SYNC_PAYLOAD_TOO_LARGE, reason),

  /**
   * Cursor fora do que o servidor pode ter emitido.
   *
   * Recusar, e nunca recomeçar do zero em silêncio: um reset invisível faria o aparelho reaplicar
   * a conta inteira sem ninguém saber por quê.
   */
  invalidCursor: (reason: string) =>
    syncException(HttpStatus.BAD_REQUEST, SYNC_ERROR_CODES.INVALID_CURSOR, reason),

  rateLimited: () =>
    syncException(
      HttpStatus.TOO_MANY_REQUESTS,
      SYNC_ERROR_CODES.SYNC_RATE_LIMITED,
      'muitas requisições de sync para esta conta',
    ),
};
