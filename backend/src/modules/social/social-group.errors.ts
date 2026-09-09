import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { SOCIAL_GROUP_ERRORS, type SocialGroupErrorCode } from './social-group.contract';

/**
 * Os erros dos Squads (T17.11), no envelope da T16.0.
 *
 * Cada um declara um `code` no corpo — é o que o `AllExceptionsFilter` transforma em
 * `{ error: { code, message, requestId } }` e o que o Android mapeia para erro tipado.
 *
 * **Nenhuma `reason` aqui repete conteúdo do usuário** (§124): nunca o nome do Squad, nunca o
 * `displayName` de ninguém, nunca a legenda de um check-in. A mensagem descreve a *forma* do
 * defeito, e nunca o valor — um nome de grupo numa mensagem de erro acabaria em tela, em relatório
 * de suporte e em log de cliente.
 */
function groupException(
  status: HttpStatus,
  code: SocialGroupErrorCode,
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
    case HttpStatus.UNPROCESSABLE_ENTITY:
      return new UnprocessableEntityException(body);
    case HttpStatus.TOO_MANY_REQUESTS:
      return new HttpException(body, HttpStatus.TOO_MANY_REQUESTS);
    case HttpStatus.SERVICE_UNAVAILABLE:
      return new HttpException(body, HttpStatus.SERVICE_UNAVAILABLE);
    default:
      return new BadRequestException(body);
  }
}

