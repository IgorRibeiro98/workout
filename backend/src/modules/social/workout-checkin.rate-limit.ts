import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { WORKOUT_CHECKIN_RATE_LIMIT } from './workout-checkin.limits';

/**
 * O teto de criação de check-in, por conta (T17.8 §117).
 *
 * Um só, e sobre o `create`. Ler o feed e excluir uma publicação própria não ganham teto próprio:
 * a leitura não enumera nada (a audiência é derivada no servidor, §71) e a exclusão só alcança
 * linha da própria conta e é idempotente. O teto geral do `BearerAuthGuard` (600/min) basta para
 * as duas.
 *
 * A chave é o `uid` autenticado, nunca o IP. Em memória e sem Redis, como todo limitador deste
 * servidor: um processo, uma VPS (ADR-0001). Reiniciar zera as janelas, e isso é aceitável — o
 * teto contém laço de cliente, não cobra cota.
 */
@Injectable()
export class WorkoutCheckInRateLimiter {
  private readonly creations = new FixedWindowRateLimiter(WORKOUT_CHECKIN_RATE_LIMIT.create);

  tryAcquireCreate(uid: string): boolean {
    return this.creations.tryAcquire(uid);
  }
}
