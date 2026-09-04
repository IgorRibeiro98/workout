package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface XpTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: XpTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactions(transactions: List<XpTransactionEntity>): List<Long>

    @Query("SELECT * FROM xp_transactions ORDER BY createdAt DESC")
    fun getAllTransactions(): Flow<List<XpTransactionEntity>>

    @Query("SELECT SUM(amount) FROM xp_transactions")
    fun getTotalXp(): Flow<Int?>

    @Query("SELECT EXISTS(SELECT 1 FROM xp_transactions WHERE eventId = :eventId)")
    suspend fun hasTransactionForEvent(eventId: String): Boolean

    @Query("DELETE FROM xp_transactions")
    suspend fun deleteAllTransactions()

    /**
     * Troca o histórico inteiro dentro de uma única transação do Room.
     *
     * Apagar e reinserir em chamadas separadas deixaria o banco sem XP caso o processo morresse no
     * meio; aqui ou o novo conjunto passa a valer inteiro, ou o antigo continua intacto.
     */
    @Transaction
    suspend fun replaceAllTransactions(transactions: List<XpTransactionEntity>) {
        deleteAllTransactions()
        insertTransactions(transactions)
    }
}
