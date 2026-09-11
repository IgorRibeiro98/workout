import { Inject, Injectable, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import {
  OBJECT_STORAGE_ORPHAN_GRACE_MS,
  OBJECT_STORAGE_ORPHAN_SCAN_PAGES,
} from '../../object-storage/object-storage.limits';
import { BACKUP_PAYLOAD_STORE, type BackupPayloadStore } from './backup-payload.store';
import { BackupRepository } from './backup.repository';

/** Quantos objetos órfãos uma varredura remove, no máximo. Bounded, sempre. */
export const BACKUP_ORPHAN_BATCH = 100;

/**
 * A coleta de objetos órfãos de backup (T18.1 §30).
 *
 * ## De onde eles vêm
 *
 * O documento de um backup é gravado no Object Storage **antes** da metadata entrar no
 * PostgreSQL (`BackupService.create`), e apagado do Object Storage **depois** de a metadata sair
 * (retenção, exclusão de conta, migração legada). É a ordem que garante que nenhuma linha aponta
 * para um objeto inexistente — e o preço dela é que um processo morto entre os dois passos, ou
 * um `remove` que falhou, deixa um objeto sem linha. Ele não é alcançável pela API (não há
 * `backup_id` que o resolva), não custa nada além de armazenamento, e é isto aqui que o recolhe.
 *
 * ## O período de carência (§16)
 *
 * Um objeto sem linha também pode ser um backup **em andamento**: o objeto já subiu e a transação
 * ainda não commitou. Só é órfão o que não tem linha **e** é mais antigo que
 * [OBJECT_STORAGE_ORPHAN_GRACE_MS]. Um objeto recente nunca é removido só por ainda não estar no
 * banco.
 *
 * ## Bounded, e barato
 *
 * Uma página por vez, sob o prefixo `backups/`, com o banco consultado só pelas chaves daquela
 * página; no máximo [OBJECT_STORAGE_ORPHAN_SCAN_PAGES] páginas e [BACKUP_ORPHAN_BATCH] remoções
 * por varredura; o cursor sobrevive entre varreduras. O intervalo é longo
 * (`BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS`, 6 h por default): órfão de backup é raro, e cada
 * varredura é uma listagem paga no GCS — nada aqui roda a cada poucos segundos.
 */
@Injectable()
export class BackupPayloadCleaner implements OnModuleInit, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private isProcessing = false;
  /** Onde a varredura parou. Ausente = do começo do namespace. */
  private cursor?: string;

  constructor(
    private readonly repository: BackupRepository,
    @Inject(BACKUP_PAYLOAD_STORE) private readonly payloads: BackupPayloadStore,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  onModuleInit(): void {
    if (this.config.backgroundJobsMode === 'disabled') {
      // T18.2 §32 — em Cloud Run, `spark-maintenance` dispara `sweep()`, respeitando o mesmo
      // `BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS` como cadência mínima entre ciclos.
      this.logger.info('backup.storage.cleaner.disabled', {
        reason: 'BACKGROUND_JOBS_MODE=disabled',
      });
      return;
    }
    this.timer = setInterval(() => {
      void this.sweep();
    }, this.config.backupPayloadCleanupIntervalMs);
    this.timer.unref?.();
  }

  onApplicationShutdown(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  /**
   * Uma varredura. Devolve quantos objetos foram removidos — é o que o teste observa.
   *
   * Pública de propósito: o teste a chama diretamente, com um relógio injetado, em vez de esperar
   * o intervalo.
   */
  async sweep(): Promise<number> {
    if (this.isProcessing) {
      return 0;
    }
    this.isProcessing = true;
    try {
      return await this.collectOrphans();
    } catch (error) {
      // Manutenção que falha não derruba o processo: backup e restore continuam funcionando sem
      // ela. Só o nome do erro — nunca a chave nem o caminho.
      this.logger.warn('backup.storage.cleanup_failed', {
        error: error instanceof Error ? error.name : 'UNKNOWN',
      });
      return 0;
    } finally {
      this.isProcessing = false;
    }
  }

  private async collectOrphans(): Promise<number> {
    const now = this.clock.now();
    let removed = 0;
    let failed = 0;

    for (let pages = 0; pages < OBJECT_STORAGE_ORPHAN_SCAN_PAGES; pages += 1) {
      const page = await this.payloads.listObjects(this.cursor);
      // `createdAt === null` (T18.1.1 §9): o provider não provou a idade do objeto, e "não provado"
      // nunca vira "antigo o suficiente". Ele fica de fora desta varredura — não é apagado, e não é
      // reprocessado como se fosse recente: a próxima varredura o revê com o mesmo provider.
      const candidates = page.objects.filter(
        (object) =>
          object.createdAt !== null && now - object.createdAt >= OBJECT_STORAGE_ORPHAN_GRACE_MS,
      );
      const known = await this.repository.findExistingStorageKeys(
        candidates.map((object) => object.storageKey),
      );

      for (const object of candidates) {
        if (known.has(object.storageKey)) {
          continue;
        }
        if (removed + failed >= BACKUP_ORPHAN_BATCH) {
          break;
        }
        // `removed` só conta remoção que **de fato** aconteceu (T18.1.1 §4): uma falha aqui não
        // pode inflar a métrica com um objeto que continua no armazenamento.
        try {
          await this.payloads.remove(object.storageKey);
          removed += 1;
        } catch {
          failed += 1;
        }
      }

      this.cursor = page.nextPageToken;
      if (this.cursor === undefined || removed + failed >= BACKUP_ORPHAN_BATCH) {
        break;
      }
    }

    if (removed > 0 || failed > 0) {
      // Contagem, nunca chave (§41).
      this.logger.info('backup.storage.orphans_collected', { removed, failed });
    }
    return removed;
  }
}
