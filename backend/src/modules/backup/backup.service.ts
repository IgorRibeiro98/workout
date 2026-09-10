import { createHash, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { ObjectStorageUnavailableError } from '../../object-storage/object-storage.client';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { BACKUP_PAYLOAD_STORE, type BackupPayloadStore } from './backup-payload.store';
import type { BackupListResponse, BackupMetadataResponse } from './backup.contract';
import { BackupErrors } from './backup.errors';
import { BackupRateLimiter } from './backup.rate-limit';
import { BackupRepository, type StoredSnapshot } from './backup.repository';
import { validateBackupRequest } from './backup.validator';

/**
 * O caso de uso do backup (T16.4, Object Storage desde a T18.1).
 *
 * ```text
 * auth → tamanho → forma canônica → schema → item a item → relações → hash
 *      → idempotência → objeto no Object Storage → transação (metadata) → retenção
 * ```
 *
 * ## Ownership
 *
 * O dono é sempre `principal.uid`, que saiu de um Firebase ID Token verificado. O corpo da
 * requisição **não tem** campo de dono, e se tivesse seria ignorado: aceitar um `ownerUid` do
 * cliente "conferindo se bate com o token" já seria um caminho a mais para errar.
 *
 * ## A ordem entre o Object Storage e o PostgreSQL (T18.1 §25/§26)
 *
 * Não existe transação distribuída entre os dois, então a ordem é deliberada: o objeto é gravado
 * **antes** da metadata, e a metadata só é escrita depois de o objeto existir. O erro que essa
 * ordem impede é o pior dos dois — uma linha em `backup_snapshots` apontando para um documento
 * que nunca foi criado, que o restore descobriria como "backup corrompido". O erro que ela
 * permite é o barato: um objeto sem linha, quando o processo morre entre os dois passos. Esse
 * objeto é órfão, e a coleta de órfãos (`BackupPayloadCleaner`) o recolhe depois da carência.
 *
 * ## O que este serviço não faz
 *
 * Não interpreta treino, não mescla, não resolve conflito. Ele guarda um snapshot imutável,
 * devolve metadata e, no restore, devolve o documento **byte a byte** — depois de conferir que
 * ele ainda é o que a metadata descreve.
 */
@Injectable()
export class BackupService {
  constructor(
    private readonly repository: BackupRepository,
    @Inject(BACKUP_PAYLOAD_STORE) private readonly payloads: BackupPayloadStore,
    private readonly logger: SparkLogger,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly rateLimiter: BackupRateLimiter,
  ) {}

  /**
   * Os tetos por conta (T16.8 §84).
   *
   * Antes de qualquer validação ou leitura: recusar cedo é o ponto de um limite. Escrita e leitura
   * têm janelas separadas — ver `backup.limits.ts` para o porquê dos números.
   */
  private assertWithinWriteLimit(uid: string): void {
    if (!this.rateLimiter.tryAcquireWrite(uid)) {
      throw BackupErrors.rateLimited();
    }
  }

  private assertWithinReadLimit(uid: string): void {
    if (!this.rateLimiter.tryAcquireRead(uid)) {
      throw BackupErrors.rateLimited();
    }
  }

  /**
   * Cria — ou reconhece — o backup daquela tentativa lógica.
   *
   * [created] distingue `201` de `200`: um reenvio depois de resposta perdida não é um backup novo,
   * e dizer que é confundiria o cliente sobre quantos snapshots existem.
   */
  async create(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawBody: string,
  ): Promise<{ created: boolean; metadata: BackupMetadataResponse }> {
    this.assertWithinWriteLimit(principal.uid);

    const startedAt = Date.now();
    const snapshot = validateBackupRequest(rawBody);

    const existing = await this.repository.findByClientBackupId(
      principal.uid,
      snapshot.clientBackupId,
    );
    if (existing) {
      return this.replayOrConflict(existing, snapshot.payloadHash, requestId, principal.uid);
    }

    // A identidade nasce no servidor, e a chave do objeto deriva **dela** — nunca de
    // `clientBackupId`, `deviceId` ou de qualquer valor que o cliente escolha (§21).
    const backupId = randomUUID();
    const storageKey = this.payloads.newStorageKey(backupId);

    // Os mesmos bytes que o hash resume (§22/§23): `payloadHash` e `sizeBytes` foram calculados
    // sobre este texto pelo validador, e é este texto que sobe — sem reserializar.
    const bytes = Buffer.from(snapshot.canonicalText, 'utf8');

    try {
      await this.payloads.write(storageKey, bytes, { sha256: snapshot.payloadHash });
    } catch (error) {
      // Nenhuma linha foi escrita: a tentativa continua pendente no aparelho, e o reenvio com o
      // mesmo `clientBackupId` é idempotente. `503`, para que o cliente saiba que é "depois", e
      // não "nunca".
      if (error instanceof ObjectStorageUnavailableError) {
        this.logger.warn('backup.storage.write_failed', {
          requestId,
          uidPrefix: uidPrefix(principal.uid),
          sizeBytes: bytes.length,
          durationMs: Date.now() - startedAt,
        });
        throw BackupErrors.storageUnavailable();
      }
      throw error;
    }

    let stored: StoredSnapshot;
    try {
      stored = await this.repository.insert(principal.uid, snapshot, Date.now(), {
        backupId,
        storageKey,
      });
    } catch (err: unknown) {
      // O objeto já existe e a metadata não vai existir: apagar agora é o caminho barato. Se a
      // remoção também falhar, o objeto é órfão — e a coleta de órfãos o recolhe (§26).
      await this.payloads.remove(storageKey).catch(() => {
        this.logger.warn('backup.storage.orphan_left', {
          requestId,
          uidPrefix: uidPrefix(principal.uid),
        });
      });

      const code = (err as { code?: string })?.code;
      if (code === '23505') {
        // Corrida entre duas requisições da mesma tentativa (§27): quem chegou primeiro ao
        // `UNIQUE (owner_uid, client_backup_id)` venceu, e só o objeto dele permanece. Este
        // perdedor já apagou o seu acima e responde a partir do vencedor.
        const concurrent = await this.repository.findByClientBackupId(
          principal.uid,
          snapshot.clientBackupId,
        );
        if (concurrent) {
          return this.replayOrConflict(concurrent, snapshot.payloadHash, requestId, principal.uid);
        }
      }
      throw err;
    }

    this.logger.info('backup.created', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      clientBackupId: stored.clientBackupId,
      backupId: stored.backupId,
      itemCount: stored.itemCount,
      sizeBytes: stored.sizeBytes,
      durationMs: Date.now() - startedAt,
    });

    await this.pruneAfterCommit(principal.uid, requestId);

    return { created: true, metadata: metadataOf(stored) };
  }

  /**
   * A tentativa já existe: mesmo conteúdo é replay; conteúdo outro é conflito (§27).
   */
  private replayOrConflict(
    existing: StoredSnapshot,
    payloadHash: string,
    requestId: string,
    uid: string,
  ): { created: boolean; metadata: BackupMetadataResponse } {
    if (existing.payloadHash !== payloadHash) {
      // Mesma tentativa, conteúdo outro. Aceitar apagaria em silêncio o que a primeira
      // significava; recusar deixa o cliente criar uma tentativa nova, que é o correto.
      this.logger.warn('backup.idempotency.conflict', {
        requestId,
        uidPrefix: uidPrefix(uid),
        clientBackupId: existing.clientBackupId,
      });
      throw BackupErrors.idempotencyConflict();
    }
    this.logger.info('backup.replayed', {
      requestId,
      uidPrefix: uidPrefix(uid),
      clientBackupId: existing.clientBackupId,
      backupId: existing.backupId,
    });
    return { created: false, metadata: metadataOf(existing) };
  }

  /** A metadata do backup mais recente da conta autenticada. Nunca de outra. */
  async latest(principal: AuthenticatedPrincipal): Promise<BackupMetadataResponse> {
    this.assertWithinReadLimit(principal.uid);
    const latest = await this.repository.findLatest(principal.uid);
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
  async list(principal: AuthenticatedPrincipal): Promise<BackupListResponse> {
    this.assertWithinReadLimit(principal.uid);
    const list = await this.repository.listFor(principal.uid);
    return { items: list.map(metadataOf) };
  }

  /**
   * A metadata de **um** backup da conta autenticada.
   *
   * O `backupId` de outra conta responde exatamente como um inexistente: `404`. Distinguir os dois
   * transformaria este endpoint em um oráculo de "este backup existe em alguma conta".
   */
  async metadata(
    principal: AuthenticatedPrincipal,
    backupId: string,
  ): Promise<BackupMetadataResponse> {
    this.assertWithinReadLimit(principal.uid);
    const stored = await this.repository.findByBackupId(principal.uid, backupId);
    if (!stored) {
      throw BackupErrors.notFound();
    }
    return metadataOf(stored);
  }

  /**
   * O documento canônico do snapshot, verbatim, para o restore — **verificado** (T18.1 §24).
   *
   * Antes de qualquer byte sair, o documento lido é conferido contra a metadata: `size_bytes` e
   * `payload_hash`. Um objeto ausente, truncado ou alterado responde `BACKUP_CONTENT_UNAVAILABLE`,
   * nunca um JSON pela metade — o Android conferiria o hash e recusaria de qualquer jeito, mas
   * dizer a verdade aqui é mais barato do que fazê-lo baixar 4 MiB para descobrir.
   */
  async content(
    principal: AuthenticatedPrincipal,
    requestId: string,
    backupId: string,
  ): Promise<Buffer> {
    this.assertWithinReadLimit(principal.uid);
    const stored = await this.repository.findByBackupId(principal.uid, backupId);
    if (!stored) {
      // Metadata de log: quem pediu e o quê. Nunca o conteúdo, nunca o `backupId` de outra conta
      // resolvido para um dono.
      this.logger.info('backup.content.not_found', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw BackupErrors.notFound();
    }

    const bytes = await this.readDocument(stored, requestId);
    if (bytes === null) {
      this.logger.warn('backup.content.unavailable', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
        backupId: stored.backupId,
      });
      throw BackupErrors.contentUnavailable();
    }

    if (bytes.length !== stored.sizeBytes || sha256OfBytes(bytes) !== stored.payloadHash) {
      // Metadata operacional, e só: nunca o conteúdo, nunca a chave do objeto (§41).
      this.logger.error('backup.content.integrity_failed', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
        backupId: stored.backupId,
        expectedBytes: stored.sizeBytes,
        actualBytes: bytes.length,
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
    return bytes;
  }

  /**
   * De onde o documento vem (T18.1 §31):
   *
   * ```text
   * storage_key presente              → Object Storage
   * storage_key ausente + payload     → o texto legado da T16.5, no PostgreSQL
   * nenhum dos dois                   → não há documento (anterior à T16.5)
   * ```
   *
   * Só a ausência vira `null`. O Object Storage fora do ar sobe como `503`: é "tente de novo", e
   * não "este backup não pode ser restaurado".
   */
  private async readDocument(stored: StoredSnapshot, requestId: string): Promise<Buffer | null> {
    const source = await this.repository.findPayloadSource(stored.ownerUid, stored.backupId);
    if (!source) {
      return null;
    }
    if (source.storageKey !== null) {
      try {
        return await this.payloads.read(source.storageKey);
      } catch (error) {
        if (error instanceof ObjectStorageUnavailableError) {
          this.logger.warn('backup.storage.read_failed', {
            requestId,
            uidPrefix: uidPrefix(stored.ownerUid),
            backupId: stored.backupId,
          });
          throw BackupErrors.storageUnavailable();
        }
        throw error;
      }
    }
    if (source.legacyPayload !== null) {
      return Buffer.from(source.legacyPayload, 'utf8');
    }
    return null;
  }

  /**
   * A retenção, **depois** do commit do backup novo (T16.4 §10, T18.1 §28/§29).
   *
   * Primeiro a metadata sai do banco — é o commit que decide que aquele snapshot deixou de
   * existir —, e só então os objetos são apagados. Um objeto que resista fica inacessível pela
   * API (não há mais linha apontando para ele) e vira órfão, que a coleta recolhe depois. A
   * retenção nunca é desfeita por causa disso: desfazê-la deixaria backup a mais, o que é
   * aceitável, mas apagar o objeto antes do commit deixaria metadata apontando para o nada.
   */
  private async pruneAfterCommit(ownerUid: string, requestId: string): Promise<void> {
    const keep = this.config.backupRetentionCount;
    try {
      const pruned = await this.repository.pruneOlderThan(ownerUid, keep);
      if (pruned.count === 0) {
        return;
      }
      let objectsRemoved = 0;
      let objectsLeft = 0;
      for (const storageKey of pruned.storageKeys) {
        try {
          await this.payloads.remove(storageKey);
          objectsRemoved += 1;
        } catch {
          objectsLeft += 1;
        }
      }
      this.logger.info('backup.retention.pruned', {
        requestId,
        uidPrefix: uidPrefix(ownerUid),
        removed: pruned.count,
        keep,
        objectsRemoved,
        objectsLeft,
      });
    } catch (error) {
      this.logger.error('backup.retention.failed', {
        requestId,
        uidPrefix: uidPrefix(ownerUid),
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
    }
  }
}

/**
 * SHA-256 dos **bytes** lidos — não de uma string decodificada e reencodada. Um objeto com UTF-8
 * inválido precisa reprovar aqui, e uma decodificação com substituição esconderia isso.
 */
function sha256OfBytes(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
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
