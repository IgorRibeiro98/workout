import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { CLOCK, type Clock } from '../common/clock';
import { SparkLogger } from '../common/logger';
import {
  classifyDatabaseSize,
  isDatabaseSizeAlerting,
  type DatabaseSizeLevel,
} from '../database/database-size.policy';
import { PostgresService } from '../database/postgres.service';
import { DrBackupStore } from '../dr/dr-backup.store';
import { NotificationDispatcher } from '../modules/social/notification.dispatcher';
import { SocialMediaCleaner } from '../modules/social/social-media.cleaner';
import { BackupPayloadCleaner } from '../modules/backup/backup-payload.cleaner';
import { AccountDeletionReconciler } from '../modules/account-deletion/account-deletion.reconciler';
import {
  OBJECT_STORAGE_CLIENT,
  type ObjectStorageClient,
} from '../object-storage/object-storage.client';

export interface MaintenanceCycleResult {
  readonly skipped: boolean;
  readonly notificationsDispatched: boolean;
  readonly accountDeletionJobsProcessed: number;
  readonly socialMediaSweepRan: boolean;
  readonly socialMediaObjectsRemoved: number;
  readonly backupPayloadSweepRan: boolean;
  readonly backupPayloadObjectsRemoved: number;
  readonly databaseSizeChecked: boolean;
  readonly drBackupChecked: boolean;
  readonly durationMs: number;
  /**
   * Só em resultado `skipped`: o lock estava tomado E o último sucesso já passou de
   * `MAINTENANCE_STALE_AFTER_MS`. Um `skipped` isolado é normal (retry do Scheduler); um `skipped`
   * com o heartbeat velho é o ciclo preso — e o controller responde 503 para o Scheduler e os
   * alertas verem (T18.3 §12: foi este o modo de falha real do lock de sessão no pooler).
   */
  readonly staleWhileLocked?: { readonly ageMs: number | null; readonly staleAfterMs: number };
}

const SKIPPED_RESULT: MaintenanceCycleResult = {
  skipped: true,
  notificationsDispatched: false,
  accountDeletionJobsProcessed: 0,
  socialMediaSweepRan: false,
  socialMediaObjectsRemoved: 0,
  backupPayloadSweepRan: false,
  backupPayloadObjectsRemoved: 0,
  databaseSizeChecked: false,
  drBackupChecked: false,
  durationMs: 0,
};

/**
 * O heartbeat persistido do maintenance e as duas observações que ele carrega (T18.3 §12/§13).
 *
 * Tudo aqui vem de `server_metadata` — sobrevive a restart, revision nova e scale-to-zero, e é o
 * que `GET /internal/maintenance/status` devolve. Nenhum valor é sensível.
 */
export interface MaintenanceStatus {
  readonly now: number;
  readonly lastStartedAt: number | null;
  readonly lastCompletedAt: number | null;
  readonly lastSuccessAt: number | null;
  readonly lastFailureAt: number | null;
  readonly lastDurationMs: number | null;
  readonly lastErrorName: string | null;
  readonly staleAfterMs: number;
  /** Idade do último sucesso, ou `null` se nunca houve um. */
  readonly ageMs: number | null;
  /** `true` sem sucesso registrado, ou com o último sucesso mais antigo que `staleAfterMs`. */
  readonly stale: boolean;
  readonly databaseSize: {
    readonly checkedAt: number;
    readonly sizeBytes: number;
    readonly level: DatabaseSizeLevel;
  } | null;
  readonly drBackup: {
    readonly checkedAt: number;
    readonly latestBackupId: string | null;
    readonly latestCreatedAt: number | null;
    readonly ageMs: number | null;
    readonly maxAgeMs: number;
    readonly stale: boolean;
  } | null;
}

/** As chaves de `server_metadata` do heartbeat. Um lugar só; o status lê exatamente estas. */
const KEYS = {
  startedAt: 'maintenance_last_started_at',
  completedAt: 'maintenance_last_completed_at',
  successAt: 'maintenance_last_success_at',
  failureAt: 'maintenance_last_failure_at',
  durationMs: 'maintenance_last_duration_ms',
  errorName: 'maintenance_last_error',
  dbSizeCheckedAt: 'database_size_checked_at',
  dbSizeBytes: 'database_size_bytes',
  dbSizeLevel: 'database_size_level',
  drCheckedAt: 'dr_backup_checked_at',
  drLatestId: 'dr_backup_latest_id',
  drLatestCreatedAt: 'dr_backup_latest_created_at',
} as const;

