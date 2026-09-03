package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XpTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: XpTransactionEntity): Long

    @Query("SELECT * FROM xp_transactions ORDER BY createdAt DESC")
    fun getAllTransactions(): Flow<List<XpTransactionEntity>>

    @Query("SELECT SUM(amount) FROM xp_transactions")
    fun getTotalXp(): Flow<Int?>

    @Query("SELECT EXISTS(SELECT 1 FROM xp_transactions WHERE eventId = :eventId)")
    suspend fun hasTransactionForEvent(eventId: String): Boolean
}
