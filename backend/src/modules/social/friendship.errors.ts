import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  NotFoundException,
} from '@nestjs/common';
import { FRIENDSHIP_ERROR_CODES, type FriendshipErrorCode } from './friendship.contract';

/**
 * Os erros do grafo social, no envelope da T16.0 (T17.1).
 *
 * Mesma forma de `social.errors.ts`: cada um declara um `code` que o `AllExceptionsFilter`
 * transforma em `{ error: { code, message, requestId } }` e que o Android mapeia para erro tipado.
 *
 * ## Nenhuma mensagem revela mais do que a resposta já revela
 *
 * As razões descrevem a **forma** do problema, nunca o valor: nenhum `friendCode`, nenhum
 * `displayName`, nenhum `socialId` e nenhum uid entra em mensagem de erro. E as respostas que
 * poderiam virar oráculo colapsam de propósito — "esse código não existe", "esse perfil está
 * desativado" e "esse código está malformado" são **a mesma** resposta (§81).
 */
function friendshipException(
  status: HttpStatus,
  code: FriendshipErrorCode,
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
    case HttpStatus.TOO_MANY_REQUESTS:
      return new HttpException(body, HttpStatus.TOO_MANY_REQUESTS);
    default:
      return new BadRequestException(body);
  }
}

export const FriendshipErrors = {
  /** Corpo, parâmetro ou valor fora do contrato. */
  invalid: (reason: string) =>
    friendshipException(
      HttpStatus.BAD_REQUEST,
      FRIENDSHIP_ERROR_CODES.INVALID_FRIEND_REQUEST,
      reason,
    ),

  /**
   * Quem chamou tem perfil social desativado.
   *
   * Enquanto ele estiver assim, nenhuma rota do grafo responde — nem leitura. As relações **não**
   * são apagadas: elas ficam suspensas e voltam inteiras ao reativar, que é o oposto de tratar
   * "desativar" como "desfazer amizades".
   */
  profileDisabled: () =>
    friendshipException(
      HttpStatus.CONFLICT,
      FRIENDSHIP_ERROR_CODES.SOCIAL_PROFILE_DISABLED,
      'os recursos sociais desta conta estão desativados',
    ),

  /**
   * O perfil alvo não é alcançável.
   *
   * Inexistente e desativado dão exatamente esta resposta. A distinção seria a informação que
   * desativar existe para esconder.
   */
  profileNotFound: () =>
    friendshipException(
      HttpStatus.NOT_FOUND,
      FRIENDSHIP_ERROR_CODES.SOCIAL_PROFILE_NOT_FOUND,
      'perfil social não encontrado',
    ),

  selfRequest: () =>
    friendshipException(
      HttpStatus.BAD_REQUEST,
      FRIENDSHIP_ERROR_CODES.SELF_FRIEND_REQUEST,
      'não é possível enviar um pedido de amizade para si mesmo',
    ),

  /** O destinatário desligou os pedidos. Vale mesmo com um app desatualizado: a autoridade é aqui. */
  requestsDisabled: () =>
    friendshipException(
      HttpStatus.FORBIDDEN,
      FRIENDSHIP_ERROR_CODES.FRIEND_REQUESTS_DISABLED,
      'este perfil não está aceitando pedidos de amizade',
    ),

  alreadyFriends: () =>
    friendshipException(
      HttpStatus.CONFLICT,
      FRIENDSHIP_ERROR_CODES.ALREADY_FRIENDS,
      'vocês já são amigos',
    ),

  /**
   * O pedido não existe — **ou** existe e quem perguntou não participa dele.
   *
   * As duas situações respondem a mesma coisa de propósito: uma conta C que tentasse adivinhar
   * `requestId` não pode aprender, pela resposta, que acertou um pedido entre A e B.
   */
  requestNotFound: () =>
    friendshipException(
      HttpStatus.NOT_FOUND,
      FRIENDSHIP_ERROR_CODES.FRIEND_REQUEST_NOT_FOUND,
      'pedido de amizade não encontrado',
    ),

  /**
   * O pedido existe, é seu, e já é terminal.
   *
   * O caso real é a corrida de §32: A cancela enquanto B aceita. Uma das duas escritas vence
   * atomicamente e a outra recebe isto — o que é uma resposta correta, e não uma falha a
   * contornar. A tela relê e mostra o estado que venceu.
   */
  requestNotPending: () =>
    friendshipException(
      HttpStatus.CONFLICT,
      FRIENDSHIP_ERROR_CODES.FRIEND_REQUEST_NOT_PENDING,
      'este pedido de amizade já foi resolvido',
    ),

  /** Só o destinatário aceita ou recusa. Quem enviou não pode aceitar o próprio pedido. */
  notRecipient: () =>
    friendshipException(
      HttpStatus.FORBIDDEN,
      FRIENDSHIP_ERROR_CODES.NOT_REQUEST_RECIPIENT,
      'apenas quem recebeu o pedido pode respondê-lo',
    ),

  /** Só quem enviou cancela. */
  notSender: () =>
    friendshipException(
      HttpStatus.FORBIDDEN,
      FRIENDSHIP_ERROR_CODES.NOT_REQUEST_SENDER,
      'apenas quem enviou o pedido pode cancelá-lo',
    ),

  friendshipNotFound: () =>
    friendshipException(
      HttpStatus.NOT_FOUND,
      FRIENDSHIP_ERROR_CODES.FRIENDSHIP_NOT_FOUND,
      'vocês não são amigos',
    ),

  /**
   * Teto próprio do lookup ou do envio, por conta.
   *
   * Código separado do `API_RATE_LIMITED` geral porque a ação que o cliente deve tomar é outra:
   * aqui não há nada errado com o app, e esperar um minuto resolve.
   */
  rateLimited: (reason: string) =>
    friendshipException(
      HttpStatus.TOO_MANY_REQUESTS,
      FRIENDSHIP_ERROR_CODES.SOCIAL_RATE_LIMITED,
      reason,
    ),
};
