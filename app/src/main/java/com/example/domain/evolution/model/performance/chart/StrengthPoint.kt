package com.example.domain.evolution.model.performance.chart

data class StrengthPoint(
    val date: Long,
    val weight: Float,
    val repetitions: Int? = null
)
