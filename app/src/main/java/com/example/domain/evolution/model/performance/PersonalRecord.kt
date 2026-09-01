package com.example.domain.evolution.model.performance

data class PersonalRecord(
    val exerciseId: String,
    val exerciseName: String,
    val maxWeight: Float,
    val repetitions: Int,
    val achievedAt: Long
)
