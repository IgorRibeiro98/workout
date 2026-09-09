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
  isAccountDeleted(uid: string): boolean {
    const hash = this.hashUid(uid);
    return this.repo.isTombstoned(hash);
  }

  /**
   * Executa a exclusão de conta de ponta a ponta (T17.13.1 §5–§11).
   *
   * ```text
   * lê as chaves de mídia          (antes do purge: depois, as linhas não existem mais)
   *         │
   *   BEGIN │ tombstone + job(LEDGER_PENDING) + purge das tabelas account-scoped
   *  COMMIT │ ← a partir daqui a conta está inacessível, e isso é irreversível
   *         │
   *  apaga os arquivos de mídia    (fora da transação: I/O de disco não segura o SQLite)
   *         │
   *  persiste o ledger de DR       (durável, com fsync — falha aqui ⇒ DELETION_PENDING)
   *         │
   *  apaga o usuário no Firebase   (falha aqui ⇒ DELETION_PENDING)
   *         │
   *  remove o job                  ⇒ DELETED
   * ```
   *
   * ## A regra que ordena tudo isso (§9)
   *
   * Depois que o purge é committed, **os dados nunca voltam** — nem se o sistema de arquivos
   * falhar, nem se o Firebase estiver fora do ar. O que ainda pode acontecer é a exclusão não ser
   * declarada *terminada*: enquanto o registro anti-DR obrigatório não estiver no disco, a
   * resposta é `DELETION_PENDING` e o job continua na tabela, com a conta bloqueada pelo tombstone
   * do banco.
   *
   * Dizer `DELETED` sem o ledger persistido seria uma promessa que o servidor não pode cumprir: é
   * exatamente o restore posterior que traria a conta de volta.
   */
  async deleteAccount(uid: string): Promise<AccountDeletionResponseDto> {
    const uidHash = this.hashUid(uid);
    const now = this.clock.now();
    const isAlreadyTombstoned = this.repo.isTombstoned(uidHash);
    const existingJob = this.repo.findJobByFirebaseUid(uid);

    // Sem tombstone pendente e sem job: já terminou. Uma segunda chamada converge (§64).
    if (isAlreadyTombstoned && !existingJob) {
      return { status: 'DELETED' };
    }

    if (!isAlreadyTombstoned) {
      // §6 — as chaves saem **antes** do purge. Depois dele as linhas não existem mais, e os
      // arquivos ficariam órfãos no disco de um servidor que afirma ter apagado tudo.
      const mediaKeys = this.repo.listMediaStorageKeys(uid);

      // §5 — tombstone, job e purge são uma transação só. Se qualquer `DELETE` falhar, o
      // `ROLLBACK` leva junto o tombstone e o job: nada de conta bloqueada sobre dados intactos,
      // nada de job órfão, nada de purge pela metade.
      this.repo.beginAccountDeletion({
        firebaseUid: uid,
        uidHash,
        tombstoneId: randomUUID(),
        jobId: randomUUID(),
        now,
      });

      // Os arquivos saem depois do commit: uma falha de I/O aqui não pode desfazer o purge do
      // banco, que é a parte que revoga o acesso. O que sobrar de arquivo é recolhido pela
      // varredura de órfãos (§140 da T17.9), porque a metadata correspondente já não existe.
      const removedFiles = await this.purgeMediaFiles(mediaKeys);

      this.logger.info('account.deletion.data_purged', {
        uidPrefix: uid.slice(0, 6),
        mediaFilesRemoved: removedFiles,
      });
    }

    const job = this.repo.findJobByFirebaseUid(uid);
    if (!job) {
      // Só acontece se outra execução concorrente terminou a exclusão entre as duas leituras.
      return { status: 'DELETED' };
    }

    try {
      await this.advanceJob(job, now);
      this.logger.info('account.deletion.completed', { uidPrefix: uid.slice(0, 6) });
      return { status: 'DELETED' };
    } catch (error: unknown) {
      this.logger.warn('account.deletion.pending', {
        uidPrefix: uid.slice(0, 6),
        phase: this.repo.findJobByFirebaseUid(uid)?.phase ?? 'unknown',
        error: error instanceof Error ? error.message : String(error),
      });
      return { status: 'DELETION_PENDING' };
    }
  }

  /**
   * Executa os passos que ainda faltam para um job, na ordem, e remove o job quando terminam.
   *
   * Uma implementação só, usada pela rota interativa e pelo reconciliador em segundo plano
   * (§11): duas cópias desta sequência divergiriam na primeira mudança, e a divergência aqui é uma
   * conta que fica presa numa fase que só um dos dois caminhos sabe destravar.
   *
   * **Lança** quando o passo atual falha. Quem chama decide o que fazer com isso: a rota responde
   * `DELETION_PENDING`, o reconciliador agenda backoff. O job permanece na tabela, na fase em que
   * parou, e sobrevive a um restart do processo porque é uma linha do SQLite (§11).
   */
  async advanceJob(job: StoredDeletionJob, now: number): Promise<void> {
    let current = job;

    if (current.phase === 'LEDGER_PENDING') {
      // §10 — a falha propaga. Este `append` é o registro anti-ressurreição obrigatório, e sem ele
      // a exclusão não pode ser declarada terminada.
      this.ledger.appendDurably(current.uid_hash, now);
      this.repo.updateJobPhase(current.id, 'FIREBASE_PENDING', now);
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
    this.repo.deleteJobByFirebaseUid(current.firebase_uid);
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
}
