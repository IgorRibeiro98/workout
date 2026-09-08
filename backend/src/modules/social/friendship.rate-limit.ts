import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { SOCIAL_FRIEND_RATE_LIMIT } from './social.limits';

/**
 * Os dois tetos próprios do grafo social, por conta (T17.1 §11–§14).
 *
 * A T17.0 registrou que as rotas sociais não precisavam de limitador próprio: elas escrevem um
 * nome e três booleanos. O lookup muda isso — ele **é** a rota que alguém tentaria varrer, e o
 * teto geral de 600/min do `BearerAuthGuard` é alto demais para essa finalidade específica.
 *
 * Separados porque procurar e enviar não são a mesma coisa: um teto único forçaria escolher entre
 * conter varredura de código e impedir alguém de adicionar o time inteiro depois do treino. A
 * política mora em `social.limits.ts`; aqui só existe a contagem, sobre o limitador compartilhado
 * da T16.8.
 */
@Injectable()
export class FriendshipRateLimiter {
  private readonly lookups = new FixedWindowRateLimiter(SOCIAL_FRIEND_RATE_LIMIT.lookup);
  private readonly sends = new FixedWindowRateLimiter(SOCIAL_FRIEND_RATE_LIMIT.sendRequest);

  tryAcquireLookup(uid: string): boolean {
    return this.lookups.tryAcquire(uid);
  }

  tryAcquireSend(uid: string): boolean {
    return this.sends.tryAcquire(uid);
  }
}
