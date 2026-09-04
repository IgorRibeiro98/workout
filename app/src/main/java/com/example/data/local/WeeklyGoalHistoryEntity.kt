package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weekly_goal_history")
data class WeeklyGoalHistoryEntity(
    @PrimaryKey val effectiveFromWeekStartEpochDay: Long,
    val goal: Int,
    val createdAt: Long = System.currentTimeMillis()
)
