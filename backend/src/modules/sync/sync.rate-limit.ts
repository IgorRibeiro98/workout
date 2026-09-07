import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { SYNC_RATE_LIMIT } from './sync.limits';

/**
 * Proteção simples por conta contra um cliente em laço (T16.6 §99).
 *
 * A mecânica mora em `common/rate-limiter.ts` desde a T16.8, porque o backup precisou da mesma
 * coisa e duas cópias do mesmo laço divergiriam na primeira correção. O que continua sendo daqui é
 * a **política**: quanto o sync aceita, declarado em `sync.limits.ts` e em nenhum outro lugar.
 */
@Injectable()
export class SyncRateLimiter extends FixedWindowRateLimiter {
  constructor() {
    super(SYNC_RATE_LIMIT);
  }
}
