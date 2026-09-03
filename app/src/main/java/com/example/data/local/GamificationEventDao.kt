package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GamificationEventDao {

    /**
     * Insere o fato ignorando repetições (mesma `dedupeKey`).
     *
     * @return o rowid gravado, ou -1 quando o evento já existia.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: GamificationEventEntity): Long

    @Query("SELECT * FROM gamification_events ORDER BY timestamp DESC, id DESC")
    suspend fun getAll(): List<GamificationEventEntity>

    @Query("SELECT * FROM gamification_events WHERE type = :type ORDER BY timestamp DESC, id DESC")
    suspend fun getByType(type: String): List<GamificationEventEntity>

    @Query("SELECT * FROM gamification_events ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<GamificationEventEntity>>

    @Query("SELECT * FROM gamification_events WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun findByDedupeKey(dedupeKey: String): GamificationEventEntity?

    @Query("SELECT COUNT(*) FROM gamification_events")
    suspend fun count(): Int
}