/**
 * O ciclo de manutenção bounded do Cloud Run (T18.2 §37–§39; heartbeat e observações na T18.3).
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
 * `pg_try_advisory_xact_lock` numa conexão dedicada do pool **antes** de tocar em qualquer worker;
 * se não conseguir, devolve `skipped: true` sem processar nada — nunca bloqueia esperando, porque
 * um ciclo que não rodou agora roda no próximo minuto.
 *
 * ## Por que o lock é de TRANSAÇÃO, numa transação aberta pelo ciclo inteiro (T18.3)
 *
 * A T18.2 usava `pg_try_advisory_lock` de **sessão**, e isso quebrou em produção no primeiro dia
 * de heartbeat: `DATABASE_URL` é o endpoint *pooled* do Neon (PgBouncer em modo transação), onde
 * cada statement fora de transação pode cair numa conexão de servidor diferente. O lock ficava na
 * conexão que executou o `pg_try_advisory_lock`; o `pg_advisory_unlock` caía em outra e não
 * liberava nada; a conexão presa voltava ao pooler segurando o lock, e todo ciclo seguinte era
 * `skipped_locked` — foi o `maintenance_stale` da T18.3 que denunciou. O próprio Neon documenta
 * que lock consultivo de sessão não é suportado no endpoint pooled; é a mesma razão pela qual o
 * `migrate-database` exige `DATABASE_URL_DIRECT`.
 *
 * Uma transação aberta prende a conexão de servidor ao cliente até o fim (é a definição do modo
 * transação), então `pg_try_advisory_xact_lock` dentro de `BEGIN … ROLLBACK` tem exatamente a
 * semântica que o ciclo precisa — e ainda ganha: se o processo morrer no meio, o pooler aborta a
 * transação e o lock some, em vez de sobreviver numa conexão órfã. O custo é uma conexão do pool
 * (de `DATABASE_POOL_MAX`) ociosa em transação enquanto o ciclo faz I/O de rede — aceitável para
 * um ciclo de segundos, num processo que só faz isto. A chave mudou junto
 * (`spark_maintenance_cycle_xact`): um lock de sessão que tenha vazado para o pooler sob o esquema
 * antigo não pode bloquear o novo; ele evapora quando o pooler recicla a conexão. A conexão é
 * obtida e devolvida manualmente (`pool.connect()`), e não por `PostgresService.transaction()`,
 * porque os workers do ciclo usam as OUTRAS conexões do pool — esta só segura o lock.
 *
 * ## Por que cadência diferente por worker, e não "roda tudo toda vez"
 *
 * Notificação e exclusão de conta precisam de latência baixa — é gente esperando um convite chegar
 * ou uma conta terminar de sair. Os dois cleaners de órfãos são o oposto: órfão é raro, e cada
 * varredura é uma listagem paga no Object Storage (§38). `claimDue` é um CAS atômico sobre
 * `server_metadata` — o mesmo tipo de linha que já guarda `migrations_applied_at` — que só deixa
 * o cleaner rodar quando já passou `*_CLEANUP_INTERVAL_MS` desde a última vez, reaproveitando os
 * mesmos dois valores de configuração que, em modo `interval`, seriam o período do `setInterval`.
 *
 * ## Heartbeat, tamanho do banco e frescor do DR (T18.3)
 *
 * Cada ciclo que conquista o lock grava início, fim, sucesso/falha e duração em `server_metadata`
 * — é o que `status()` lê e o que `maintenance_stale` usa para dizer "o Scheduler parou". Duas
 * observações de baixa cadência viajam no mesmo ciclo, pelo mesmo CAS dos cleaners:
 * `pg_database_size` classificado pelos limiares (`database-size.policy.ts`) e a idade do backup
 * de DR mais recente (`DrBackupStore.latestValid`). Nenhuma das duas roda a cada minuto, e uma
 * falha nelas é registrada sem derrubar o ciclo — notificação e exclusão de conta importam mais.
 */
