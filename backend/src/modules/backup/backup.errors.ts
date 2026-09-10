import {
  BadRequestException,
  ConflictException,
  GoneException,
  HttpException,
  HttpStatus,
  NotFoundException,
  PayloadTooLargeException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { BACKUP_ERROR_CODES, type BackupErrorCode } from './backup.contract';

/**
 * Os erros do backup, no envelope da T16.0.
 *
 * Cada um declara um `code` no corpo — é o que o `AllExceptionsFilter` usa para responder
 * `{ error: { code, message, requestId } }`.
 *
 * **Nenhuma `reason` aqui repete conteúdo do snapshot.** As razões descrevem a *forma* do defeito
 * ("syncId do item não corresponde ao do payload"), nunca o valor: um nome de treino, uma nota de
 * série ou uma medida corporal não podem vazar numa mensagem de erro — que acaba em log do cliente,
 * em tela e em relato de suporte.
 */
function backupException(
  status: HttpStatus,
  code: BackupErrorCode,
  message: string,
): HttpException {
  const body = { code, message };
  switch (status) {
    case HttpStatus.TOO_MANY_REQUESTS:
      return new HttpException(body, HttpStatus.TOO_MANY_REQUESTS);
    case HttpStatus.CONFLICT:
      return new ConflictException(body);
    case HttpStatus.PAYLOAD_TOO_LARGE:
      return new PayloadTooLargeException(body);
    case HttpStatus.NOT_FOUND:
      return new NotFoundException(body);
    case HttpStatus.GONE:
      return new GoneException(body);
    case HttpStatus.SERVICE_UNAVAILABLE:
      return new ServiceUnavailableException(body);
    default:
      return new BadRequestException(body);
  }
}

export const BackupErrors = {
  /** Envelope, item, identidade ou relação fora do contrato. */
  invalid: (reason: string) =>
    backupException(HttpStatus.BAD_REQUEST, BACKUP_ERROR_CODES.INVALID_BACKUP, reason),

  unsupportedBackupSchemaVersion: (version: number) =>
    backupException(
      HttpStatus.BAD_REQUEST,
      BACKUP_ERROR_CODES.UNSUPPORTED_BACKUP_SCHEMA_VERSION,
      `backupSchemaVersion não suportada: ${version}`,
    ),

  unsupportedEntitySchemaVersion: (entityType: string, version: number) =>
    backupException(
      HttpStatus.BAD_REQUEST,
      BACKUP_ERROR_CODES.UNSUPPORTED_ENTITY_SCHEMA_VERSION,
      `entitySchemaVersion não suportada para ${entityType}: ${version}`,
    ),

  /**
   * Mesma tentativa lógica, conteúdo diferente.
   *
   * Não é "o servidor ficou confuso": é o cliente dizendo duas coisas incompatíveis sobre a mesma
   * tentativa. Aceitar a segunda apagaria silenciosamente o que a primeira significava.
   */
  idempotencyConflict: () =>
    backupException(
      HttpStatus.CONFLICT,
      BACKUP_ERROR_CODES.BACKUP_IDEMPOTENCY_CONFLICT,
      'já existe um backup com este clientBackupId e conteúdo diferente',
    ),

  tooLarge: (reason: string) =>
    backupException(HttpStatus.PAYLOAD_TOO_LARGE, BACKUP_ERROR_CODES.BACKUP_TOO_LARGE, reason),

  notFound: () =>
    backupException(
      HttpStatus.NOT_FOUND,
      BACKUP_ERROR_CODES.BACKUP_NOT_FOUND,
      'nenhum backup para esta conta',
    ),

  /**
   * O snapshot existe e o documento original dele não.
   *
   * Erro próprio, e não `NOT_FOUND`: o backup **está** na lista da conta, e dizer "não existe"
   * faria o app parecer quebrado. O cliente mostra que aquela cópia não pode ser restaurada e
   * convida a criar uma nova.
   */
  contentUnavailable: () =>
    backupException(
      HttpStatus.GONE,
      BACKUP_ERROR_CODES.BACKUP_CONTENT_UNAVAILABLE,
      'o conteúdo deste backup não está disponível para restauração',
    ),

  /**
   * O armazenamento de objetos não respondeu (T18.1 §40). Recuperável: reenviar é idempotente.
   */
  storageUnavailable: () =>
    backupException(
      HttpStatus.SERVICE_UNAVAILABLE,
      BACKUP_ERROR_CODES.BACKUP_STORAGE_UNAVAILABLE,
      'o armazenamento de backups está indisponível no momento',
    ),

  /** Requisições demais desta conta em uma janela curta. Reenviar depois é seguro e idempotente. */
  rateLimited: () =>
    backupException(
      HttpStatus.TOO_MANY_REQUESTS,
      BACKUP_ERROR_CODES.BACKUP_RATE_LIMITED,
      'muitas requisições de backup para esta conta',
    ),
};
