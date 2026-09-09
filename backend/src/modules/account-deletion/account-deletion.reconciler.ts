import { Injectable, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { Inject } from '@nestjs/common';
import { AccountDeletionRepository } from './account-deletion.repository';
import { AccountDeletionService } from './account-deletion.service';

const RECONCILER_INTERVAL_MS = 60_000;
const MAX_BACKOFF_MS = 3_600_000;

@Injectable()
export class AccountDeletionReconciler implements OnModuleInit, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private isProcessing = false;

  constructor(
    private readonly repo: AccountDeletionRepository,
    // T17.13.1 §11 — a sequência de passos pendentes de uma exclusão tem **um** dono
    // (`AccountDeletionService.advanceJob`). Este reconciliador contribui com o que é dele: a
    // varredura periódica e o backoff. Ele não sabe o que é "ledger" nem o que é "Firebase" —
    // duplicar essa ordem aqui faria as duas cópias divergirem, e a divergência é uma conta presa
    // numa fase que só um dos dois caminhos destrava.
    private readonly service: AccountDeletionService,
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
          await this.service.advanceJob(job, now);
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
            // A fase relida do banco, e não a do `job` em memória: `advanceJob` pode ter
            // persistido o ledger e falhado só no passo seguinte, e é a fase **nova** que diz o
            // que o retry vai tentar.
            phase: this.repo.findJobByFirebaseUid(job.firebase_uid)?.phase ?? job.phase,
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
