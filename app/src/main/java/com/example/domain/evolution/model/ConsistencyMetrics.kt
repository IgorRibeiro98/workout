package com.example.domain.evolution.model

data class ConsistencyMetrics(
    val trainingDays: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val monthlySessions: Int,
    val averageSessionsPerWeek: Float
)
