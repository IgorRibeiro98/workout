import { createHmac, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { AUTH_TOKEN_VERIFIER, type AuthTokenVerifier } from '../auth/auth-token-verifier';
import { SOCIAL_MEDIA_STORE, type SocialMediaStore } from '../social/social-media.store';
import { AccountDeletionRepository, type StoredDeletionJob } from './account-deletion.repository';
import { DeletionTombstoneLedger } from './deletion-tombstone.ledger';
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
    // T17.13.1 §8 — o registro anti-ressurreição deixou de ser um `appendFileSync` best-effort e
    // virou um colaborador que **falha**. Ver `deletion-tombstone.ledger.ts`.
    private readonly ledger: DeletionTombstoneLedger,
    private readonly logger: SparkLogger,
  ) {}

  /** Calcula hash irreversível HMAC-SHA256 para o Firebase UID. */
  hashUid(uid: string): string {
    return createHmac('sha256', this.config.accountDeletionHmacKey).update(uid).digest('hex');
  }

  /** Confere se o UID já foi excluído anteriormente (barreira de autenticação). */
  async isAccountDeleted(uid: string): Promise<boolean> {
    const hash = this.hashUid(uid);
    return this.repo.isTombstoned(hash);
  }

  /**
   * Executa a exclusão de conta de ponta a ponta (T17.13.1 §5–§11).
   */
  async deleteAccount(uid: string): Promise<AccountDeletionResponseDto> {
    const uidHash = this.hashUid(uid);
    const now = this.clock.now();
    const isAlreadyTombstoned = await this.repo.isTombstoned(uidHash);
    const existingJob = await this.repo.findJobByFirebaseUid(uid);

    // Sem tombstone pendente e sem job: já terminou. Uma segunda chamada converge (§64).
    if (isAlreadyTombstoned && !existingJob) {
      return { status: 'DELETED' };
    }

    if (!isAlreadyTombstoned) {
      // §6 — as chaves saem **antes** do purge. Depois dele as linhas não existem mais, e os
      // arquivos ficariam órfãos no disco de um servidor que afirma ter apagado tudo.
      const mediaKeys = await this.repo.listMediaStorageKeys(uid);

      // §5 — tombstone, job e purge são uma transação só.
      await this.repo.beginAccountDeletion({
        firebaseUid: uid,
        uidHash,
        tombstoneId: randomUUID(),
        jobId: randomUUID(),
        now,
      });

      const removedFiles = await this.purgeMediaFiles(mediaKeys);

      this.logger.info('account.deletion.data_purged', {
        uidPrefix: uid.slice(0, 6),
        mediaFilesRemoved: removedFiles,
      });
    }

    const job = await this.repo.findJobByFirebaseUid(uid);
    if (!job) {
      // Só acontece se outra execução concorrente terminou a exclusão entre as duas leituras.
      return { status: 'DELETED' };
    }

    try {
      await this.advanceJob(job, now);
      this.logger.info('account.deletion.completed', { uidPrefix: uid.slice(0, 6) });
      return { status: 'DELETED' };
    } catch (error: unknown) {
      const currentJob = await this.repo.findJobByFirebaseUid(uid);
      this.logger.warn('account.deletion.pending', {
        uidPrefix: uid.slice(0, 6),
        phase: currentJob?.phase ?? 'unknown',
        error: error instanceof Error ? error.message : String(error),
      });
      return { status: 'DELETION_PENDING' };
    }
  }

  /**
   * Executa os passos que ainda faltam para um job, na ordem, e remove o job quando terminam.
   */
  async advanceJob(job: StoredDeletionJob, now: number): Promise<void> {
    let current = job;

    if (current.phase === 'LEDGER_PENDING') {
      // §10 — a falha propaga. Este `append` é o registro anti-ressurreição obrigatório, e sem ele
      // a exclusão não pode ser declarada terminada.
      this.ledger.appendDurably(current.uid_hash, now);
      await this.repo.updateJobPhase(current.id, 'FIREBASE_PENDING', now);
      current = { ...current, phase: 'FIREBASE_PENDING' };
      this.logger.info('account.deletion.ledger_persisted', {
        uidPrefix: current.firebase_uid.slice(0, 6),
      });
    }

    // `deleteUser` converge quando o usuário já não existe no Firebase (`auth/user-not-found` é
    // tratado como sucesso pelo verificador). Um provedor ausente — o `deleteUser` opcional não
    // implementado — também converge: não há o que apagar.
    if (this.authVerifier.deleteUser) {
      await this.authVerifier.deleteUser(current.firebase_uid);
    }

    // A chave estável é o uid, e nunca o `id` do job (T17.10 §83).
    await this.repo.deleteJobByFirebaseUid(current.firebase_uid);
  }

  /** Consulta o status da exclusão para retry ou reconciliação. */
  async getDeletionStatus(uid: string): Promise<AccountDeletionResponseDto> {
    const uidHash = this.hashUid(uid);
    if (!(await this.repo.isTombstoned(uidHash))) {
      return { status: 'DELETION_PENDING' };
    }
    if (await this.repo.hasPendingJob(uid)) {
      return { status: 'DELETION_PENDING' };
    }
    return { status: 'DELETED' };
  }

  /**
   * Reconcilia tombstones em caso de restauração de backup antigo (Disaster Recovery).
   * Varre o banco restaurado e expurga qualquer conta cujo HMAC coincida com a lista de tombstones.
   */
  async reconcileTombstones(tombstoneHashes: Set<string>): Promise<number> {
    const ownerUids = await this.repo.listAllOwnerUidsInDatabase();
    let purgedCount = 0;
    const now = this.clock.now();

    for (const uid of ownerUids) {
      const hash = this.hashUid(uid);
      if (tombstoneHashes.has(hash)) {
        const mediaKeys = await this.repo.listMediaStorageKeys(uid);
        await this.repo.purgeAccountData(uid);
        await this.repo.insertTombstone(randomUUID(), hash, now);
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
}
