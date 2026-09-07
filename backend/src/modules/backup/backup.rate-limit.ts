import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { BACKUP_RATE_LIMIT } from './backup.limits';

/**
 * Os dois tetos por conta do backup (T16.8 §84).
 *
 * Separados porque escrever um snapshot e listar metadata não custam a mesma coisa — e porque um
 * limite único forçaria escolher entre barrar um app em laço e parar um restore legítimo. A
 * política mora em `backup.limits.ts`; aqui só existe a contagem.
 */
@Injectable()
export class BackupRateLimiter {
  private readonly writes = new FixedWindowRateLimiter(BACKUP_RATE_LIMIT.write);
  private readonly reads = new FixedWindowRateLimiter(BACKUP_RATE_LIMIT.read);

  tryAcquireWrite(uid: string): boolean {
    return this.writes.tryAcquire(uid);
  }

  tryAcquireRead(uid: string): boolean {
    return this.reads.tryAcquire(uid);
  }
}
