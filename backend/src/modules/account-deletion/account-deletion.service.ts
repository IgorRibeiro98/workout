import { createHmac, randomUUID } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { AUTH_TOKEN_VERIFIER, type AuthTokenVerifier } from '../auth/auth-token-verifier';
import { SOCIAL_MEDIA_STORE, type SocialMediaStore } from '../social/social-media.store';
import { AccountDeletionRepository } from './account-deletion.repository';
import type { AccountDeletionResponseDto } from './account-deletion.contract';

@Injectable()
export class AccountDeletionService {
  constructor(
    private readonly repo: AccountDeletionRepository,
    @Inject(AUTH_TOKEN_VERIFIER) private readonly authVerifier: AuthTokenVerifier,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    // T17.9 §114 — o purge do SQLite não alcança o sistema de arquivos. As fotos da conta
    // excluída precisam sair do disco, e é este colaborador que faz isso.
    @Inject(SOCIAL_MEDIA_STORE) private readonly mediaStore: SocialMediaStore,
    private readonly logger: SparkLogger,
  ) {}

  /** Calcula hash irreversível HMAC-SHA256 para o Firebase UID. */
  hashUid(uid: string): string {
    return createHmac('sha256', this.config.accountDeletionHmacKey).update(uid).digest('hex');
  }

  /** Confere se o UID já foi excluído anteriormente (barreira de autenticação). */
  isAccountDeleted(uid: string): boolean {
    const hash = this.hashUid(uid);
    return this.repo.isTombstoned(hash);
  }

  /**
   * Executa a exclusão de conta de ponta a ponta:
   * 1. Transação SQLite atômica (purga dados das 21 tabelas e grava tombstone);
   * 2. Persiste tombstone no arquivo de DR (anti-ressurreição);
   * 3. Exclui a conta no Firebase Auth via Firebase Admin SDK.
   */
  async deleteAccount(uid: string): Promise<AccountDeletionResponseDto> {
    const uidHash = this.hashUid(uid);
    const now = this.clock.now();
    const isAlreadyTombstoned = this.repo.isTombstoned(uidHash);
    const hasPendingJob = this.repo.hasPendingJob(uid);

    if (isAlreadyTombstoned && !hasPendingJob) {
      return { status: 'DELETED' };
    }

    const jobId = randomUUID();
    if (!isAlreadyTombstoned) {
      // §114 — as chaves saem **antes** do purge. Depois dele as linhas não existem mais, e os
      // arquivos ficariam órfãos no disco de um servidor que afirma ter apagado tudo.
      const mediaKeys = this.repo.listMediaStorageKeys(uid);

      this.repo.insertTombstone(randomUUID(), uidHash, now);
      this.repo.insertJob(jobId, uid, uidHash, now);
      this.repo.purgeAccountData(uid);
      this.appendTombstoneToFile(uidHash, now);

      // Os arquivos saem depois do commit: uma falha de I/O aqui não pode desfazer o purge do
      // banco, que é a parte que revoga o acesso. O que sobrar de arquivo é recolhido pela
      // varredura de órfãos (§140), porque a metadata correspondente já não existe.
      const removedFiles = await this.purgeMediaFiles(mediaKeys);

      this.logger.info('account.deletion.data_purged', {
        uidPrefix: uid.slice(0, 6),
        mediaFilesRemoved: removedFiles,
      });
    }

    // Tenta exclusão imediata no Firebase Admin
    try {
      if (this.authVerifier.deleteUser) {
        await this.authVerifier.deleteUser(uid);
      }
      this.repo.deleteJob(jobId);
      this.logger.info('account.deletion.completed', {
        uidPrefix: uid.slice(0, 6),
      });
      return { status: 'DELETED' };
    } catch (error: unknown) {
      this.logger.warn('account.deletion.firebase_pending', {
        uidPrefix: uid.slice(0, 6),
        error: error instanceof Error ? error.message : String(error),
      });
      return { status: 'DELETION_PENDING' };
    }
  }

  /** Consulta o status da exclusão para retry ou reconciliação. */
  getDeletionStatus(uid: string): AccountDeletionResponseDto {
    const uidHash = this.hashUid(uid);
    if (!this.repo.isTombstoned(uidHash)) {
      return { status: 'DELETION_PENDING' };
    }
    if (this.repo.hasPendingJob(uid)) {
      return { status: 'DELETION_PENDING' };
    }
    return { status: 'DELETED' };
  }

  /**
   * Reconcilia tombstones em caso de restauração de backup antigo (Disaster Recovery).
   * Varre o banco restaurado e expurga qualquer conta cujo HMAC coincida com a lista de tombstones.
   */
  async reconcileTombstones(tombstoneHashes: Set<string>): Promise<number> {
    const ownerUids = this.repo.listAllOwnerUidsInDatabase();
    let purgedCount = 0;
    const now = this.clock.now();

    for (const uid of ownerUids) {
      const hash = this.hashUid(uid);
      if (tombstoneHashes.has(hash)) {
        // §139/§178 — um restore antigo pode trazer de volta o banco **e** a mídia de uma conta já
        // excluída. Reconciliar significa apagar os dois: purgar só o SQLite deixaria as fotos
        // ressuscitadas no disco, sem metadata que as revogue e sem nada que as recolha além da
        // varredura de órfãos — que levaria a fazer, mas por acidente e não por política.
        const mediaKeys = this.repo.listMediaStorageKeys(uid);
        this.repo.purgeAccountData(uid);
        this.repo.insertTombstone(randomUUID(), hash, now);
        const removedFiles = await this.purgeMediaFiles(mediaKeys);
        purgedCount++;
        this.logger.info('account.deletion.dr_purged', {
          uidPrefix: uid.slice(0, 6),
          mediaFilesRemoved: removedFiles,
        });
      }
    }

    return purgedCount;
  }

  /**
   * Apaga os arquivos de mídia de uma conta excluída (§114/§139).
   *
   * Uma falha por arquivo não interrompe o laço, e não derruba a exclusão: o banco já foi purgado,
   * o acesso já foi revogado, e um arquivo que resistiu é recolhido pela varredura de órfãos
   * (§140) — porque a metadata dele já não existe. Interromper aqui deixaria os arquivos
   * **seguintes** no disco, que é o oposto do que se quer.
   */
  private async purgeMediaFiles(storageKeys: readonly string[]): Promise<number> {
    let removed = 0;
    for (const key of storageKeys) {
      try {
        await this.mediaStore.remove(key);
        removed += 1;
      } catch {
        // Sem o caminho na mensagem (§161). A varredura de órfãos recolhe o que sobrar.
      }
    }
    return removed;
  }

  private appendTombstoneToFile(uidHash: string, deletedAt: number): void {
    try {
      const filePath = this.config.deletionTombstonesFilePath;
      const dir = dirname(filePath);
      if (!existsSync(dir)) {
        mkdirSync(dir, { recursive: true });
      }
      appendFileSync(filePath, `${uidHash}\t${deletedAt}\n`, 'utf8');
    } catch {
      // Falha de escrita no arquivo local não aborta o purge do banco
    }
  }
}
