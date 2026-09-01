package com.example.domain.evolution.model

data class EvolutionSummary(
    val currentWeight: Float?,
    val initialWeight: Float?,
    val weightChange: Float?,
    val totalWorkoutSessions: Int,
    val trainingDays: Int,
    val averageWorkoutsPerWeek: Float,
    val totalExercisesPerformed: Int,
    val generatedAt: Long
)
