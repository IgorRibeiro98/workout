package com.example.data.backup

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Uma tentativa lógica de backup, **durável** (T16.4).
 *
 * ## Por que a tentativa é uma linha de banco
 *
 * ```text
 * snapshot só em memória  →  processo morre  →  o servidor pode ter recebido, ou não
 *                         →  o app gera outro clientBackupId
 *                         →  dois backups lógicos para a mesma intenção
 * ```
 *
 * Persistir a tentativa é o que fecha essa janela. Depois de um process death, o app reabre,
 * encontra a tentativa pendente e **reenvia exatamente o mesmo payload com o mesmo
 * [clientBackupId]** — e o servidor devolve o backup que já existia em vez de criar outro.
 *
 * ## O payload é imutável
 *
 * Depois que uma tentativa nasce, [payload] não muda. Um retry envia os mesmos bytes, e por isso
 * chega ao mesmo [payloadHash]: é o que transforma "a resposta se perdeu" em uma operação segura
 * em vez de um conflito. Precisar de um estado mais novo significa **outra tentativa**, com outro
 * `clientBackupId` — nunca reescrever esta.
 *
 * O payload é guardado como TEXT no próprio Room, e não em arquivo separado: um snapshot do Spark
 * fica na casa de centenas de kilobytes, cabe com folga, e ficar no mesmo banco é o que dá
 * atomicidade entre "capturei o estado" e "registrei a tentativa" sem inventar coordenação entre
 * dois armazenamentos. Nada disso vai para armazenamento externo público.
 */
@Entity(
    tableName = "backup_attempts",
    indices = [
        // Idempotência: o servidor reconhece um reenvio por `clientBackupId`. Duas linhas com o
        // mesmo id seriam duas tentativas se passando por uma.
        Index(value = ["clientBackupId"], unique = true),
        Index(value = ["ownerUid", "status", "id"])
    ]
)
data class BackupAttemptEntity(

    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Identidade da tentativa lógica. UUID gerado aqui, estável entre reenvios. */
    val clientBackupId: String,

    /**
     * A conta dona do dataset no momento da captura.
     *
     * Vem do [CloudDataBindingEntity], **não** do `FirebaseAuth.currentUser`. É isso que impede
     * que trocar de conta no aparelho mande dados de A para a conta B.
     */
    val ownerUid: String,

    val deviceId: String,

    val backupSchemaVersion: Int,

    /**
     * O corte entre "coberto por este snapshot" e "mudou depois dele".
     *
     * É o maior `id` da Outbox no instante da captura, lido dentro da mesma transação. Entradas
     * com `id` menor ou igual a este são cobertas pelo snapshot completo; as posteriores — as que
     * o usuário criar **enquanto o upload acontece** — não são, e continuam pendentes.
     */
    val coveredOutboxSequence: Long,

    /** SHA-256 da forma canônica de [payload]. Calculado aqui; o servidor calcula o dele. */
    val payloadHash: String,

    /** O snapshot completo, na forma canônica. É exatamente o corpo que vai ao servidor. */
    val payload: String,

    val itemCount: Int,

    val sizeBytes: Int,

    /** Quando a tentativa nasceu, em epoch millis UTC. */
    val createdAt: Long,

    /** Nome de [BackupAttemptStatus]. */
    val status: String = BackupAttemptStatus.PENDING.name,

    val attemptCount: Int = 0,

    val lastAttemptAt: Long? = null,

    /**
     * Por que a última tentativa falhou — o **nome** da classe de falha, nunca conteúdo.
     *
     * `NETWORK`, `UNAVAILABLE`, `CONFLICT`... Mensagem de servidor e payload não entram aqui:
     * esta coluna acaba em tela e em diagnóstico.
     */
    val failureReason: String? = null,

    /** A identidade que o **servidor** deu ao backup, quando ele confirmou. */
    val serverBackupId: String? = null,

    /** O `createdAt` do **servidor**. É esta a hora que a UI mostra como "último backup". */
    val serverCreatedAt: Long? = null,

    /** O hash que o servidor calculou. Guardado para diagnóstico e para o restore da T16.5. */
    val serverPayloadHash: String? = null
)

/**
 * O estado de uma tentativa.
 *
 * `UPLOADING` não existe de propósito: seria um estado que não sobrevive a process death — o app
 * morre no meio do upload e a linha ficaria mentindo. E `FAILED` não existe porque a maioria das
 * falhas **não** encerra a tentativa: sem rede, com o servidor fora do ar ou com a sessão expirada,
 * ela continua [PENDING], recuperável, com [BackupAttemptEntity.attemptCount] e
 * [BackupAttemptEntity.failureReason] contando o que houve.
 *
 * O terceiro valor cobre o caso que faltava (auditoria 2026-09-12): a recusa **definitiva**.
 */
