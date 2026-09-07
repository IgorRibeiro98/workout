package com.example.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Acesso à Outbox. Leitura e escrita locais — nenhum destes métodos fala com a rede. */
@Dao
interface SyncOutboxDao {

    @Insert
    suspend fun insert(entry: SyncOutboxEntryEntity): Long

    /** A fila desta conta, em ordem de intenção. É o que o push da T16.6 vai consumir. */
    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE ownerUid = :ownerUid AND status = 'PENDING'
        ORDER BY id ASC
        """
    )
    suspend fun pendingFor(ownerUid: String): List<SyncOutboxEntryEntity>

    /**
     * A última intenção registrada para um agregado, de qualquer status.
     *
     * Base da coalescência: só faz sentido registrar de novo se a última entrada não disser já a
     * mesma coisa. Olhar a **última** (e não "existe alguma pendente") é o que impede um `UPSERT`
     * de ser engolido por uma entrada anterior quando já houve um `DELETE` depois dela.
     */
    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun latestFor(
        ownerUid: String,
        entityType: String,
        entitySyncId: String
    ): SyncOutboxEntryEntity?

    /**
     * A posição atual da fila — o maior `id` já usado, ou 0 se ela está vazia (T16.4).
     *
     * É o corte entre "o snapshot cobre isto" e "isto mudou depois". Lido dentro da mesma
     * transação que captura o snapshot, ele é o que permite ao backup dizer, sem ambiguidade, o
     * que já está protegido — e deixar em paz o que o usuário alterou enquanto o upload acontecia.
     *
     * A tabela inteira, e não só as entradas de um dono: a fila é uma sequência só, e o corte
     * descreve a posição dela.
     */
    @Query("SELECT COALESCE(MAX(id), 0) FROM sync_outbox")
    suspend fun maxSequence(): Long

    /**
     * Remove as intenções que um snapshot completo já cobriu (T16.4).
     *
     * Só é chamado **depois** de o servidor confirmar o backup — nunca antes, nunca "otimista".
     * Uma entrada apagada antes da confirmação seria uma alteração que ninguém mais sabe que
     * precisa subir.
     *
     * Elas são apagadas, e não marcadas: a intenção foi cumprida pelo snapshot, e guardá-la com um
     * carimbo criaria uma fila que nada mais consome. O filtro por `ownerUid` mantém intocada
     * qualquer entrada de outro dono.
     */
    @Query("DELETE FROM sync_outbox WHERE ownerUid = :ownerUid AND id <= :sequence")
    suspend fun deleteCoveredBy(ownerUid: String, sequence: Long): Int

    /**
     * As entradas de um agregado, de qualquer status (T16.6).
     *
     * O push coalesce as pendentes do mesmo agregado em um envio só; o apply remoto precisa saber
     * se um agregado tem alteração local pendente antes de escrever por cima dele.
     */
    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
        ORDER BY id ASC
        """
    )
    suspend fun entriesFor(
        ownerUid: String,
        entityType: String,
        entitySyncId: String
    ): List<SyncOutboxEntryEntity>

    /**
     * Confirma um conjunto de entradas: elas foram cumpridas e saem da fila (T16.6).
     *
     * Só é chamado **depois** de o servidor confirmar (`APPLIED` ou `ALREADY_APPLIED`), e dentro
     * da mesma transação que grava a `revision` conhecida. Apagar antes da confirmação
     * transformaria uma resposta perdida em alteração perdida — e alteração perdida não tem
     * conserto, enquanto reenvio tem.
     */
    @Query("DELETE FROM sync_outbox WHERE ownerUid = :ownerUid AND id IN (:ids)")
    suspend fun acknowledge(ownerUid: String, ids: List<Long>): Int

    /**
     * Tira as entradas da fila de envio sem apagá-las (T16.6).
     *
     * Reenviar o que o servidor recusou por conflito, contrato ou histórico imutável produziria a
     * mesma recusa para sempre. A alteração local continua guardada, e a resolução é da T16.7.
     */
    @Query(
        """
        UPDATE sync_outbox
        SET status = 'BLOCKED', blockedReason = :reason, lastAttemptAt = :now,
            attemptCount = attemptCount + 1
        WHERE ownerUid = :ownerUid AND id IN (:ids)
        """
    )
    suspend fun block(ownerUid: String, ids: List<Long>, reason: String, now: Long): Int

    /**
     * Descarta as entradas de um agregado que ficaram fora da fila de envio (T16.7).
     *
     * Chamado **só** a partir de uma resolução de conflito escolhida pelo usuário: ele decidiu
     * ficar com a versão da nuvem, ou confirmar a exclusão feita em outro aparelho, e a tentativa
     * local que estava bloqueada deixou de descrever algo que ele quer. Fora daí, uma entrada
     * `BLOCKED` nunca é apagada — ela é a alteração da pessoa.
     *
     * Só `BLOCKED`: uma entrada `PENDING` do mesmo agregado nasceu **depois** do conflito (o
     * usuário voltou a editar), e apagá-la seria descartar em silêncio uma alteração que nem
     * chegou a ser recusada.
     */
    @Query(
        """
        DELETE FROM sync_outbox
        WHERE ownerUid = :ownerUid AND entityType = :entityType AND entitySyncId = :entitySyncId
          AND status = 'BLOCKED'
        """
    )
    suspend fun discardBlockedFor(ownerUid: String, entityType: String, entitySyncId: String): Int

    /** Quantas alterações locais ainda não subiram. É o "3 alterações aguardando conexão". */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerUid = :ownerUid AND status = 'PENDING'")
    suspend fun pendingCountFor(ownerUid: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE ownerUid = :ownerUid AND status = 'BLOCKED'")
    suspend fun blockedCountFor(ownerUid: String): Int

    @Query("SELECT * FROM sync_outbox ORDER BY id ASC")
    suspend fun all(): List<SyncOutboxEntryEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int
}
