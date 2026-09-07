package com.example.data.restore

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Uma tentativa lógica de restore, **durável** (T16.5).
 *
 * ## Por que a tentativa é uma linha de banco
 *
 * ```text
 * restore só em memória  →  processo morre no meio da aplicação
 *                        →  o app reabre sem saber se o dataset foi substituído
 *                        →  metade dos dados do backup, metade dos antigos, ninguém sabe qual
 * ```
 *
 * Persistir a tentativa é o que fecha essa janela. Depois de um process death o app encontra a
 * linha, lê em que **fase** ela parou e decide de forma determinística: retomar ou desfazer
 * ([RestoreRecovery]). Sem ela, "o restore terminou?" seria uma pergunta sem resposta.
 *
 * ## Ela não guarda o snapshot
 *
 * Diferente da `BackupAttemptEntity` da T16.4 — que guarda o payload congelado porque precisa
 * reenviar exatamente os mesmos bytes —, aqui o documento baixado vive em **arquivo privado do
 * app** ([downloadPath]). Um snapshot de restore chega do servidor e pode ser rebaixado a qualquer
 * momento (o download é read-only e repetível); guardá-lo dentro do banco que ele vai substituir
 * significaria carregar centenas de kilobytes para dentro da própria transação crítica.
 *
 * O que fica na linha é o que precisa sobreviver a um reinício: identidade, dono, hash esperado,
 * fase e onde estão os arquivos.
 */
@Entity(
    tableName = "restore_attempts",
    indices = [
        Index(value = ["restoreAttemptId"], unique = true),
        Index(value = ["ownerUid", "status", "id"])
    ]
)
data class RestoreAttemptEntity(

    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Identidade da tentativa lógica de restore. UUID gerado aqui.
     *
     * Não se confunde com nenhuma das outras identidades do projeto: não é o `backupId` (do
     * servidor), não é o `clientBackupId` (da tentativa de **backup**) e não é `syncId` (de uma
     * entidade). Ele nomeia *esta* tentativa de restaurar, e nomeia os arquivos dela em disco.
     */
    val restoreAttemptId: String,

    /** O backup escolhido, na identidade opaca que o servidor deu a ele. */
    val backupId: String,

    /**
     * A conta dona do backup **e** do dataset resultante.
     *
     * Revalidada imediatamente antes da aplicação: se a sessão do Firebase tiver mudado de conta
     * no meio do caminho, o restore para aqui sem tocar em dado nenhum.
     */
    val ownerUid: String,

    /** O hash que a metadata do servidor declarou. O Android recalcula o dele e compara. */
    val payloadHash: String,

    val backupSchemaVersion: Int,

    /** Quando o servidor criou o backup. É a data que a UI mostra, e ela é do **servidor**. */
    val backupCreatedAt: Long,

    /** Arquivo privado com o snapshot baixado. `null` antes de o download completar. */
    val downloadPath: String? = null,

    /**
     * Arquivo privado com o snapshot de segurança do estado **anterior**.
     *
     * Criado antes de qualquer mutação, e é o que permite reconstruir o dataset se a aplicação
     * falhar depois do ponto de não retorno do Room.
     */
    val safetySnapshotPath: String? = null,

    /**
     * O dataset era **sem dono** quando esta tentativa começou.
     *
     * Guardado na criação porque o rollback precisa saber para onde voltar: desfazer um restore que
     * estabeleceu o vínculo e deixar o vínculo para trás faria a conta apontar para dados que não
     * vieram do backup dela. Depois da aplicação, o vínculo já existe — e o estado anterior não
     * seria mais dedutível do banco.
     */
    val datasetWasUnbound: Boolean,

    /** Nome de [RestorePhase]. */
    val status: String = RestorePhase.DOWNLOADING.name,

    val createdAt: Long,

    val updatedAt: Long,

    /**
     * Por que a tentativa terminou sem restaurar — o **nome** da classe de falha, nunca conteúdo.
     *
     * `BACKUP_INTEGRITY_ERROR`, `INVALID_BACKUP`, `ACCOUNT_CHANGED`... Nome de treino, nota e
     * medida não entram aqui: esta coluna acaba em tela e em diagnóstico.
     */
    val failureReason: String? = null
)

