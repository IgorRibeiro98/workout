import { Inject, Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type { BackupListResponse, BackupMetadataResponse } from './backup.contract';
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
   * Os backups retidos da conta autenticada — a descoberta do restore (T16.5).
   *
   * Metadata, na ordem do servidor. Nenhum snapshot é lido, e uma conta sem backup recebe uma
   * lista vazia (`200`), não `404`: "você ainda não tem backup" é um estado normal da tela, e não
   * um erro a tratar.
   */
  list(principal: AuthenticatedPrincipal): BackupListResponse {
    return { items: this.repository.listFor(principal.uid).map(metadataOf) };
  }

  /**
   * A metadata de **um** backup da conta autenticada.
   *
   * O `backupId` de outra conta responde exatamente como um inexistente: `404`. Distinguir os dois
   * transformaria este endpoint em um oráculo de "este backup existe em alguma conta".
   */
  metadata(principal: AuthenticatedPrincipal, backupId: string): BackupMetadataResponse {
    const stored = this.repository.findByBackupId(principal.uid, backupId);
    if (!stored) {
      throw BackupErrors.notFound();
    }
    return metadataOf(stored);
  }

  /**
   * O documento canônico do snapshot, verbatim, para o restore.
   *
   * ## O que este método não faz
   *
   * Não marca, não consome, não move e não apaga nada: baixar um backup é leitura pura, e o
   * snapshot continua imutável e disponível enquanto a retenção o mantiver. Restaurar não gasta o
   * backup.
   *
   * Devolve o texto **exato** que produziu [BackupMetadataResponse.payloadHash]. O Android
   * recalcula o SHA-256 sobre o que recebeu e compara com a metadata; qualquer diferença — no
   * caminho, no servidor ou no disco — vira recusa lá, antes de qualquer escrita local.
   */
  content(principal: AuthenticatedPrincipal, requestId: string, backupId: string): string {
    const stored = this.repository.findByBackupId(principal.uid, backupId);
    if (!stored) {
      // Metadata de log: quem pediu e o quê. Nunca o conteúdo, nunca o `backupId` de outra conta
      // resolvido para um dono.
      this.logger.info('backup.content.not_found', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw BackupErrors.notFound();
    }

    const payload = this.repository.findPayload(principal.uid, backupId);
    if (payload === null) {
      this.logger.warn('backup.content.unavailable', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
        backupId: stored.backupId,
      });
      throw BackupErrors.contentUnavailable();
    }

    this.logger.info('backup.content.served', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      backupId: stored.backupId,
      itemCount: stored.itemCount,
      sizeBytes: stored.sizeBytes,
    });
    return payload;
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
