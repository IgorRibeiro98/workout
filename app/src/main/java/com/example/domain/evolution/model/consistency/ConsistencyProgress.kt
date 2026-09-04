package com.example.domain.evolution.model.consistency

data class ConsistencyProgress(
    val currentStreakWeeks: Int,
    val longestStreakWeeks: Int,
    val currentWeekCompleted: Int,
    val currentWeekGoal: Int,
    val currentWeekStatus: WeeklyConsistencyStatus
)
