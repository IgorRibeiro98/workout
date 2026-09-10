import { Injectable } from '@nestjs/common';
import type { InteractionContextRequest } from './workout-checkin.contract';
import { WorkoutCheckInErrors } from './workout-checkin.errors';
import { WorkoutCheckInAccessPolicy, type VisibleCheckIn } from './workout-checkin.access-policy';

/**
 * A audiência **autorizada** de uma interação, depois de revalidada contra as tabelas (T17.12 §7).
 *
 * Nunca é o que o cliente enviou — é o que o servidor confirmou. `FRIEND` não carrega `groupId`;
 * `GROUP` sempre carrega um, e é o `id` interno de `social_groups` (§28), não uma string arbitrária.
 */
export type InteractionAudience =
  | { readonly type: 'FRIEND' }
  | { readonly type: 'GROUP'; readonly groupId: string };

/** O resultado de resolver um contexto: a audiência autorizada **e** o check-in que ela alcança. */
export interface ResolvedInteractionContext {
  readonly audience: InteractionAudience;
  readonly checkIn: VisibleCheckIn;
}

/**
 * `WorkoutCheckInContextResolver` (T17.12 §32).
 *
 * ## O que ele resolve, e por quê ele é o único lugar que resolve isso
 *
 * Toda superfície de **mutação** de interação — reagir, remover reação, comentar — e a leitura de
 * comentários passam por aqui antes de tocar o banco. Cada uma delas faz a mesma pergunta:
 * "dado este `checkInId`, este viewer e o contexto que o cliente propôs, qual audiência autorizada
 * isto realmente é?" — e a resposta não pode divergir entre elas, ou um ajuste em uma rota deixaria
 * as outras aceitando o que não deveriam (a mesma razão pela qual `WorkoutCheckInAccessPolicy`
 * existe como classe única desde a T17.9).
 *
 * ## Contexto é uma **proposta**, nunca uma concessão (§7/§67/§69)
 *
 * `requested.groupId` diz onde o cliente *acha* que está. Quem decide se isso é verdade é
 * [WorkoutCheckInAccessPolicy.findGroupAccessibleCheckIn], contra `social_group_checkin_shares`,
 * `social_group_memberships` e `social_blocks` — **a cada chamada**, nunca a partir de algo que o
 * dispositivo guardou. Um `groupId` que não corresponda ao Squad onde o check-in está de fato
 * nunca "cai" para `FRIEND`: ele é recusado com o mesmo `404` de "não existe" (§69/§135) — fail
 * **closed**, nunca fail-open para uma audiência mais fraca.
 *
 * ## Ausência de contexto (§68)
 *
 * Um corpo sem `context` — o formato de todo cliente anterior à T17.12 — resolve para `FRIEND`.
 * Não é uma adivinhação: é a mesma autorização que essas rotas sempre exigiram (`findVisibleCheckIn`
 * — relação direta), só que agora nomeada.
 */
@Injectable()
export class WorkoutCheckInContextResolver {
  constructor(private readonly accessPolicy: WorkoutCheckInAccessPolicy) {}

  async resolve(
    viewerUid: string,
    checkInId: string,
    requested: InteractionContextRequest | undefined,
  ): Promise<ResolvedInteractionContext> {
    if (requested?.type === 'GROUP') {
      const groupId = requested.groupId as string;
      const checkIn = await this.accessPolicy.findGroupAccessibleCheckIn(
        viewerUid,
        checkInId,
        groupId,
      );
      if (!checkIn) {
        throw WorkoutCheckInErrors.checkInNotFound();
      }
      return { audience: { type: 'GROUP', groupId }, checkIn };
    }

    const checkIn = await this.accessPolicy.findVisibleCheckIn(viewerUid, checkInId);
    if (!checkIn) {
      throw WorkoutCheckInErrors.checkInNotFound();
    }
    return { audience: { type: 'FRIEND' }, checkIn };
  }
}