/**
 * As fases de um restore, na ordem em que acontecem.
 *
 * A ordem é o invariante da tarefa: **nada local é alterado** antes de [VALIDATED], e o snapshot
 * de segurança existe antes de [ROOM_APPLIED]. Cada valor é um ponto em que o processo pode morrer
 * e o app precisa saber o que fazer ao reabrir.
 *
 * ```text
 * DOWNLOADING ─→ VALIDATED ─→ SAFETY_SNAPSHOT_CREATED ─→ ROOM_APPLIED ─→ PREFERENCES_APPLIED ─→ COMPLETED
 *      │              │                   │                    │                  │
 *      └──────────────┴───────────────────┘                    └──────────────────┴──→ retomar
 *                     ↓                                                                 (dado já
 *              ABANDONED (zero alteração local)                                        substituído)
 * ```
 *
 * Não existe `APPLYING`: um estado que só vale enquanto o processo está vivo mentiria depois de um
 * process death. A transação do Room ou commitou — e a linha diz [ROOM_APPLIED] — ou não commitou,
 * e a linha continua dizendo [SAFETY_SNAPSHOT_CREATED].
 */
enum class RestorePhase {
    /** Baixando o snapshot para o arquivo privado. Nada local foi tocado. */
    DOWNLOADING,

    /** Baixado, hash conferido, schema e semântica validados. Nada local foi tocado. */
    VALIDATED,

    /** O estado anterior foi salvo em arquivo privado. Ainda nada foi substituído. */
    SAFETY_SNAPSHOT_CREATED,

    /** O Room foi substituído e commitado. As preferências ainda não. */
    ROOM_APPLIED,

    /** As preferências do DataStore também foram aplicadas. */
    PREFERENCES_APPLIED,

    /** Tudo aplicado e verificado. Só aqui a UI pode dizer "restaurado". */
    COMPLETED,

    /**
     * A tentativa terminou **sem** alterar dado local — ou foi desfeita até o estado anterior.
     *
     * Terminal. Uma tentativa abandonada não é retomada: o usuário escolhe de novo, e um novo
     * download é seguro porque ele é read-only no servidor.
     */
    ABANDONED
}

@Dao
interface RestoreAttemptDao {

    @Insert
    suspend fun insert(attempt: RestoreAttemptEntity): Long

    @Query("SELECT * FROM restore_attempts WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): RestoreAttemptEntity?

    @Query("SELECT * FROM restore_attempts WHERE restoreAttemptId = :restoreAttemptId LIMIT 1")
    suspend fun byRestoreAttemptId(restoreAttemptId: String): RestoreAttemptEntity?

    /**
     * A tentativa em andamento, se houver — de qualquer conta.
     *
     * "De qualquer conta" é deliberado: uma tentativa interrompida bloqueia o aparelho inteiro até
     * ser resolvida. Ignorá-la porque a sessão atual é de outra conta deixaria um dataset
     * possivelmente pela metade sendo usado como se estivesse íntegro.
     */
    @Query(
        """
        SELECT * FROM restore_attempts
        WHERE status NOT IN ('COMPLETED', 'ABANDONED')
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun oldestUnfinished(): RestoreAttemptEntity?

    /** A última tentativa concluída, para a UI dizer quando o aparelho foi restaurado. */
    @Query(
        """
        SELECT * FROM restore_attempts
        WHERE ownerUid = :ownerUid AND status = 'COMPLETED'
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun lastCompletedFor(ownerUid: String): RestoreAttemptEntity?

    /**
     * Avança a fase.
     *
     * `UPDATE` de colunas, e não `REPLACE` da linha: [RestoreAttemptEntity.restoreAttemptId],
     * [RestoreAttemptEntity.backupId] e [RestoreAttemptEntity.payloadHash] não aparecem aqui, então
     * não existe caminho por onde eles mudem depois da criação.
     */
    @Query(
        """
        UPDATE restore_attempts
        SET status = :status, updatedAt = :now, failureReason = NULL
        WHERE id = :id
        """
    )
    suspend fun updateStatus(id: Long, status: String, now: Long)

    @Query("UPDATE restore_attempts SET downloadPath = :path, updatedAt = :now WHERE id = :id")
    suspend fun updateDownloadPath(id: Long, path: String?, now: Long)

    @Query(
        "UPDATE restore_attempts SET safetySnapshotPath = :path, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateSafetySnapshotPath(id: Long, path: String?, now: Long)

    /** Encerra a tentativa sem restaurar. [reason] é classe de erro, nunca conteúdo. */
    @Query(
        """
        UPDATE restore_attempts
        SET status = 'ABANDONED', failureReason = :reason, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun abandon(id: Long, reason: String, now: Long)

    @Query("SELECT * FROM restore_attempts ORDER BY id ASC")
    suspend fun all(): List<RestoreAttemptEntity>

    @Query("SELECT COUNT(*) FROM restore_attempts")
    suspend fun count(): Int
}