enum class BackupAttemptStatus {
    /** Criada e ainda não confirmada pelo servidor. Pode (e deve) ser reenviada. */
    PENDING,

    /** O servidor confirmou. A Outbox coberta já pôde ser baseline. */
    SUCCEEDED,

    /**
     * O servidor recusou estes bytes de um jeito que reenviá-los **não** resolve.
     *
     * Snapshot grande demais (413), contrato violado (4xx), colisão de `clientBackupId` com
     * conteúdo diferente (409) ou destino inexistente (404). A tentativa é imutável por desenho, e
     * um retry manda exatamente os mesmos bytes: insistir produziria a mesma recusa para sempre —
     * e, como `oldestPendingFor` sempre reaproveita a pendente mais antiga, o usuário ficava
     * **impedido de fazer qualquer backup novo**, que é um preço muito maior do que o da falha.
     *
     * Encerrar não apaga nada: o payload, o corte da Outbox e o motivo continuam na linha, para
     * diagnóstico. O que muda é que a próxima operação captura um estado novo em vez de reenviar
     * um que já foi recusado.
     */
    ABANDONED
}

@Dao
interface BackupAttemptDao {

    @Insert
    suspend fun insert(attempt: BackupAttemptEntity): Long

    /**
     * A tentativa pendente mais antiga desta conta.
     *
     * A mais antiga, e não a mais nova: se existe uma pendente, ela precisa ser resolvida antes de
     * o app criar outra — senão dois snapshots do mesmo dataset ficariam em voo e o baseline da
     * Outbox não teria um corte único.
     */
    @Query(
        """
        SELECT * FROM backup_attempts
        WHERE ownerUid = :ownerUid AND status = 'PENDING'
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun oldestPendingFor(ownerUid: String): BackupAttemptEntity?

    @Query("SELECT * FROM backup_attempts WHERE clientBackupId = :clientBackupId LIMIT 1")
    suspend fun byClientBackupId(clientBackupId: String): BackupAttemptEntity?

    /** A última tentativa confirmada — o que a UI mostra como "último backup". */
    @Query(
        """
        SELECT * FROM backup_attempts
        WHERE ownerUid = :ownerUid AND status = 'SUCCEEDED'
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun lastSucceededFor(ownerUid: String): BackupAttemptEntity?

    /**
     * Marca a tentativa como confirmada.
     *
     * Um `UPDATE` de colunas, e não um `REPLACE` da linha: [BackupAttemptEntity.payload] e
     * [BackupAttemptEntity.payloadHash] não aparecem aqui, então não há caminho por onde eles
     * mudem depois da criação.
     */
    @Query(
        """
        UPDATE backup_attempts
        SET status = 'SUCCEEDED',
            serverBackupId = :serverBackupId,
            serverCreatedAt = :serverCreatedAt,
            serverPayloadHash = :serverPayloadHash,
            failureReason = NULL,
            lastAttemptAt = :now
        WHERE id = :id
        """
    )
    suspend fun markSucceeded(
        id: Long,
        serverBackupId: String,
        serverCreatedAt: Long,
        serverPayloadHash: String,
        now: Long
    )

    /** Registra uma falha recuperável. A tentativa continua `PENDING` e continua reenviável. */
    @Query(
        """
        UPDATE backup_attempts
        SET attemptCount = attemptCount + 1,
            lastAttemptAt = :now,
            failureReason = :reason
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: Long, reason: String, now: Long)

    /**
     * Encerra a tentativa: estes bytes foram recusados e não voltam (ver [BackupAttemptStatus.ABANDONED]).
     *
     * O `UPDATE` não toca em [BackupAttemptEntity.payload], [BackupAttemptEntity.payloadHash] nem
     * em [BackupAttemptEntity.coveredOutboxSequence] — a tentativa continua imutável e auditável.
     * A Outbox também não é tocada: nada subiu, então nada foi coberto.
     */
    @Query(
        """
        UPDATE backup_attempts
        SET status = 'ABANDONED',
            attemptCount = attemptCount + 1,
            lastAttemptAt = :now,
            failureReason = :reason
        WHERE id = :id
        """
    )
    suspend fun markAbandoned(id: Long, reason: String, now: Long)

    @Query("SELECT * FROM backup_attempts ORDER BY id ASC")
    suspend fun all(): List<BackupAttemptEntity>

    @Query("SELECT COUNT(*) FROM backup_attempts")
    suspend fun count(): Int
}
