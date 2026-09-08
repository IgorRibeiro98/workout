import { BadRequestException, HttpException, HttpStatus, NotFoundException } from '@nestjs/common';
import { SOCIAL_PROFILE_ERROR_CODES, type SocialProfileErrorCode } from './social-profile.contract';

/**
 * Os erros do perfil social enriquecido (T17.2), no envelope da T16.0.
 *
 * Mesma forma de `social.errors.ts` e `friendship.errors.ts`: um `code` no corpo, que o
 * `AllExceptionsFilter` transforma em `{ error: { code, message, requestId } }` e que o Android
 * mapeia para erro tipado.
 *
 * ## As mensagens descrevem a forma do defeito, nunca o valor
 *
 * Nenhuma razão daqui repete `socialId`, `displayName`, `friendCode`, e-mail, uid ou qualquer
 * número de progresso. Uma mensagem de erro viaja para tela, para log de cliente e para relato de
 * suporte, e o que ela precisa dizer é o que fazer em seguida.
 */
function profileException(
  status: HttpStatus,
  code: SocialProfileErrorCode,
  message: string,
): HttpException {
  if (status === HttpStatus.NOT_FOUND) {
    return new NotFoundException({ code, message });
  }
  return new BadRequestException({ code, message });
}

export const SocialProfileErrors = {
  /**
   * O perfil enriquecido não é alcançável — e as quatro razões respondem a mesma coisa.
   *
   * ```text
   * socialId inexistente     ┐
   * perfil alvo DISABLED     ├──▶  404 FRIEND_PROFILE_NOT_FOUND
   * não são amigos           │
   * pedido apenas PENDING    ┘
   * ```
   *
   * A colapsagem é o ponto (§120). Se "não somos amigos" fosse `403` e "não existe" fosse `404`,
   * uma conta C que conhecesse o `socialId` de B poderia, sem nenhuma permissão, confirmar que B
   * existe — e, comparando com o `404` de um id inventado, aprender que B desativou o social. O
   * mesmo vale para o pedido pendente: quem só pediu não pode descobrir, pela resposta, que o
   * outro lado ainda não respondeu.
   */
  friendProfileNotFound: () =>
    profileException(
      HttpStatus.NOT_FOUND,
      SOCIAL_PROFILE_ERROR_CODES.FRIEND_PROFILE_NOT_FOUND,
      'perfil de amigo não encontrado',
    ),

  /** Corpo de `PATCH /v1/social/me/progress-sharing` fora do contrato. */
  invalidProgressSettings: (reason: string) =>
    profileException(
      HttpStatus.BAD_REQUEST,
      SOCIAL_PROFILE_ERROR_CODES.INVALID_PROGRESS_SETTINGS,
      reason,
    ),
};
