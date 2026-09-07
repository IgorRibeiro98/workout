package com.example.data.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Uma divergência entre a cópia local e a remota, preservada até uma decisão segura (T16.6/T16.7).
 *
 * ```text
 * T16.6   detecta, isola e guarda os dois lados
 * T16.7   o **usuário** escolhe, e a escolha vira uma mutação nova ou uma aplicação local
 * ```
 *
 * Continua não havendo aqui *last write wins*, "manda de novo com a revision atual", desempate
 * por `updatedAt` nem merge por campo: qualquer um deles apagaria em silêncio a alteração de um
 * dos dois lados, que é exatamente o defeito que o protocolo existe para impedir. O que a T16.7
 * acrescenta é uma **escolha explícita** — nunca uma heurística.
 *
 * ## A linha sobrevive ao processo
 *
 * Ela é Room, e não estado em memória: um conflito precisa continuar existindo depois de o app
 * ser fechado, morto ou reiniciado. E [status] existe pelo mesmo motivo — uma escolha do usuário
 * que ainda não foi confirmada pelo servidor não pode desaparecer com o processo, nem virar duas
 * mutações porque a tela foi tocada duas vezes.
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

    /**
     * Nome de [SyncConflictStatus] (T16.7).
     *
     * `PENDING` enquanto espera o usuário; `AWAITING_PUSH` depois que ele escolheu a versão deste
     * aparelho e a mutação correspondente ainda não foi confirmada pelo servidor. É este campo —
     * durável, e não um booleano em memória — que torna a resolução idempotente: um segundo toque
     * encontra a linha fora de `PENDING` e não faz nada.
     */
    @ColumnInfo(defaultValue = "PENDING")
    val status: String = SyncConflictStatus.PENDING.name,

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
     * A entidade foi **excluída** no servidor e este aparelho tem alteração local pendente nela
     * (T16.7).
     *
     * Nem o local é apagado, nem a exclusão é desfeita. As duas coisas são intenções legítimas de
     * pessoas diferentes (ou da mesma pessoa em aparelhos diferentes), e só o usuário pode dizer
     * qual delas vale.
     */
    REMOTE_DELETED_LOCAL_MODIFIED,

    /**
     * Este aparelho **excluiu** a entidade e o servidor tem uma alteração mais nova dela (T16.7).
     *
     * O inverso do anterior, e igualmente indecidível sozinho: apagar aqui destruiria uma edição
     * que quem apagou nunca chegou a ver.
     */
    LOCAL_DELETED_REMOTE_MODIFIED,

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
    IDEMPOTENCY;

    /**
     * O conflito envolve uma exclusão de um dos dois lados.
     *
     * Serve para uma regra só, e ela importa: quando um conflito já registrado recebe uma
     * classificação nova, a de origem é preservada — exceto se a nova for uma exclusão. Um
     * tombstone muda **o que o conflito é**, e as escolhas que fazem sentido deixam de ser as
     * mesmas.
     */
    val isDeletion: Boolean
        get() = this == REMOTE_DELETED_LOCAL_MODIFIED || this == LOCAL_DELETED_REMOTE_MODIFIED
}

/**
 * Em que ponto da resolução o conflito está (T16.7).
 *
 * Deliberadamente dois valores. `RESOLVING_REMOTE` e `RESOLVING_DELETE` não existem porque essas
 * resoluções são **locais e atômicas**: elas terminam dentro da própria transação, e um estado
 * intermediário durável só criaria uma linha que ninguém sabe destravar depois de um crash — o
 * mesmo motivo pelo qual a Outbox não tem `IN_FLIGHT`.
 */
enum class SyncConflictStatus {

    /** Esperando a decisão do usuário. É o estado normal. */
    PENDING,

    /**
     * O usuário escolheu a versão deste aparelho, e a mutação correspondente está na Outbox.
     *
     * A linha **não** é apagada aqui: se o push voltar a falhar (outro aparelho escreveu de novo
     * no meio), o conflito volta para `PENDING` com a revision nova, e o lado remoto guardado
     * continua disponível. Apagá-la antes da confirmação perderia justamente o que a próxima
     * decisão precisa.
     */
    AWAITING_PUSH
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
     * Marca o conflito como "o usuário escolheu a versão deste aparelho" (T16.7).
     *
     * A cláusula `status = 'PENDING'` é a idempotência: dois toques rápidos em "Manter deste
     * aparelho" fazem esta linha ser atualizada **uma** vez, e a segunda resolução encontra zero
     * linhas afetadas e desiste. Sem ela, o segundo toque criaria uma segunda mutação.
     */
    @Query(
        """
        UPDATE sync_conflicts
        SET status = 'AWAITING_PUSH'
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
          AND status = 'PENDING'
        """
    )
    suspend fun markAwaitingPush(
        ownerUid: String,
        entityType: String,
        entitySyncId: String
    ): Int

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
