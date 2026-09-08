import { createHmac, randomUUID } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { AUTH_TOKEN_VERIFIER, type AuthTokenVerifier } from '../auth/auth-token-verifier';
import { AccountDeletionRepository } from './account-deletion.repository';
import type { AccountDeletionResponseDto } from './account-deletion.contract';

@Injectable()
export class AccountDeletionService {
  constructor(
    private readonly repo: AccountDeletionRepository,
    @Inject(AUTH_TOKEN_VERIFIER) private readonly authVerifier: AuthTokenVerifier,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
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
      this.repo.insertTombstone(randomUUID(), uidHash, now);
      this.repo.insertJob(jobId, uid, uidHash, now);
      this.repo.purgeAccountData(uid);
      this.appendTombstoneToFile(uidHash, now);

      this.logger.info('account.deletion.data_purged', {
        uidPrefix: uid.slice(0, 6),
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
  reconcileTombstones(tombstoneHashes: Set<string>): number {
    const ownerUids = this.repo.listAllOwnerUidsInDatabase();
    let purgedCount = 0;
    const now = this.clock.now();

    for (const uid of ownerUids) {
      const hash = this.hashUid(uid);
      if (tombstoneHashes.has(hash)) {
        this.repo.purgeAccountData(uid);
        this.repo.insertTombstone(randomUUID(), hash, now);
        purgedCount++;
        this.logger.info('account.deletion.dr_purged', {
          uidPrefix: uid.slice(0, 6),
        });
      }
    }

    return purgedCount;
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
