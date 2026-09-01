package com.example.domain.evolution.model

data class WeightEvolution(
    val firstWeight: Float?,
    val currentWeight: Float?,
    val variation: Float?,
    val measurementsCount: Int,
    val trend: WeightTrend
)

enum class WeightTrend {
    UP,
    DOWN,
    STABLE,
    UNKNOWN
}
