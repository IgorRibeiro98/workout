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

    @Query("SELECT * FROM sync_outbox ORDER BY id ASC")
    suspend fun all(): List<SyncOutboxEntryEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int
}
