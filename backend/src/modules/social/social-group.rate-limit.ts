import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { SOCIAL_GROUP_RATE_LIMIT } from './social-group.limits';

/**
 * Os quatro tetos próprios dos Squads, por conta autenticada (T17.11 §125).
 *
 * Separados porque criar, convidar, mexer na composição e compartilhar não são a mesma coisa —
 * e a matriz de §125 pede exatamente essa separação. As razões de cada número estão em
 * `social-group.limits.ts`, junto com os valores; aqui fica só a mecânica.
 *
 * O teto geral do `BearerAuthGuard` (600/min) não serve para nenhum dos quatro: 600 criações por
 * minuto são 600 grupos, e 600 convites por minuto são 600 pushes para pessoas que não pediram
 * nada.
 *
 * A chave é o `uid` autenticado, **nunca** o IP: em rede móvel e atrás de NAT o IP é compartilhado
 * por gente que não tem nada a ver com o abuso, e o Caddy à frente faria todo mundo parecer o
 * mesmo cliente. Todas as rotas de Squad exigem Bearer, então o `uid` sempre existe quando a
 * contagem acontece.
 *
 * Em memória, e sem Redis, como todo limitador deste servidor: um processo, uma VPS (ADR-0001).
 * Reiniciar zera as janelas, e isso é aceitável — o teto contém laço, não cobra cota.
 */
@Injectable()
export class SocialGroupRateLimiter {
  private readonly creations = new FixedWindowRateLimiter(SOCIAL_GROUP_RATE_LIMIT.create);
  private readonly invitations = new FixedWindowRateLimiter(SOCIAL_GROUP_RATE_LIMIT.invite);
  private readonly memberships = new FixedWindowRateLimiter(SOCIAL_GROUP_RATE_LIMIT.membership);
  private readonly shares = new FixedWindowRateLimiter(SOCIAL_GROUP_RATE_LIMIT.share);

  tryAcquireCreate(uid: string): boolean {
    return this.creations.tryAcquire(uid);
  }

  tryAcquireInvite(uid: string): boolean {
    return this.invitations.tryAcquire(uid);
  }

  /**
   * Aceitar, recusar, cancelar, sair, remover e transferir compartilham o teto.
   *
   * Todas são respostas a algo que já existe, todas são idempotentes e nenhuma alcança quem não
   * está no grupo: o risco ali é o toque repetido, não o abuso.
   */
  tryAcquireMembership(uid: string): boolean {
    return this.memberships.tryAcquire(uid);
  }

  /** Compartilhar e descompartilhar um check-in **próprio** em um Squad. */
  tryAcquireShare(uid: string): boolean {
    return this.shares.tryAcquire(uid);
  }
}
