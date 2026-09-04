package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeeklyGoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(entity: WeeklyGoalHistoryEntity)

    @Query("SELECT * FROM weekly_goal_history ORDER BY effectiveFromWeekStartEpochDay ASC")
    suspend fun getAllGoals(): List<WeeklyGoalHistoryEntity>

    @Query("SELECT * FROM weekly_goal_history ORDER BY effectiveFromWeekStartEpochDay ASC")
    fun getAllGoalsFlow(): Flow<List<WeeklyGoalHistoryEntity>>

    @Query("SELECT * FROM weekly_goal_history WHERE effectiveFromWeekStartEpochDay <= :weekStartEpochDay ORDER BY effectiveFromWeekStartEpochDay DESC LIMIT 1")
    suspend fun getGoalForWeek(weekStartEpochDay: Long): WeeklyGoalHistoryEntity?

    @Query("DELETE FROM weekly_goal_history")
    suspend fun deleteAll()
}
