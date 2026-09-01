package com.example.domain.evolution.model.performance

data class ExercisePerformanceEvolution(
    val exerciseId: String,
    val exerciseName: String,
    val firstWeight: Float?,
    val currentWeight: Float?,
    val bestWeight: Float?,
    val weightVariation: Float?,
    val totalExecutions: Int,
    val totalVolume: Float
)
