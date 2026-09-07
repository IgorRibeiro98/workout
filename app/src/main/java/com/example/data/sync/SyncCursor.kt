package com.example.data.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Até onde este aparelho já leu o change log do servidor (T16.6).
 *
 * ## O cursor é do servidor, não do relógio
 *
 * `lastPulledServerSequence` é uma posição em `sync_changes.server_sequence` — estado controlado
 * pelo servidor. Ele **não** é um timestamp, não vem de `updatedAt` e não vem do relógio do
 * aparelho: relógios divergem, e um cursor baseado em tempo pularia mudanças silenciosamente.
 *
 * ## Por conta
 *
 * A chave é o `ownerUid`. Um cursor descreve a posição em **um** change log, e o change log é por
 * conta: reaproveitá-lo depois de uma troca de dono faria o aparelho pular tudo que a conta nova
 * já tinha.
 *
 * ## O cursor só avança depois do apply
 *
 * Esta linha é escrita **dentro** da mesma transação Room que aplica as mudanças da página. Um
 * cursor gravado antes transformaria uma falha de escrita em alteração remota perdida para sempre
 * — e perder alteração não tem conserto, enquanto reaplicar tem (o apply é idempotente).
 */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(

    @PrimaryKey val ownerUid: String,

    /** A maior `serverSequence` já aplicada com sucesso. Zero significa "do começo". */
    val lastPulledServerSequence: Long,

    /** Quando o último ciclo completou, em epoch millis UTC. Só para a UI dizer "agora há pouco". */
    val lastSyncedAt: Long
)

@Dao
interface SyncCursorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursor WHERE ownerUid = :ownerUid LIMIT 1")
    suspend fun get(ownerUid: String): SyncCursorEntity?

    /** Usado pelo restore: o dataset foi substituído e a posição anterior não o descreve mais. */
    @Query("DELETE FROM sync_cursor")
    suspend fun deleteAll()
}
