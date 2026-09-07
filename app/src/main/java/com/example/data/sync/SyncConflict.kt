package com.example.data.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Uma divergência entre a cópia local e a remota que a T16.6 **detecta e preserva** (T16.6).
 *
 * ```text
 * T16.6   detecta, isola e guarda os dois lados
 * T16.7   resolve
 * ```
 *
 * Esta tabela existe para que a T16.7 tenha com o que trabalhar, e para que nada seja decidido
 * enquanto isso. Não há aqui *last write wins*, "manda de novo com a revision atual", desempate
 * por `updatedAt` nem merge por campo: qualquer um deles apagaria em silêncio a alteração de um
 * dos dois lados, que é exatamente o defeito que o protocolo existe para impedir.
 *
 * ## O que ela guarda, e por quê
 *
 * - o **lado local** continua no lugar de sempre: a entidade no Room, intacta, e a entrada da
 *   Outbox, preservada em [SyncOutboxStatus.BLOCKED]. Nada é copiado daqui;
 * - o **lado remoto** é guardado aqui — revision, hash e o payload daquela sequência. Ele é
 *   guardado, e não "reobtido depois", porque o pull é por cursor: uma vez que o cursor passou
 *   daquela sequência, buscar de novo *aquela* versão exigiria um endpoint que não existe.
 *
 * ## Um conflito não bloqueia o resto
 *
 * A chave é o agregado. Um treino em conflito impede que **aquele treino** convirja; medidas,
 * check-ins e sessões continuam sincronizando normalmente.
 */
@Entity(tableName = "sync_conflicts", primaryKeys = ["ownerUid", "entityType", "entitySyncId"])
data class SyncConflictEntity(

    val ownerUid: String,

    /** Nome de [SyncEntityType]. */
    val entityType: String,

    /** Identidade global do agregado em conflito. */
    val entitySyncId: String,

    /** Nome de [SyncConflictKind]. */
    val kind: String,

    /** A revision sobre a qual a alteração local foi construída, quando havia uma. */
    val baseRevision: Long? = null,

    /** O hash canônico do agregado local no momento da detecção. */
    val localPayloadHash: String? = null,

    /** A revision remota que este aparelho não aplicou. */
    val remoteRevision: Long? = null,

    /** A posição da mudança remota no change log. */
    val remoteServerSequence: Long? = null,

    val remotePayloadHash: String? = null,

    /**
     * O payload remoto daquela revision, na forma canônica.
     *
     * Nulo quando o conflito foi detectado no **push** — ali o servidor devolve a revision atual,
     * não o conteúdo. O pull seguinte preenche este campo quando a mudança remota chegar.
     */
    val remotePayload: String? = null,

    /** A tentativa local que ficou bloqueada, quando o conflito veio de um push. */
    val clientMutationId: String? = null,

    val detectedAt: Long
)

/**
 * Que tipo de divergência foi detectada.
 *
 * Nomes descrevem **o que aconteceu**, não o que fazer: a decisão é da T16.7.
 */
enum class SyncConflictKind {

    /**
     * O push local foi recusado porque o servidor já estava adiante.
     *
     * Outro aparelho escreveu depois da `baseRevision` que este conhecia. A alteração local
     * continua no Room e na Outbox; o remoto continua no servidor. Nenhum dos dois foi perdido.
     */
    STALE_LOCAL_CHANGE,

    /**
     * Chegou uma mudança remota para um agregado com alteração local pendente e conteúdo
     * diferente.
     *
     * O remoto **não** é aplicado por cima: sobrescrever dado local sujo é a perda que a T16.7
     * existe para evitar.
     */
    REMOTE_AHEAD_LOCAL_DIRTY,

    /**
     * Mesma sessão concluída, conteúdo histórico divergente.
     *
     * Histórico não é documento colaborativo. Divergência é conflito de integridade, e nunca
     * "a versão mais nova vence".
     */
    IMMUTABLE_HISTORY,

    /**
     * O servidor recusou a mutação pelo contrato — payload, identidade ou versão.
     *
     * Não é conflito entre dois aparelhos: é defeito. Fica registrado para diagnóstico, e o
     * reenvio automático não acontece porque reenviar o mesmo produziria o mesmo.
     */
    REJECTED_BY_SERVER,

    /**
     * Mesmo `clientMutationId`, conteúdo diferente.
     *
     * Só acontece com defeito de cliente. A tentativa não é reaplicada.
     */
    IDEMPOTENCY
}

@Dao
interface SyncConflictDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conflict: SyncConflictEntity)

    @Query(
        """
        SELECT * FROM sync_conflicts
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
        LIMIT 1
        """
    )
    suspend fun get(
        ownerUid: String,
        entityType: String,
        entitySyncId: String
    ): SyncConflictEntity?

    @Query("SELECT * FROM sync_conflicts WHERE ownerUid = :ownerUid ORDER BY detectedAt DESC")
    suspend fun allFor(ownerUid: String): List<SyncConflictEntity>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE ownerUid = :ownerUid")
    fun observeCount(ownerUid: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE ownerUid = :ownerUid")
    suspend fun countFor(ownerUid: String): Int

    /**
     * O conflito daquele agregado deixou de existir — as duas cópias convergiram.
     *
     * Só é chamado quando isso é **provado**: o push foi aceito, ou o conteúdo local e o remoto
     * têm o mesmo hash canônico. Nunca por idade e nunca "para limpar a tela".
     */
    @Query(
        """
        DELETE FROM sync_conflicts
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
        """
    )
    suspend fun clear(ownerUid: String, entityType: String, entitySyncId: String)

    /** Usado pelo restore: o dataset foi substituído e os conflitos descreviam o anterior. */
    @Query("DELETE FROM sync_conflicts")
    suspend fun deleteAll()
}
