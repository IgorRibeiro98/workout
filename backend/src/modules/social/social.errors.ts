import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  NotFoundException,
} from '@nestjs/common';
import { SOCIAL_ERROR_CODES, type SocialErrorCode } from './social.contract';

/**
 * Os erros do domínio social, no envelope da T16.0.
 *
 * Cada um declara um `code` no corpo — é o que o `AllExceptionsFilter` transforma em
 * `{ error: { code, message, requestId } }` e o que o Android mapeia para erro tipado.
 *
 * **Nenhuma `reason` aqui repete conteúdo do usuário.** As razões descrevem a *forma* do defeito
 * ("o nome social é curto demais"), nunca o valor: o nome social vai para tela, para log de
 * cliente e para relato de suporte, e o código de amigo é dado compartilhável que não precisa
 * circular em mensagem de erro.
 */
function socialException(
  status: HttpStatus,
  code: SocialErrorCode,
  message: string,
): HttpException {
  const body = { code, message };
  switch (status) {
    case HttpStatus.NOT_FOUND:
      return new NotFoundException(body);
    case HttpStatus.CONFLICT:
      return new ConflictException(body);
    case HttpStatus.FORBIDDEN:
      return new ForbiddenException(body);
    case HttpStatus.SERVICE_UNAVAILABLE:
      return new HttpException(body, HttpStatus.SERVICE_UNAVAILABLE);
    default:
      return new BadRequestException(body);
  }
}

export const SocialErrors = {
  /** Corpo fora do contrato — inclusive um campo server-side (`ownerUid`, `socialId`, ...). */
  invalid: (reason: string) =>
    socialException(HttpStatus.BAD_REQUEST, SOCIAL_ERROR_CODES.INVALID_SOCIAL_REQUEST, reason),

  /**
   * Nome social fora das regras de forma.
   *
   * Código próprio, separado de [invalid], porque a UI precisa apontar o campo em vez de dizer
   * "requisição inválida" numa tela onde só existe um campo editável.
   */
  invalidDisplayName: (reason: string) =>
    socialException(HttpStatus.BAD_REQUEST, SOCIAL_ERROR_CODES.INVALID_DISPLAY_NAME, reason),

  /**
   * A conta não tem perfil social.
   *
   * `404` aqui é um erro de verdade: alguém tentou alterar, desativar ou reativar algo que não
   * existe. **Não** é a resposta de `GET /v1/social/me`, que responde `{ enabled: false }` —
   * "ainda não ativei" é um estado normal do produto, e tratá-lo como falha faria o cliente
   * confundir o caminho feliz com erro.
   */
  notEnabled: () =>
    socialException(
      HttpStatus.NOT_FOUND,
      SOCIAL_ERROR_CODES.SOCIAL_NOT_ENABLED,
      'esta conta não tem perfil social',
    ),

  /**
   * `enable` sobre um perfil que já está ativo.
   *
   * Conflito, e não sucesso silencioso: o cliente pediu uma transição que não aconteceu, e ele
   * precisa saber que o estado veio de outro lugar (outro aparelho, provavelmente) em vez de
   * acreditar que foi ele quem reativou. O Android trata isso recarregando o perfil, não como
   * falha de tela.
   */
  alreadyEnabled: () =>
    socialException(
      HttpStatus.CONFLICT,
      SOCIAL_ERROR_CODES.SOCIAL_ALREADY_ENABLED,
      'os recursos sociais já estão ativos nesta conta',
    ),

  /** `disable` sobre um perfil já desativado. Simétrico a [alreadyEnabled], pelo mesmo motivo. */
  alreadyDisabled: () =>
    socialException(
      HttpStatus.CONFLICT,
      SOCIAL_ERROR_CODES.SOCIAL_ALREADY_DISABLED,
      'os recursos sociais já estão desativados nesta conta',
    ),

  /**
   * O servidor não conseguiu concluir agora.
   *
   * O caso real é a geração de `friendCode` esgotar as tentativas contra a `UNIQUE` do banco —
   * estatisticamente impossível, e ainda assim tratado. `503` porque nada há de errado com o
   * pedido: tentar de novo é a ação certa, e é o que o Android faz oferecer.
   */
  unavailable: (reason: string) =>
    socialException(HttpStatus.SERVICE_UNAVAILABLE, SOCIAL_ERROR_CODES.SOCIAL_UNAVAILABLE, reason),

  /** Usuário não optou por participar do ranking (T17.4). */
  rankingNotEnabled: () =>
    socialException(
      HttpStatus.FORBIDDEN,
      SOCIAL_ERROR_CODES.RANKING_NOT_ENABLED,
      'usuário não optou por participar do ranking',
    ),

  /** Fuso horário da atividade inválido ou ausente quando necessário (T17.4). */
  invalidActivityTimeZone: (reason: string) =>
    socialException(
      HttpStatus.BAD_REQUEST,
      SOCIAL_ERROR_CODES.INVALID_ACTIVITY_TIMEZONE,
      reason,
    ),

  /** Atividade não disponível para exibição (T17.4). */
  activityNotAvailable: (reason: string) =>
    socialException(
      HttpStatus.NOT_FOUND,
      SOCIAL_ERROR_CODES.ACTIVITY_NOT_AVAILABLE,
      reason,
    ),
};
