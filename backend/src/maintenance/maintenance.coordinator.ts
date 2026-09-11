import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { CLOCK, type Clock } from '../common/clock';
import { SparkLogger } from '../common/logger';
import { PostgresService } from '../database/postgres.service';
import { NotificationDispatcher } from '../modules/social/notification.dispatcher';
import { SocialMediaCleaner } from '../modules/social/social-media.cleaner';
import { BackupPayloadCleaner } from '../modules/backup/backup-payload.cleaner';
import { AccountDeletionReconciler } from '../modules/account-deletion/account-deletion.reconciler';

export interface MaintenanceCycleResult {
  readonly skipped: boolean;
  readonly notificationsDispatched: boolean;
  readonly accountDeletionJobsProcessed: number;
  readonly socialMediaSweepRan: boolean;
  readonly socialMediaObjectsRemoved: number;
  readonly backupPayloadSweepRan: boolean;
  readonly backupPayloadObjectsRemoved: number;
}

const SKIPPED_RESULT: MaintenanceCycleResult = {
  skipped: true,
  notificationsDispatched: false,
  accountDeletionJobsProcessed: 0,
  socialMediaSweepRan: false,
  socialMediaObjectsRemoved: 0,
  backupPayloadSweepRan: false,
  backupPayloadObjectsRemoved: 0,
};

/**
 * O ciclo de manutenção bounded do Cloud Run (T18.2 §37–§39).
 *
 * ## O que ele substitui
 *
 * Em `BACKGROUND_JOBS_MODE=interval` (VPS), quatro workers agendam o próprio `setInterval` e
 * competem por CPU no mesmo processo que serve HTTP. Em `disabled` (Cloud Run), nenhum agenda
 * nada — é este coordenador, chamado uma vez por invocação HTTP de `spark-maintenance`
 * (`MaintenanceController`, acionado pelo Cloud Scheduler), que decide o que rodar nesta passagem.
 *
 * ## Por que um lock consultivo, e não só `max instances=1` + `concurrency=1`
 *
 * §39 é explícito: retry do Scheduler ou um deploy no meio de uma execução podem sobrepor duas
 * chamadas de verdade, mesmo com a configuração mais conservadora do Cloud Run. `runCycle()` tenta
 * `pg_try_advisory_lock` numa conexão dedicada do pool **antes** de tocar em qualquer worker; se
 * não conseguir, devolve `skipped: true` sem processar nada — nunca bloqueia esperando, porque um
 * ciclo que não rodou agora roda no próximo minuto.
 *
 * O lock é de **sessão** (não de transação): ele precisa cobrir o ciclo inteiro, que faz chamadas
 * de rede lentas (FCM, GCS) intercaladas com consultas ao banco em conexões diferentes do pool — um
 * lock de transação (`pg_advisory_xact_lock`) exigiria manter uma transação aberta por toda essa
 * janela, e uma transação longa segurando uma conexão do pool durante I/O de rede é exatamente o
 * tipo de recurso que este processo não pode desperdiçar com `DATABASE_POOL_MAX` pequeno (T18.2
 * §23). Por isso a conexão é obtida e devolvida manualmente (`pool.connect()`), e não por
 * `PostgresService.transaction()`.
 *
 * ## Por que cadência diferente por worker, e não "roda tudo toda vez"
 *
 * Notificação e exclusão de conta precisam de latência baixa — é gente esperando um convite chegar
 * ou uma conta terminar de sair. Os dois cleaners de órfãos são o oposto: órfão é raro, e cada
 * varredura é uma listagem paga no Object Storage (§38). `claimDue` é um CAS atômico sobre
 * `server_metadata` — o mesmo tipo de linha que já guarda `migrations_applied_at` — que só deixa
 * o cleaner rodar quando já passou `*_CLEANUP_INTERVAL_MS` desde a última vez, reaproveitando os
 * mesmos dois valores de configuração que, em modo `interval`, seriam o período do `setInterval`.
 */
@Injectable()
export class MaintenanceCoordinator {
  constructor(
    private readonly postgres: PostgresService,
    private readonly notificationDispatcher: NotificationDispatcher,
    private readonly accountDeletionReconciler: AccountDeletionReconciler,
    private readonly socialMediaCleaner: SocialMediaCleaner,
    private readonly backupPayloadCleaner: BackupPayloadCleaner,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  async runCycle(): Promise<MaintenanceCycleResult> {
    const client = await this.postgres.pool.connect();
    let locked = false;
    try {
      const { rows } = await client.query<{ locked: boolean }>(
        `SELECT pg_try_advisory_lock(hashtext('spark_maintenance_cycle')) AS locked`,
      );
      locked = rows[0]?.locked ?? false;
      if (!locked) {
        this.logger.info('maintenance.cycle.skipped_locked', {});
        return SKIPPED_RESULT;
      }

      let notificationsDispatched = false;
      if (this.config.socialPushEnabled) {
        await this.notificationDispatcher.runDispatchCycle();
        notificationsDispatched = true;
      }

      const accountDeletionJobsProcessed = await this.accountDeletionReconciler.processDueJobs();

      let socialMediaSweepRan = false;
      let socialMediaObjectsRemoved = 0;
      if (await this.claimDue('social_media_cleanup', this.config.socialMediaCleanupIntervalMs)) {
        socialMediaObjectsRemoved = await this.socialMediaCleaner.sweep();
        socialMediaSweepRan = true;
      }

      let backupPayloadSweepRan = false;
      let backupPayloadObjectsRemoved = 0;
      if (
        await this.claimDue('backup_payload_cleanup', this.config.backupPayloadCleanupIntervalMs)
      ) {
        backupPayloadObjectsRemoved = await this.backupPayloadCleaner.sweep();
        backupPayloadSweepRan = true;
      }

      this.logger.info('maintenance.cycle.completed', {
        notificationsDispatched,
        accountDeletionJobsProcessed,
        socialMediaSweepRan,
        backupPayloadSweepRan,
      });

      return {
        skipped: false,
        notificationsDispatched,
        accountDeletionJobsProcessed,
        socialMediaSweepRan,
        socialMediaObjectsRemoved,
        backupPayloadSweepRan,
        backupPayloadObjectsRemoved,
      };
    } finally {
      if (locked) {
        await client
          .query(`SELECT pg_advisory_unlock(hashtext('spark_maintenance_cycle'))`)
          .catch(() => undefined);
      }
      client.release();
    }
  }

  /**
   * Reivindica atomicamente o direito de rodar um worker de baixa cadência agora.
   *
   * `INSERT ... ON CONFLICT DO UPDATE ... WHERE` é a forma de CAS de uma linha só: sem linha
   * anterior, a reivindicação sempre vence (primeira execução); com linha anterior, só vence se já
   * passou `intervalMs` desde `updated_at`. Perder a corrida (`rowCount === 0`) significa "outro
   * ciclo já rodou este worker recentemente" — nunca um erro.
   */
  private async claimDue(key: string, intervalMs: number): Promise<boolean> {
    const now = this.clock.now();
    const result = await this.postgres.query(
      `INSERT INTO server_metadata (key, value, updated_at)
       VALUES ($1, $2, $3)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
       WHERE ($3 - server_metadata.updated_at) >= $4
       RETURNING 1`,
      [key, String(now), now, intervalMs],
    );
    return (result.rowCount ?? 0) > 0;
  }
}
