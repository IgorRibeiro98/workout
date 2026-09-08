import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  NotFoundException,
} from '@nestjs/common';
import { CHALLENGE_ERROR_CODES, type ChallengeErrorCode } from './challenge.contract';

/**
 * Os erros dos desafios, no envelope da T16.0 (T17.3).
 *
 * Mesma forma de `social.errors.ts` e `friendship.errors.ts`: cada um declara um `code` que o
 * `AllExceptionsFilter` transforma em `{ error: { code, message, requestId } }` e que o Android
 * mapeia para erro tipado.
 *
 * ## Nenhuma mensagem revela mais do que a resposta já revela
 *
 * As razões descrevem a **forma** do problema, nunca o valor: nenhum nome de desafio, nenhum
 * `displayName`, nenhum `socialId`, nenhum uid e nenhuma pontuação entra em mensagem de erro.
 *
 * E as respostas que poderiam virar oráculo colapsam de propósito:
 *
 * ```text
 * desafio inexistente        ─┐
 * desafio de terceiros       ─┼─▶ CHALLENGE_NOT_FOUND          (§100/§182)
 * desafio que só me convidou ─┘   (o preview vem por outra rota)
 *
 * socialId inexistente       ─┐
 * perfil desativado          ─┼─▶ CHALLENGE_PARTICIPANT_NOT_AVAILABLE   (§32)
 * não somos amigos           ─┘
 * ```
 */
function challengeException(
  status: HttpStatus,
  code: ChallengeErrorCode,
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

export const ChallengeErrors = {
  /** Corpo, parâmetro ou valor fora do contrato — inclusive um campo de pontuação (§33). */
  invalid: (reason: string) =>
    challengeException(
      HttpStatus.BAD_REQUEST,
      CHALLENGE_ERROR_CODES.INVALID_CHALLENGE_REQUEST,
      reason,
    ),

  /**
   * Tipo desconhecido.
   *
   * Código próprio, e não `invalid`, porque a ação do cliente é outra: um APK antigo pedindo um
   * tipo que sumiu, ou um APK novo demais pedindo um que ainda não existe aqui. A tela precisa
   * dizer "este tipo de desafio não está disponível", não "requisição inválida".
   */
  invalidType: () =>
    challengeException(
      HttpStatus.BAD_REQUEST,
      CHALLENGE_ERROR_CODES.INVALID_CHALLENGE_TYPE,
      'tipo de desafio não suportado por este servidor',
    ),

  /** Meta fora dos limites, ou impossível para a duração escolhida (§21). */
  invalidTarget: (reason: string) =>
    challengeException(
      HttpStatus.BAD_REQUEST,
      CHALLENGE_ERROR_CODES.INVALID_CHALLENGE_TARGET,
      reason,
    ),

  /** Período fora de forma, invertido, curto/longo demais, ou que não começa no futuro (§15). */
  invalidPeriod: (reason: string) =>
    challengeException(
      HttpStatus.BAD_REQUEST,
      CHALLENGE_ERROR_CODES.INVALID_CHALLENGE_PERIOD,
      reason,
    ),

  /** O identificador não é um fuso IANA que este runtime conhece. */
  invalidTimeZone: () =>
    challengeException(
      HttpStatus.BAD_REQUEST,
      CHALLENGE_ERROR_CODES.INVALID_CHALLENGE_TIMEZONE,
      'fuso horário inválido',
    ),

  tooManyParticipants: () =>
    challengeException(
      HttpStatus.BAD_REQUEST,
      CHALLENGE_ERROR_CODES.TOO_MANY_PARTICIPANTS,
      'o desafio excede o número máximo de participantes',
    ),

  /**
   * Um convidado não é amigo ativo agora.
   *
   * Inexistente, desativado e "não somos amigos" respondem exatamente isto — e a resposta **não**
   * diz qual dos três, nem qual dos convidados. Dizer qual transformaria a criação de desafio num
   * verificador de existência de `socialId`, que é a enumeração que a T17.1 fechou.
   */
  participantNotAvailable: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.CHALLENGE_PARTICIPANT_NOT_AVAILABLE,
      'um dos amigos convidados não está disponível para participar',
    ),

  tooManyOpenChallenges: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.TOO_MANY_OPEN_CHALLENGES,
      'esta conta já tem desafios demais em andamento',
    ),

  /** O desafio não existe, ou quem perguntou não participa dele. A mesma resposta (§100/§182). */
  notFound: () =>
    challengeException(
      HttpStatus.NOT_FOUND,
      CHALLENGE_ERROR_CODES.CHALLENGE_NOT_FOUND,
      'desafio não encontrado',
    ),

  invitationNotFound: () =>
    challengeException(
      HttpStatus.NOT_FOUND,
      CHALLENGE_ERROR_CODES.CHALLENGE_INVITATION_NOT_FOUND,
      'convite de desafio não encontrado',
    ),

  /**
   * O convite existe, é seu, e já foi respondido.
   *
   * A corrida real: o criador cancela o desafio enquanto o convidado aceita. Uma das escritas
   * vence atomicamente e a outra recebe isto — resposta correta, e não falha a contornar. A tela
   * relê e mostra o estado que venceu.
   */
  invitationNotPending: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.CHALLENGE_INVITATION_NOT_PENDING,
      'este convite já foi respondido',
    ),

  /**
   * Aceitar depois do início (§54/§56).
   *
   * Bloqueante por desenho: quem entra no meio teria de decidir se os treinos que já fez contam —
   * e qualquer das duas respostas é injusta com alguém. Começar sempre no futuro (§15) é o que
   * torna esta situação rara, e este erro é o que a torna impossível.
   */
  alreadyStarted: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.CHALLENGE_ALREADY_STARTED,
      'este desafio já começou',
    ),

  cancelled: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.CHALLENGE_CANCELLED,
      'este desafio foi cancelado',
    ),

  notCreator: () =>
    challengeException(
      HttpStatus.FORBIDDEN,
      CHALLENGE_ERROR_CODES.NOT_CHALLENGE_CREATOR,
      'apenas quem criou o desafio pode cancelá-lo',
    ),

  /**
   * O criador não sai.
   *
   * Sair deixaria um desafio sem dono, com participantes competindo por regras que ninguém mais
   * pode encerrar. Cancelar é a saída dele, e ela é honesta com os outros: o desafio acaba para
   * todos, sem resultado (§69).
   */
  cannotLeaveAsCreator: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.CANNOT_LEAVE_AS_CREATOR,
      'quem criou o desafio precisa cancelá-lo em vez de sair',
    ),

  /** Mesmo `clientRequestId`, conteúdo diferente (§190). Nunca uma segunda criação silenciosa. */
  idempotencyConflict: () =>
    challengeException(
      HttpStatus.CONFLICT,
      CHALLENGE_ERROR_CODES.CHALLENGE_IDEMPOTENCY_CONFLICT,
      'este identificador de requisição já foi usado para um desafio diferente',
    ),

  /** Teto próprio da criação ou das respostas. Esperar resolve; não há nada errado com o app. */
  rateLimited: (reason: string) =>
    challengeException(
      HttpStatus.TOO_MANY_REQUESTS,
      CHALLENGE_ERROR_CODES.CHALLENGE_RATE_LIMITED,
      reason,
    ),
};
