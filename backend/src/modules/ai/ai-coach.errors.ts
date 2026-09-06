import {
  BadRequestException,
  ConflictException,
  GatewayTimeoutException,
  HttpException,
  HttpStatus,
  ServiceUnavailableException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { AI_ERROR_CODES, type AiErrorCode } from './ai-coach.contract';

/**
 * Os erros do Coach, no envelope da T16.0.
 *
 * Cada um declara um `code` no corpo — é isso que o `AllExceptionsFilter` usa para responder
 * `{ error: { code, message, requestId } }` sem contar nada sobre o servidor. Nenhuma mensagem
 * daqui carrega stack, endpoint interno, internals do SDK, prompt ou trecho de resposta (§50).
 */
function aiException(status: HttpStatus, code: AiErrorCode, message: string): HttpException {
  const body = { code, message };
  switch (status) {
    case HttpStatus.BAD_REQUEST:
      return new BadRequestException(body);
    case HttpStatus.CONFLICT:
      return new ConflictException(body);
    case HttpStatus.UNPROCESSABLE_ENTITY:
      return new UnprocessableEntityException(body);
    case HttpStatus.TOO_MANY_REQUESTS:
      return new HttpException(body, HttpStatus.TOO_MANY_REQUESTS);
    case HttpStatus.GATEWAY_TIMEOUT:
      return new GatewayTimeoutException(body);
    default:
      return new ServiceUnavailableException(body);
  }
}

export const AiCoachErrors = {
  /** O corpo não bate com o contrato. A razão é curta e não repete o payload recebido. */
  invalidRequest: (reason: string) =>
    aiException(HttpStatus.BAD_REQUEST, AI_ERROR_CODES.INVALID_AI_REQUEST, reason),

  /** Versão de contrato que este servidor não sabe interpretar — recusa explícita, nunca palpite. */
  unsupportedSchemaVersion: (version: number) =>
    aiException(
      HttpStatus.BAD_REQUEST,
      AI_ERROR_CODES.UNSUPPORTED_SCHEMA_VERSION,
      `schemaVersion não suportada: ${version}`,
    ),

  /** Já existe uma chamada equivalente desta conta em andamento. */
  requestConflict: () =>
    aiException(
      HttpStatus.CONFLICT,
      AI_ERROR_CODES.AI_REQUEST_CONFLICT,
      'já existe uma chamada do Coach em andamento para esta conta',
    ),

  /** O modelo respondeu, mas a resposta não passou na validação. Nada do conteúdo vaza. */
  invalidResponse: () =>
    aiException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      AI_ERROR_CODES.INVALID_AI_RESPONSE,
      'a resposta do modelo não passou na validação e foi descartada',
    ),

  userQuotaExceeded: () =>
    aiException(
      HttpStatus.TOO_MANY_REQUESTS,
      AI_ERROR_CODES.AI_USER_QUOTA_EXCEEDED,
      'limite diário do Coach para esta conta',
    ),

  globalQuotaExceeded: () =>
    aiException(
      HttpStatus.TOO_MANY_REQUESTS,
      AI_ERROR_CODES.AI_GLOBAL_QUOTA_EXCEEDED,
      'o Coach atingiu o limite diário do servidor',
    ),

  providerUnavailable: () =>
    aiException(
      HttpStatus.SERVICE_UNAVAILABLE,
      AI_ERROR_CODES.AI_PROVIDER_UNAVAILABLE,
      'o Coach está temporariamente indisponível',
    ),

  providerTimeout: () =>
    aiException(
      HttpStatus.GATEWAY_TIMEOUT,
      AI_ERROR_CODES.AI_PROVIDER_TIMEOUT,
      'o Coach demorou demais para responder',
    ),
};