@Injectable()
export class MaintenanceCoordinator {
  constructor(
    private readonly postgres: PostgresService,
    private readonly notificationDispatcher: NotificationDispatcher,
    private readonly accountDeletionReconciler: AccountDeletionReconciler,
    private readonly socialMediaCleaner: SocialMediaCleaner,
    private readonly backupPayloadCleaner: BackupPayloadCleaner,
    @Inject(OBJECT_STORAGE_CLIENT) private readonly objectStorage: ObjectStorageClient,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  async runCycle(): Promise<MaintenanceCycleResult> {
    const client = await this.postgres.pool.connect();
    let inTransaction = false;
    const startedAt = this.clock.now();
    try {
      await client.query('BEGIN');
      inTransaction = true;
      const { rows } = await client.query<{ locked: boolean }>(
        `SELECT pg_try_advisory_xact_lock(hashtext('spark_maintenance_cycle_xact')) AS locked`,
      );
      if (!(rows[0]?.locked ?? false)) {
        this.logger.info('maintenance.cycle.skipped_locked', {});
        return this.skippedResult();
      }

      await this.recordStarted(startedAt);
      this.logger.info('maintenance_started', { operation: 'maintenance_cycle' });

      try {
        let notificationsDispatched = false;
        if (this.config.socialPushEnabled) {
          await this.notificationDispatcher.runDispatchCycle();
          notificationsDispatched = true;
        }

        const accountDeletionJobsProcessed = await this.accountDeletionReconciler.processDueJobs();

        let socialMediaSweepRan = false;
        let socialMediaObjectsRemoved = 0;
        const mediaClaim = await this.claimDue(
          'social_media_cleanup',
          this.config.socialMediaCleanupIntervalMs,
        );
        if (mediaClaim !== null) {
          socialMediaObjectsRemoved = await this.withClaim(mediaClaim, () =>
            this.socialMediaCleaner.sweep(),
          );
          socialMediaSweepRan = true;
        }

        let backupPayloadSweepRan = false;
        let backupPayloadObjectsRemoved = 0;
        const backupClaim = await this.claimDue(
          'backup_payload_cleanup',
          this.config.backupPayloadCleanupIntervalMs,
        );
        if (backupClaim !== null) {
          backupPayloadObjectsRemoved = await this.withClaim(backupClaim, () =>
            this.backupPayloadCleaner.sweep(),
          );
          backupPayloadSweepRan = true;
        }

        let databaseSizeChecked = false;
        const sizeClaim = await this.claimDue(
          'database_size_check',
          this.config.databaseSizeCheckIntervalMs,
        );
        if (sizeClaim !== null) {
          await this.withClaim(sizeClaim, () => this.checkDatabaseSize());
          databaseSizeChecked = true;
        }

        let drBackupChecked = false;
        const drClaim = await this.claimDue('dr_backup_check', this.config.drBackupCheckIntervalMs);
        if (drClaim !== null) {
          await this.withClaim(drClaim, () => this.checkDrBackupFreshness());
          drBackupChecked = true;
        }

        const durationMs = this.clock.now() - startedAt;
        await this.recordCompleted(startedAt, durationMs);
        this.logger.info('maintenance_completed', {
          operation: 'maintenance_cycle',
          status: 'SUCCESS',
          durationMs,
          notificationsDispatched,
          accountDeletionJobsProcessed,
          socialMediaSweepRan,
          backupPayloadSweepRan,
          databaseSizeChecked,
          drBackupChecked,
        });
        // O evento histórico (T18.2) continua, para quem já filtra por ele.
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
          databaseSizeChecked,
          drBackupChecked,
          durationMs,
        };
      } catch (error) {
        const durationMs = this.clock.now() - startedAt;
        const errorName = error instanceof Error ? error.name : 'UNKNOWN';
        await this.recordFailed(durationMs, errorName).catch(() => undefined);
        this.logger.error('maintenance_failed', {
          operation: 'maintenance_cycle',
          status: 'FAILED',
          durationMs,
          errorName,
        });
        throw error;
      }
    } finally {
      // ROLLBACK encerra a transação que segura o lock (não há nada a commitar nela). Se nem isso
      // funcionar, a conexão está em estado desconhecido: sai do pool destruída, nunca reciclada.
      let releaseBroken = false;
      if (inTransaction) {
        await client.query('ROLLBACK').catch(() => {
          releaseBroken = true;
        });
      }
      client.release(releaseBroken || undefined);
    }
  }

