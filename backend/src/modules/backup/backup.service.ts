import { Inject, Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type { BackupMetadataResponse } from './backup.contract';
import { BackupErrors } from './backup.errors';
import { BackupRepository, type StoredSnapshot } from './backup.repository';
import { validateBackupRequest } from './backup.validator';

/**
 * O caso de uso do backup (T16.4).
 *
 * ```text
 * auth → tamanho → forma canônica → schema → item a item → relações → hash
 *      → idempotência → transação → retenção
 * ```
 *
 * ## Ownership
 *
 * O dono é sempre `principal.uid`, que saiu de um Firebase ID Token verificado. O corpo da
 * requisição **não tem** campo de dono, e se tivesse seria ignorado: aceitar um `ownerUid` do
 * cliente "conferindo se bate com o token" já seria um caminho a mais para errar.
 *
 * ## O que este serviço não faz
 *
 * Não devolve conteúdo, não baixa, não mescla, não resolve conflito e não interpreta treino. Ele
 * guarda um snapshot imutável e devolve metadata. Restore é T16.5.
 */
@Injectable()
export class BackupService {
  constructor(
    private readonly repository: BackupRepository,
    private readonly logger: SparkLogger,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  /**
   * Cria — ou reconhece — o backup daquela tentativa lógica.
   *
   * [created] distingue `201` de `200`: um reenvio depois de resposta perdida não é um backup novo,
   * e dizer que é confundiria o cliente sobre quantos snapshots existem.
   */
  create(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawBody: string,
  ): { created: boolean; metadata: BackupMetadataResponse } {
    const startedAt = Date.now();
    const snapshot = validateBackupRequest(rawBody);

    const existing = this.repository.findByClientBackupId(principal.uid, snapshot.clientBackupId);
    if (existing) {
      if (existing.payloadHash !== snapshot.payloadHash) {
        // Mesma tentativa, conteúdo outro. Aceitar apagaria em silêncio o que a primeira
        // significava; recusar deixa o cliente criar uma tentativa nova, que é o correto.
        this.logger.warn('backup.idempotency.conflict', {
          requestId,
          uidPrefix: uidPrefix(principal.uid),
          clientBackupId: snapshot.clientBackupId,
        });
        throw BackupErrors.idempotencyConflict();
      }
      this.logger.info('backup.replayed', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
        clientBackupId: snapshot.clientBackupId,
        backupId: existing.backupId,
      });
      return { created: false, metadata: metadataOf(existing) };
    }

    const stored = this.repository.insert(principal.uid, snapshot, Date.now());

    this.logger.info('backup.created', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      clientBackupId: stored.clientBackupId,
      backupId: stored.backupId,
      itemCount: stored.itemCount,
      sizeBytes: stored.sizeBytes,
      durationMs: Date.now() - startedAt,
    });

    this.pruneAfterCommit(principal.uid, requestId);

    return { created: true, metadata: metadataOf(stored) };
  }

  /** A metadata do backup mais recente da conta autenticada. Nunca de outra. */
  latest(principal: AuthenticatedPrincipal): BackupMetadataResponse {
    const latest = this.repository.findLatest(principal.uid);
    if (!latest) {
      throw BackupErrors.notFound();
    }
    return metadataOf(latest);
  }

  /**
   * A retenção, **depois** do commit do backup novo.
   *
   * Separada da transação de escrita de propósito: se a limpeza falhar, o backup recém-criado
   * continua válido e o problema vira "limpeza pendente", não "usuário sem backup". A ordem
   * inversa — apagar antes de gravar — é a que produz perda de dado.
   */
  private pruneAfterCommit(ownerUid: string, requestId: string): void {
    const keep = this.config.backupRetentionCount;
    try {
      const removed = this.repository.pruneOlderThan(ownerUid, keep);
      if (removed > 0) {
        this.logger.info('backup.retention.pruned', {
          requestId,
          uidPrefix: uidPrefix(ownerUid),
          removed,
          keep,
        });
      }
    } catch (error) {
      this.logger.error('backup.retention.failed', {
        requestId,
        uidPrefix: uidPrefix(ownerUid),
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
    }
  }
}

function metadataOf(stored: StoredSnapshot): BackupMetadataResponse {
  return {
    backupId: stored.backupId,
    clientBackupId: stored.clientBackupId,
    backupSchemaVersion: stored.backupSchemaVersion,
    createdAt: stored.createdAt,
    itemCount: stored.itemCount,
    sizeBytes: stored.sizeBytes,
    payloadHash: stored.payloadHash,
  };
}
