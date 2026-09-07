import {
  BadRequestException,
  HttpException,
  HttpStatus,
  NotFoundException,
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
    case HttpStatus.SERVICE_UNAVAILABLE:
      return new HttpException(body, HttpStatus.SERVICE_UNAVAILABLE);
    case HttpStatus.PAYLOAD_TOO_LARGE:
      return new PayloadTooLargeException(body);
    case HttpStatus.TOO_MANY_REQUESTS:
      return new HttpException(body, HttpStatus.TOO_MANY_REQUESTS);
    case HttpStatus.NOT_FOUND:
      return new NotFoundException(body);
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

  /**
   * O cursor é anterior ao que o servidor ainda pode entregar.
   *
   * Recusa explícita, e nunca um `cursor = 0` silencioso: entre os dois pontos houve mudanças —
   * possivelmente exclusões — que o aparelho nunca vai receber, e reprocessar do começo sem saber
   * disso ressuscitaria dado apagado. O aparelho precisa de rebaseline, e quem decide isso é o
   * usuário.
   */
  cursorExpired: () =>
    syncException(
      HttpStatus.BAD_REQUEST,
      SYNC_ERROR_CODES.CURSOR_EXPIRED,
      'a sincronização deste aparelho precisa ser reconstruída',
    ),

  /**
   * O agregado não existe **para a conta autenticada** (T16.7.1).
   *
   * A mesma resposta para "nunca existiu" e para "existe, mas é de outra conta". Distinguir os
   * dois transformaria a rota num oráculo de existência do dado alheio: bastaria variar o
   * `syncId` e ler o status.
   */
  entityNotFound: () =>
    syncException(
      HttpStatus.NOT_FOUND,
      SYNC_ERROR_CODES.SYNC_ENTITY_NOT_FOUND,
      'agregado não encontrado nesta conta',
    ),

  rateLimited: () =>
    syncException(
      HttpStatus.TOO_MANY_REQUESTS,
      SYNC_ERROR_CODES.SYNC_RATE_LIMITED,
      'muitas requisições de sync para esta conta',
    ),

  /**
   * A escrita remota está pausada (`SYNC_WRITE_ENABLED=false`).
   *
   * 503 de propósito: nada há de errado com a mutação, e o aparelho precisa mantê-la pendente
   * para reenviar depois — que é exatamente o que ele faz com 5xx.
   */
  writeDisabled: () =>
    syncException(
      HttpStatus.SERVICE_UNAVAILABLE,
      SYNC_ERROR_CODES.SYNC_WRITE_DISABLED,
      'a sincronização está temporariamente pausada neste servidor',
    ),
};