  /**
   * Um ciclo que não conseguiu o lock. Enquanto o heartbeat está fresco, é só um retry do Scheduler
   * chegando cedo. Com o heartbeat velho, "alguém tem o lock" já dura mais que o aceitável: o ciclo
   * está preso (ou o lock vazou), e isso precisa sair do log `info` — vira `maintenance_locked_stale`
   * em `error`, e o controller devolve 503.
   */
  private async skippedResult(): Promise<MaintenanceCycleResult> {
    const status = await this.status().catch(() => null);
    if (!status?.stale) {
      return SKIPPED_RESULT;
    }
    const staleWhileLocked = { ageMs: status.ageMs, staleAfterMs: status.staleAfterMs };
    this.logger.error('maintenance_locked_stale', {
      operation: 'maintenance_cycle',
      status: 'SKIPPED',
      ...staleWhileLocked,
    });
    return { ...SKIPPED_RESULT, staleWhileLocked };
  }

  /** O heartbeat, lido de `server_metadata`. Nunca lança por ausência: "nunca rodou" é `stale`. */
  async status(): Promise<MaintenanceStatus> {
    const now = this.clock.now();
    const values = await this.readKeys(Object.values(KEYS));
    const num = (key: string): number | null => {
      const raw = values.get(key);
      if (raw === undefined) {
        return null;
      }
      const parsed = Number(raw);
      return Number.isFinite(parsed) ? parsed : null;
    };

    const lastSuccessAt = num(KEYS.successAt);
    const ageMs = lastSuccessAt === null ? null : Math.max(0, now - lastSuccessAt);
    const stale = ageMs === null || ageMs > this.config.maintenanceStaleAfterMs;

    const dbCheckedAt = num(KEYS.dbSizeCheckedAt);
    const dbSizeBytes = num(KEYS.dbSizeBytes);
    const dbLevel = values.get(KEYS.dbSizeLevel);

    const drCheckedAt = num(KEYS.drCheckedAt);
    const drLatestCreatedAt = num(KEYS.drLatestCreatedAt);
    const drLatestId = values.get(KEYS.drLatestId) ?? null;
    const drAgeMs = drLatestCreatedAt === null ? null : Math.max(0, now - drLatestCreatedAt);

    return {
      now,
      lastStartedAt: num(KEYS.startedAt),
      lastCompletedAt: num(KEYS.completedAt),
      lastSuccessAt,
      lastFailureAt: num(KEYS.failureAt),
      lastDurationMs: num(KEYS.durationMs),
      lastErrorName: values.get(KEYS.errorName) ?? null,
      staleAfterMs: this.config.maintenanceStaleAfterMs,
      ageMs,
      stale,
      databaseSize:
        dbCheckedAt !== null && dbSizeBytes !== null && dbLevel !== undefined
          ? { checkedAt: dbCheckedAt, sizeBytes: dbSizeBytes, level: dbLevel as DatabaseSizeLevel }
          : null,
      drBackup:
        drCheckedAt !== null
          ? {
              checkedAt: drCheckedAt,
              latestBackupId: drLatestId === '' ? null : drLatestId,
              latestCreatedAt: drLatestCreatedAt,
              ageMs: drAgeMs,
              maxAgeMs: this.config.drBackupMaxAgeMs,
              stale: drAgeMs === null || drAgeMs > this.config.drBackupMaxAgeMs,
            }
          : null,
    };
  }

  // ------------------------------------------------------------------ observações

