import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  NotFoundException,
} from '@nestjs/common';
import { MultiplayerErrorCodes } from './multiplayer.contract';

/**
 * Os erros do multiplayer, no envelope da T16.0.
 *
 * Nenhuma mensagem carrega valor: nem `socialId`, nem nome, nem uid, nem conteúdo de evento. E
 * as respostas que virariam oráculo colapsam — sala de outra pessoa, sala inexistente e sala de um
 * par bloqueado são **a mesma** resposta (`404`), como em todo o social desde a T17.1.
 */
export const MultiplayerErrors = {
  invalid: (reason: string): HttpException =>
    new BadRequestException({ code: MultiplayerErrorCodes.INVALID_REQUEST, message: reason }),

  socialNotEnabled: (): HttpException =>
    new ForbiddenException({
      code: MultiplayerErrorCodes.SOCIAL_NOT_ENABLED,
      message: 'Treinar em dupla à distância exige o perfil social ativo.',
    }),

  /** Convidado inexistente e convidado que não é amigo são a mesma resposta (anti-enumeração). */
  friendshipRequired: (): HttpException =>
    new ForbiddenException({
      code: MultiplayerErrorCodes.FRIENDSHIP_REQUIRED,
      message: 'Só é possível treinar à distância com um amigo.',
    }),

  cannotInviteSelf: (): HttpException =>
    new BadRequestException({
      code: MultiplayerErrorCodes.CANNOT_INVITE_SELF,
      message: 'Não é possível convidar a si mesmo.',
    }),

  roomNotFound: (): HttpException =>
    new NotFoundException({
      code: MultiplayerErrorCodes.ROOM_NOT_FOUND,
      message: 'Sala não encontrada.',
    }),

  roomClosed: (): HttpException =>
    new ConflictException({
      code: MultiplayerErrorCodes.ROOM_CLOSED,
      message: 'Esta sala já foi encerrada.',
    }),

  roomExpired: (): HttpException =>
    new ConflictException({
      code: MultiplayerErrorCodes.ROOM_EXPIRED,
      message: 'Esta sala expirou.',
    }),

  notAMember: (): HttpException =>
    new ForbiddenException({
      code: MultiplayerErrorCodes.NOT_A_MEMBER,
      message: 'Você ainda não entrou nesta sala.',
    }),

  /** Quem saiu explicitamente não volta por reconexão automática (T19.5 §10). */
  memberLeft: (): HttpException =>
    new ConflictException({
      code: MultiplayerErrorCodes.MEMBER_LEFT,
      message: 'Você saiu desta sala.',
    }),

  notHost: (): HttpException =>
    new ForbiddenException({
      code: MultiplayerErrorCodes.NOT_HOST,
      message: 'Só quem criou a sala pode encerrá-la.',
    }),

  conflict: (reason: string): HttpException =>
    new ConflictException({ code: MultiplayerErrorCodes.CONFLICT, message: reason }),

  eventLimit: (): HttpException =>
    new ConflictException({
      code: MultiplayerErrorCodes.EVENT_LIMIT,
      message: 'Esta sala atingiu o limite de eventos.',
    }),

  rateLimited: (): HttpException =>
    new HttpException(
      {
        code: MultiplayerErrorCodes.RATE_LIMITED,
        message: 'Limite diário de salas atingido.',
      },
      HttpStatus.TOO_MANY_REQUESTS,
    ),
} as const;
