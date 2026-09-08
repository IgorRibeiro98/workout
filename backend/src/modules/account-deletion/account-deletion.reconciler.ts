import { Inject, Injectable, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { AUTH_TOKEN_VERIFIER, type AuthTokenVerifier } from '../auth/auth-token-verifier';
import { AccountDeletionRepository } from './account-deletion.repository';

const RECONCILER_INTERVAL_MS = 60_000;
const MAX_BACKOFF_MS = 3_600_000;

@Injectable()
export class AccountDeletionReconciler implements OnModuleInit, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private isProcessing = false;

  constructor(
    private readonly repo: AccountDeletionRepository,
    @Inject(AUTH_TOKEN_VERIFIER) private readonly authVerifier: AuthTokenVerifier,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  onModuleInit(): void {
    // Roda uma verificação inicial e agenda o intervalo
    void this.processDueJobs();
    this.timer = setInterval(() => {
      void this.processDueJobs();
    }, RECONCILER_INTERVAL_MS);
  }

  onApplicationShutdown(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  async processDueJobs(): Promise<number> {
    if (this.isProcessing) return 0;
    this.isProcessing = true;

    try {
      const now = this.clock.now();
      const dueJobs = this.repo.findDueJobs(now, 20);
      let processed = 0;

      for (const job of dueJobs) {
        try {
          if (this.authVerifier.deleteUser) {
            await this.authVerifier.deleteUser(job.firebase_uid);
          }
          this.repo.deleteJob(job.id);
          this.logger.info('account.deletion.job.completed', {
            jobId: job.id,
            attempts: job.attempts + 1,
          });
          processed++;
        } catch (error: unknown) {
          const attempt = job.attempts + 1;
          const backoff = Math.min(Math.pow(2, attempt) * 10_000, MAX_BACKOFF_MS);
          const nextAttemptAt = now + backoff;
          const errorMessage = error instanceof Error ? error.message : String(error);

          this.repo.incrementJobAttempt(job.id, errorMessage, nextAttemptAt);
          this.logger.warn('account.deletion.job.retry_scheduled', {
            jobId: job.id,
            attempt,
            nextAttemptInSec: Math.round(backoff / 1000),
          });
        }
      }

      return processed;
    } finally {
      this.isProcessing = false;
    }
  }
}