  private async checkDatabaseSize(): Promise<void> {
    const now = this.clock.now();
    try {
      const { rows } = await this.postgres.query<{ bytes: string }>(
        'SELECT pg_database_size(current_database())::text AS bytes',
      );
      const sizeBytes = Number(rows[0]?.bytes ?? 0);
      const assessment = classifyDatabaseSize(sizeBytes, this.config.databaseSizeThresholdsMb);
      await this.writeKeys([
        [KEYS.dbSizeCheckedAt, String(now)],
        [KEYS.dbSizeBytes, String(assessment.sizeBytes)],
        [KEYS.dbSizeLevel, assessment.level],
      ]);
      const fields = {
        operation: 'database_size_check',
        databaseSizeBytes: assessment.sizeBytes,
        databaseSizeMb: assessment.sizeMb,
        level: assessment.level,
        threshold: assessment.thresholdMb,
        thresholdsMb: this.config.databaseSizeThresholdsMb,
      };
      this.logger.info('database_size_checked', fields);
      if (assessment.level !== 'NORMAL') {
        if (isDatabaseSizeAlerting(assessment.level)) {
          this.logger.error('database_size_threshold_exceeded', fields);
        } else {
          this.logger.warn('database_size_threshold_exceeded', fields);
        }
      }
    } catch (error) {
      this.logger.warn('database_size_check_failed', {
        operation: 'database_size_check',
        errorName: error instanceof Error ? error.name : 'UNKNOWN',
      });
    }
  }

  private async checkDrBackupFreshness(): Promise<void> {
    const now = this.clock.now();
    try {
      const latest = await new DrBackupStore(this.objectStorage).latestValid();
      const createdAt = latest?.manifest.createdAtEpochMs ?? null;
      const ageMs = createdAt === null ? null : Math.max(0, now - createdAt);
      const stale = ageMs === null || ageMs > this.config.drBackupMaxAgeMs;
      await this.writeKeys([
        [KEYS.drCheckedAt, String(now)],
        [KEYS.drLatestId, latest?.backupId ?? ''],
        [KEYS.drLatestCreatedAt, createdAt === null ? '' : String(createdAt)],
      ]);
      const fields = {
        operation: 'dr_backup_freshness_check',
        backupId: latest?.backupId ?? null,
        ageMs,
        maxAgeMs: this.config.drBackupMaxAgeMs,
        stale,
      };
      this.logger.info('db_backup_freshness_checked', fields);
      if (stale) {
        this.logger.error('db_backup_stale', fields);
      }
    } catch (error) {
      this.logger.warn('db_backup_freshness_check_failed', {
        operation: 'dr_backup_freshness_check',
        errorName: error instanceof Error ? error.name : 'UNKNOWN',
      });
    }
  }

  // ------------------------------------------------------------------ heartbeat

  private async recordStarted(startedAt: number): Promise<void> {
    const previous = await this.readKeys([KEYS.successAt]);
    const lastSuccess = Number(previous.get(KEYS.successAt));
    if (Number.isFinite(lastSuccess) && lastSuccess > 0) {
      const ageMs = startedAt - lastSuccess;
      if (ageMs > this.config.maintenanceStaleAfterMs) {
        // O ciclo voltou depois de um buraco: quem lê o log sabe quanto tempo ficou parado.
        this.logger.warn('maintenance_stale', {
          operation: 'maintenance_cycle',
          ageMs,
          staleAfterMs: this.config.maintenanceStaleAfterMs,
        });
      }
    }
    await this.writeKeys([[KEYS.startedAt, String(startedAt)]]);
  }

  private async recordCompleted(startedAt: number, durationMs: number): Promise<void> {
    const completedAt = startedAt + durationMs;
    await this.writeKeys([
      [KEYS.completedAt, String(completedAt)],
      [KEYS.successAt, String(completedAt)],
      [KEYS.durationMs, String(durationMs)],
      [KEYS.errorName, ''],
    ]);
  }

  private async recordFailed(durationMs: number, errorName: string): Promise<void> {
    const failedAt = this.clock.now();
    await this.writeKeys([
      [KEYS.completedAt, String(failedAt)],
      [KEYS.failureAt, String(failedAt)],
      [KEYS.durationMs, String(durationMs)],
      [KEYS.errorName, errorName],
    ]);
  }

