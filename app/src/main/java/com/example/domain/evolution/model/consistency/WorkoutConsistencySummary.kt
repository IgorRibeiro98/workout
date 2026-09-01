package com.example.domain.evolution.model.consistency

data class WorkoutConsistencySummary(
    val totalSessions: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val averageSessionsPerWeek: Float,
    val lastWorkoutDate: Long?
)
