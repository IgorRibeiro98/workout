import { Injectable } from '@nestjs/common';
import type { StoredFriendRequest } from './friendship.repository';

/**
 * Quem pode agir sobre um pedido de amizade e sobre uma amizade (T17.1 §82).
 *
 * Ela existe pelo mesmo motivo que a `SocialAccessPolicy`: a alternativa é `if (request.recipient
 * === principal.uid)` espalhado por quatro handlers, cada cópia envelhecendo no seu ritmo. A
 * quarta cópia — a esquecida — é sempre a que deixa a conta errada agir.
 *
 * ## O que ela decide
 *
 * **Participação.** Dado um pedido e um uid autenticado, quem é quem naquele pedido. Ela não
 * decide autenticação (isso é o `BearerAuthGuard` sobre um Firebase ID Token verificado) e não
 * decide visibilidade de perfil (isso é a `SocialAccessPolicy`, que a T17.1 continua consultando
 * para descoberta e para pedidos).
 *
 * ## A regra que não tem exceção
 *
 * ```text
 * aceitar  ─┬─ só o destinatário
 * recusar  ─┘
 * cancelar ─── só quem enviou
 * remover amizade ─── qualquer um dos dois do par, e mais ninguém
 * ```
 *
 * Uma conta C não participa de nada entre A e B — e a resposta que ela recebe é "não existe", não
 * "não é seu": saber que um pedido existe já é mais do que ela deveria aprender.
 */
@Injectable()
export class FriendshipAccessPolicy {
  /** O pedido é **visível** para esta conta? Só os dois participantes o veem (§117). */
  isParticipant(request: StoredFriendRequest, uid: string): boolean {
    return request.requesterUid === uid || request.recipientUid === uid;
  }

  /**
   * Esta conta pode aceitar ou recusar?
   *
   * Somente o destinatário. Quem enviou **não** pode aceitar o próprio pedido — se pudesse, o
   * pedido não seria um pedido, e a amizade não exigiria consentimento do outro lado.
   */
  canRespond(request: StoredFriendRequest, uid: string): boolean {
    return request.recipientUid === uid;
  }

  /** Esta conta pode cancelar? Somente quem enviou: cancelar é retirar a própria proposta. */
  canCancel(request: StoredFriendRequest, uid: string): boolean {
    return request.requesterUid === uid;
  }

  /**
   * Esta conta pode desfazer a amizade entre [uidA] e [uidB]?
   *
   * Qualquer um dos dois, e ninguém mais (§44). Não há hierarquia: quem foi convidado tem
   * exatamente o mesmo poder de sair de quem convidou. E sair **não** é bloquear — bloqueio é uma
   * capacidade distinta, com consequências distintas, e continua fora de escopo (T17.6).
   */
  canRemoveFriendship(uidA: string, uidB: string, uid: string): boolean {
    return uid === uidA || uid === uidB;
  }
}
