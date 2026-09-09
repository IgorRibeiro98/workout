import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { WORKOUT_CHECKIN_ERRORS, type WorkoutCheckInErrorCode } from './workout-checkin.contract';

/**
 * Os erros dos check-ins de treino (T17.8), no envelope da T16.0.
 *
 * Cada um declara um `code` no corpo — é o que o `AllExceptionsFilter` transforma em
 * `{ error: { code, message, requestId } }` e o que o Android mapeia para erro tipado.
 *
 * **Nenhuma `reason` aqui repete conteúdo do usuário**, e nenhuma repete `sessionSyncId` (§13):
 * a mensagem descreve a *forma* do defeito, nunca o valor. Um `sessionSyncId` numa mensagem de
 * erro acabaria em tela, em relatório de suporte e em log de cliente.
 */
function checkInException(
  status: HttpStatus,
  code: WorkoutCheckInErrorCode,
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

export const WorkoutCheckInErrors = {
  /** Corpo fora do contrato — inclusive um campo server-side (`ownerUid`, `completed`, ...). */
  invalid: (reason: string) =>
    checkInException(
      HttpStatus.BAD_REQUEST,
      WORKOUT_CHECKIN_ERRORS.INVALID_CHECKIN_REQUEST,
      reason,
    ),

  /** Publicar e ler o feed exigem perfil social **ativo**. Desativado fecha os dois lados (§60). */
  socialNotEnabled: () =>
    checkInException(
      HttpStatus.FORBIDDEN,
      WORKOUT_CHECKIN_ERRORS.SOCIAL_NOT_ENABLED,
      'esta conta não tem perfil social ativo',
    ),

  /**
   * Anti-enumeração (§116).
   *
   * Sessão inexistente, sessão de **outra conta**, sessão com tombstone e sessão que o servidor
   * ainda não recebeu respondem exatamente isto, com a mesma mensagem. Distinguir qualquer um
   * deles transformaria a rota num oráculo: "esta sessão pertence a outro usuário" é a informação
   * que ninguém pode obter perguntando.
   */
  sessionNotFound: () =>
    checkInException(
      HttpStatus.NOT_FOUND,
      WORKOUT_CHECKIN_ERRORS.SESSION_NOT_FOUND,
      'sessão de treino não encontrada',
    ),

  /**
   * A sessão é desta conta e existe, mas não está concluída (§17).
   *
   * Aqui não há risco de enumeração — a sessão já foi provada como do próprio requisitante —, e
   * distinguir ajuda o cliente a explicar o que aconteceu em vez de dizer "não encontrada" sobre
   * algo que a pessoa está vendo na tela.
   */
  sessionNotCompleted: () =>
    checkInException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      WORKOUT_CHECKIN_ERRORS.SESSION_NOT_COMPLETED,
      'apenas uma sessão concluída pode virar check-in',
    ),

  /** Fora da janela de 48 horas (§28). O relógio é o do servidor (§26/§27). */
  windowExpired: () =>
    checkInException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      WORKOUT_CHECKIN_ERRORS.CHECKIN_WINDOW_EXPIRED,
      'a janela para publicar o check-in deste treino já passou',
    ),

  /** Já houve um check-in desta sessão e ele foi excluído (§29). */
  alreadyExists: () =>
    checkInException(
      HttpStatus.CONFLICT,
      WORKOUT_CHECKIN_ERRORS.CHECKIN_ALREADY_EXISTS,
      'este treino já teve um check-in publicado',
    ),

  /** Mesmo `clientRequestId`, outra sessão (§32). */
  /**
   * O mesmo `clientUploadId` voltou com bytes diferentes (T17.13.1 §40).
   *
   * `409`, e não `200` com a mídia antiga: devolver a foto anterior faria a pessoa publicar um
   * check-in com a imagem errada, convencida de ter enviado a nova. A mensagem descreve a forma do
   * conflito e nunca o conteúdo de nenhuma das duas imagens.
   */
  mediaUploadConflict: () =>
    checkInException(
      HttpStatus.CONFLICT,
      WORKOUT_CHECKIN_ERRORS.MEDIA_UPLOAD_CONFLICT,
      'este clientUploadId já foi usado para enviar outra imagem',
    ),

  requestConflict: () =>
    checkInException(
      HttpStatus.CONFLICT,
      WORKOUT_CHECKIN_ERRORS.CHECKIN_REQUEST_CONFLICT,
      'este identificador de requisição já foi usado para outra sessão',
    ),

  /** Inexistente **e** de outra conta respondem a mesma coisa (§65). */
  checkInNotFound: () =>
    checkInException(
      HttpStatus.NOT_FOUND,
      WORKOUT_CHECKIN_ERRORS.CHECKIN_NOT_FOUND,
      'check-in não encontrado',
    ),

  rateLimited: () =>
    checkInException(
      HttpStatus.TOO_MANY_REQUESTS,
      WORKOUT_CHECKIN_ERRORS.RATE_LIMITED,
      'muitas publicações em pouco tempo; tente novamente em instantes',
    ),

  /**
   * O servidor não conseguiu concluir agora.
   *
   * O caso real é uma corrida entre duas requisições simultâneas da mesma conta pela mesma sessão:
   * as duas passam pela leitura, e a `UNIQUE` do banco recusa a segunda. `503` porque nada há de
   * errado com o pedido — tentar de novo devolve o check-in que a primeira criou.
   */
  unavailable: (reason: string) =>
    checkInException(
      HttpStatus.SERVICE_UNAVAILABLE,
      WORKOUT_CHECKIN_ERRORS.SOCIAL_UNAVAILABLE,
      reason,
    ),
  // --- T17.9 ---------------------------------------------------------------------------

  /**
   * Legenda ou comentário que não passou na sanitização (§9/§77).
   *
   * A `reason` descreve a **regra** violada, nunca o texto recusado: repetir o que o usuário
   * escreveu numa mensagem de erro o levaria para a tela, para o relatório de suporte e para o
   * log do cliente — exatamente os três lugares onde §161 proíbe que ele apareça.
   */
  invalidContent: (reason: string) =>
    checkInException(HttpStatus.BAD_REQUEST, WORKOUT_CHECKIN_ERRORS.INVALID_CONTENT, reason),

  /**
   * Os bytes não são uma imagem que este servidor aceita (§13/§14/§20).
   *
   * Formato não suportado, arquivo corrompido, animação e bomba de descompressão respondem a mesma
   * coisa. A `reason` diz a classe, e não a mensagem da libvips: a saída de uma biblioteca de
   * imagem pode carregar nome de arquivo, offset e detalhe de build.
   */
  invalidImage: (reason: string) =>
    checkInException(HttpStatus.BAD_REQUEST, WORKOUT_CHECKIN_ERRORS.INVALID_IMAGE, reason),

  /** O upload é maior que o teto configurado (§18). */
  mediaTooLarge: () =>
    checkInException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      WORKOUT_CHECKIN_ERRORS.MEDIA_TOO_LARGE,
      'a imagem enviada é maior que o permitido',
    ),

  /** A conta ocupou a quota de mídia (§29/§30). */
  mediaQuotaExceeded: () =>
    checkInException(
      HttpStatus.UNPROCESSABLE_ENTITY,
      WORKOUT_CHECKIN_ERRORS.MEDIA_QUOTA_EXCEEDED,
      'o espaço de fotos desta conta está cheio',
    ),

  /**
   * Anti-enumeração da mídia (§34/§35/§51/§149).
   *
   * Inexistente, de outra conta, de outra sessão, já anexada a um check-in e de uma conta que
   * trocou durante o upload: os cinco respondem exatamente isto. É a mesma resposta que um viewer
   * sem direito recebe ao pedir os bytes — conhecer o `mediaId` não é, e não pode virar,
   * autorização.
   */
  mediaNotFound: () =>
    checkInException(
      HttpStatus.NOT_FOUND,
      WORKOUT_CHECKIN_ERRORS.MEDIA_NOT_FOUND,
      'imagem não encontrada',
    ),

  /** Tipo de reação fora do enum fechado (§62). */
  invalidReaction: () =>
    checkInException(
      HttpStatus.BAD_REQUEST,
      WORKOUT_CHECKIN_ERRORS.INVALID_REACTION,
      'tipo de reação não reconhecido',
    ),

  /** O comentário não existe, não é visível ou não é apagável por quem pediu (§95). */
  commentNotFound: () =>
    checkInException(
      HttpStatus.NOT_FOUND,
      WORKOUT_CHECKIN_ERRORS.COMMENT_NOT_FOUND,
      'comentário não encontrado',
    ),

  /** Alvo de denúncia inexistente, invisível para quem denuncia, ou o próprio conteúdo (§104–§106). */
  invalidReportTarget: (reason: string) =>
    checkInException(HttpStatus.NOT_FOUND, WORKOUT_CHECKIN_ERRORS.INVALID_REPORT_TARGET, reason),
};