  private async writeKeys(entries: readonly (readonly [string, string])[]): Promise<void> {
    const now = this.clock.now();
    for (const [key, value] of entries) {
      await this.postgres.query(
        `INSERT INTO server_metadata (key, value, updated_at) VALUES ($1, $2, $3)
         ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at`,
        [key, value, now],
      );
    }
  }

  private async readKeys(keys: readonly string[]): Promise<Map<string, string>> {
    const { rows } = await this.postgres.query<{ key: string; value: string }>(
      'SELECT key, value FROM server_metadata WHERE key = ANY($1::text[])',
      [keys as string[]],
    );
    const values = new Map<string, string>();
    for (const row of rows) {
      if (row.value !== '') {
        values.set(row.key, row.value);
      }
    }
    return values;
  }

  /**
   * Reivindica atomicamente o direito de rodar um worker de baixa cadência agora.
   *
   * `INSERT ... ON CONFLICT DO UPDATE ... WHERE` é a forma de CAS de uma linha só: sem linha
   * anterior, a reivindicação sempre vence (primeira execução); com linha anterior, só vence se já
   * passou `intervalMs` desde `updated_at`. Perder a corrida (`null`) significa "outro ciclo já
   * rodou este worker recentemente" — nunca um erro.
   *
   * A CTE `anterior` lê a linha **antes** do INSERT (todas as partes de uma instrução compartilham
   * o mesmo snapshot), e é o que permite devolver a janela quando o worker falha — ver
   * [withClaim]. Um round-trip só: reivindicar e saber o que havia antes é a mesma pergunta.
   */
  private async claimDue(key: string, intervalMs: number): Promise<DueClaim | null> {
    const now = this.clock.now();
    const { rows } = await this.postgres.query<{
      claimed: number;
      previous_updated_at: number | null;
    }>(
      `WITH anterior AS (
         SELECT updated_at FROM server_metadata WHERE key = $1
       ), reivindicacao AS (
         INSERT INTO server_metadata (key, value, updated_at)
         VALUES ($1, $2, $3)
         ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
         WHERE ($3 - server_metadata.updated_at) >= $4
         RETURNING 1
       )
       SELECT (SELECT count(*) FROM reivindicacao)::int AS claimed,
              (SELECT updated_at FROM anterior)         AS previous_updated_at`,
      [key, String(now), now, intervalMs],
    );
    const row = rows[0];
    if (row === undefined || row.claimed === 0) {
      return null;
    }
    return { key, previousUpdatedAt: row.previous_updated_at };
  }

  /**
   * Roda o worker reivindicado e, se ele **falhar**, devolve a janela (T18.3.2).
   *
   * `claimDue` consome a janela antes de o trabalho acontecer — é o que impede dois ciclos
   * simultâneos de varrer a mesma coisa. O efeito colateral era que uma varredura que falhava só
   * seria tentada de novo um intervalo inteiro depois: seis horas, no caso da limpeza de payloads
   * de backup. Restaurar o `updated_at` anterior faz o próximo ciclo tentar de novo imediatamente,
   * sem afrouxar a exclusão mútua enquanto o trabalho está em andamento.
   *
   * A devolução é best-effort: se ela falhar, quem manda é o erro original do worker.
   */
  private async withClaim<T>(claim: DueClaim, work: () => Promise<T>): Promise<T> {
    try {
      return await work();
    } catch (error) {
      await this.restoreClaim(claim).catch(() => undefined);
      throw error;
    }
  }

  private async restoreClaim(claim: DueClaim): Promise<void> {
    if (claim.previousUpdatedAt === null) {
      // Não havia linha antes: apagar devolve o estado exato de "nunca rodou".
      await this.postgres.query('DELETE FROM server_metadata WHERE key = $1', [claim.key]);
      return;
    }
    await this.postgres.query(
      'UPDATE server_metadata SET value = $2, updated_at = $3 WHERE key = $1',
      [claim.key, String(claim.previousUpdatedAt), claim.previousUpdatedAt],
    );
  }
}

/** Uma janela de cadência reivindicada, e o `updated_at` que havia antes dela. */
interface DueClaim {
  readonly key: string;
  readonly previousUpdatedAt: number | null;
}