export const SocialGroupErrors = {
  /** Corpo fora do contrato — inclusive um campo server-side (`ownerUid`, `memberCount`, ...). */
  invalid: (reason: string) =>
    groupException(HttpStatus.BAD_REQUEST, SOCIAL_GROUP_ERRORS.INVALID_GROUP_REQUEST, reason),

  /** O nome não é um nome (§8). A `reason` diz a regra violada, nunca o texto recusado. */
  invalidName: (reason: string) =>
    groupException(HttpStatus.BAD_REQUEST, SOCIAL_GROUP_ERRORS.INVALID_GROUP_NAME, reason),

  /** Squads exigem perfil social **ativo** dos dois lados (§16/§96). */
  socialNotEnabled: () =>
    groupException(
      HttpStatus.FORBIDDEN,
      SOCIAL_GROUP_ERRORS.SOCIAL_NOT_ENABLED,
      'esta conta não tem perfil social ativo',
    ),

  /**
   * Anti-enumeração (§59/§60).
   *
   * Inexistente, excluído e "existe mas você não é membro" respondem exatamente isto, com a mesma
   * mensagem. Distinguir transformaria a rota num oráculo: bastaria comparar as respostas para
   * descobrir que um `groupId` existe — e a privacidade de um Squad é justamente não ser
   * descobrível (§5).
   */
  notFound: () =>
    groupException(
      HttpStatus.NOT_FOUND,
      SOCIAL_GROUP_ERRORS.GROUP_NOT_FOUND,
      'squad não encontrado',
    ),

  /**
   * A ação existe, o Squad é visível para quem pediu, mas o papel não permite (§22/§42/§46).
   *
   * `403`, e não `404`: aqui não há o que enumerar — quem recebe esta resposta já provou ser
   * membro do grupo, e esconder que a ação é do dono só faria a tela mostrar um botão que nunca
   * funciona.
   */
  forbidden: (reason: string) =>
    groupException(HttpStatus.FORBIDDEN, SOCIAL_GROUP_ERRORS.GROUP_FORBIDDEN, reason),

  /** Teto de Squads criados (§18). */
  ownedLimitReached: (max: number) =>
    groupException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      SOCIAL_GROUP_ERRORS.GROUP_OWNED_LIMIT_REACHED,
      `esta conta já tem ${max} squads criados`,
    ),

  /** Teto de participações (§18). */
  membershipLimitReached: (max: number) =>
    groupException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      SOCIAL_GROUP_ERRORS.GROUP_MEMBERSHIP_LIMIT_REACHED,
      `esta conta já participa de ${max} squads`,
    ),

  /** O Squad está cheio (§11/§29). */
  full: (max: number) =>
    groupException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      SOCIAL_GROUP_ERRORS.GROUP_FULL,
      `um squad tem no máximo ${max} participantes`,
    ),

  /**
   * O alvo do convite não pode ser convidado (§23/§24/§25/§26).
   *
   * Não é amigo direto ativo, tem bloqueio em alguma direção, desativou o Social, é o próprio
   * requisitante, ou o `socialId` simplesmente não existe: a mesma resposta para os cinco. Um erro
   * que distinguisse "não é seu amigo" de "não existe" transformaria a rota num verificador de
   * `socialId`.
   */
  inviteNotAllowed: () =>
    groupException(
      HttpStatus.NOT_FOUND,
      SOCIAL_GROUP_ERRORS.GROUP_INVITE_NOT_ALLOWED,
      'não é possível convidar esta pessoa',
    ),

  /** O alvo já participa (§27). */
  alreadyMember: () =>
    groupException(
      HttpStatus.CONFLICT,
      SOCIAL_GROUP_ERRORS.GROUP_ALREADY_MEMBER,
      'esta pessoa já participa do squad',
    ),

  /** Convites pendentes demais neste Squad (§126). */
  inviteLimitReached: (max: number) =>
    groupException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      SOCIAL_GROUP_ERRORS.GROUP_INVITE_LIMIT_REACHED,
      `este squad já tem ${max} convites pendentes`,
    ),

  /**
   * O convite não está disponível (§29/§30).
   *
   * Inexistente, de outra pessoa, já respondido, expirado, Squad excluído, Squad cheio, amizade
   * desfeita desde o envio e bloqueio superveniente: **todos** respondem isto. §30 é explícito
   * sobre o caso central — A convida B, deixam de ser amigos, B tenta aceitar —, e o resultado
   * precisa ser indistinguível de "esse convite não existe", ou a resposta contaria a B o estado
   * da relação com A pelo canal errado.
   */
  invitationNotAvailable: () =>
    groupException(
      HttpStatus.NOT_FOUND,
      SOCIAL_GROUP_ERRORS.INVITATION_NOT_AVAILABLE,
      'convite indisponível',
    ),

  /** O dono precisa transferir a posse ou excluir o Squad antes (§39/§43). */
  ownerActionRequired: (reason: string) =>
    groupException(HttpStatus.CONFLICT, SOCIAL_GROUP_ERRORS.GROUP_OWNER_ACTION_REQUIRED, reason),

  /** O membro alvo não existe neste Squad (§42/§40). */
  memberNotFound: () =>
    groupException(
      HttpStatus.NOT_FOUND,
      SOCIAL_GROUP_ERRORS.GROUP_MEMBER_NOT_FOUND,
      'participante não encontrado neste squad',
    ),

  /**
   * O check-in não pode ser compartilhado (§51/§56).
   *
   * Inexistente, de outra conta e excluído respondem a mesma coisa — só o autor compartilha o
   * próprio check-in, e confirmar que um `checkInId` de terceiro existe já seria informação.
   */
  checkInNotFound: () =>
    groupException(
      HttpStatus.NOT_FOUND,
      SOCIAL_GROUP_ERRORS.CHECKIN_NOT_FOUND,
      'check-in não encontrado',
    ),

  /** O check-in já está em Squads demais (§68). */
  shareLimitReached: (max: number) =>
    groupException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      SOCIAL_GROUP_ERRORS.GROUP_SHARE_LIMIT_REACHED,
      `um check-in pode ser compartilhado em no máximo ${max} squads`,
    ),

  /**
   * O `clientRequestId` já foi usado com outro payload (T17.13.1 §36/§37).
   *
   * A `reason` descreve a **forma** do conflito e nunca o valor divergente: dizer qual nome ou
   * qual destinatário estava no registro original devolveria conteúdo que o retry não trouxe.
   */
  idempotencyConflict: (reason: string) =>
    groupException(HttpStatus.CONFLICT, SOCIAL_GROUP_ERRORS.IDEMPOTENCY_CONFLICT, reason),

  rateLimited: () =>
    groupException(
      HttpStatus.TOO_MANY_REQUESTS,
      SOCIAL_GROUP_ERRORS.RATE_LIMITED,
      'muitas operações em pouco tempo; tente novamente em instantes',
    ),

  /**
   * O servidor não conseguiu concluir agora.
   *
   * O caso real é uma corrida entre duas requisições — dois aceites simultâneos do mesmo convite,
   * duas transferências de posse ao mesmo tempo — em que uma `UNIQUE` do banco recusa a segunda.
   * `503` porque nada há de errado com o pedido: tentar de novo lê o estado que a primeira deixou.
   */
  unavailable: (reason: string) =>
    groupException(HttpStatus.SERVICE_UNAVAILABLE, SOCIAL_GROUP_ERRORS.SOCIAL_UNAVAILABLE, reason),
};
