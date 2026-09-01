package com.example.domain.evolution.model.performance

data class WorkoutPerformanceSummary(
    val totalSessions: Int,
    val totalExercises: Int,
    val totalSets: Int,
    val totalRepetitions: Int,
    val totalVolume: Float,
    val averageSessionDuration: Int? = null
)
