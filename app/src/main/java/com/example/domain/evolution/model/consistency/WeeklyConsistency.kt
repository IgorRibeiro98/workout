package com.example.domain.evolution.model.consistency

data class WeeklyConsistency(
    val weekStartEpochDay: Long,
    val goal: Int,
    val completedWorkouts: Int,
    val status: WeeklyConsistencyStatus
)
