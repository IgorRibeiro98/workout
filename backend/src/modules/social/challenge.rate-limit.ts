import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { CHALLENGE_RATE_LIMIT } from './challenge.limits';

/**
 * Os dois tetos próprios dos desafios, por conta (T17.3 §108–§110).
 *
 * Separados porque criar e responder não são a mesma coisa. **Criar é a operação mais cara do
 * módulo em consequência social**: cada criação dispara até nove convites para pessoas que não
 * pediram nada, e um laço no cliente transformaria isso em spam entre amigos. Responder é
 * idempotente e não alcança terceiros — o risco ali é o toque repetido, não o abuso.
 *
 * O teto geral do `BearerAuthGuard` (600/min) não serve para o primeiro: 600 criações por minuto
 * são 600 desafios e milhares de convites.
 *
 * A chave é o `uid` autenticado, **nunca** o IP (§110): em rede móvel e atrás de NAT o IP é
 * compartilhado por gente que não tem nada a ver com o abuso, e o Caddy à frente faria todo mundo
 * parecer o mesmo cliente. Todas as rotas de desafio exigem Bearer, então o `uid` sempre existe
 * quando a contagem acontece.
 *
 * Em memória, e sem Redis, como todo limitador deste servidor: um processo, uma VPS (ADR-0001).
 * Reiniciar zera as janelas, e isso é aceitável — o teto contém laço, não cobra cota.
 */
@Injectable()
export class ChallengeRateLimiter {
  private readonly creations = new FixedWindowRateLimiter(CHALLENGE_RATE_LIMIT.create);
  private readonly responses = new FixedWindowRateLimiter(CHALLENGE_RATE_LIMIT.respond);

  tryAcquireCreate(uid: string): boolean {
    return this.creations.tryAcquire(uid);
  }

  /** Aceitar, recusar, sair e cancelar compartilham o teto: todas são respostas a algo que existe. */
  tryAcquireRespond(uid: string): boolean {
    return this.responses.tryAcquire(uid);
  }
}
