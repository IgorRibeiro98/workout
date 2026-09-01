package com.example.domain.evolution.model

data class BodyEvolutionSummary(
    val currentWeight: Float?,
    val initialWeight: Float?,
    val weightVariation: Float?,
    val currentHeight: Float?,
    val bmi: Float?,
    val bmiCategory: BMICategory?
)
