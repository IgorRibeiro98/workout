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
  private isShuttingDown = false;

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
    void this.processDueJobs().catch(() => undefined);
    this.timer = setInterval(() => {
      void this.processDueJobs().catch(() => undefined);
    }, RECONCILER_INTERVAL_MS);
    this.timer.unref?.();
  }

  onApplicationShutdown(): void {
    this.isShuttingDown = true;
    this.isProcessing = true;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  async processDueJobs(): Promise<number> {
    if (this.isProcessing || this.isShuttingDown) return 0;
    this.isProcessing = true;

    try {
      const now = this.clock.now();
      const dueJobs = await this.repo.findDueJobs(now, 20);
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

          await this.repo.incrementJobAttempt(job.id, errorMessage, nextAttemptAt);
          const refreshed = await this.repo.findJobByFirebaseUid(job.firebase_uid);
          this.logger.warn('account.deletion.job.retry_scheduled', {
            jobId: job.id,
            // A fase relida do banco, e não a do `job` em memória: `advanceJob` pode ter
            // persistido o ledger e falhado só no passo seguinte, e é a fase **nova** que diz o
            // que o retry vai tentar.
            phase: refreshed?.phase ?? job.phase,
            attempt,
            nextAttemptInSec: Math.round(backoff / 1000),
          });
        }
      }

      return processed;
    } catch (error: unknown) {
      this.logger.warn('account.deletion.reconciler_cycle_error', {
        error: error instanceof Error ? error.name : 'Unknown',
      });
      return 0;
    } finally {
      this.isProcessing = false;
    }
  }
}
