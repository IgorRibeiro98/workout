package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(unlock: AchievementUnlockEntity): Long

    @Query("SELECT * FROM achievement_unlocks")
    suspend fun getUnlocks(): List<AchievementUnlockEntity>

    @Query("SELECT * FROM achievement_unlocks")
    fun observeUnlocks(): Flow<List<AchievementUnlockEntity>>
}
