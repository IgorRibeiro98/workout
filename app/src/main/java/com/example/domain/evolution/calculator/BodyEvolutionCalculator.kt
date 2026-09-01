package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.BodyEvolutionSummary
import com.example.domain.evolution.model.BodyMeasurement
import kotlin.math.roundToInt

object BodyEvolutionCalculator {

    fun calculate(measurements: List<BodyMeasurement>): BodyEvolutionSummary {
        val sorted = measurements.sortedBy { it.date }
        val latestWithWeight = sorted.lastOrNull { it.weightKg != null && it.weightKg > 0f }
        val firstWithWeight = sorted.firstOrNull { it.weightKg != null && it.weightKg > 0f }
        val latestWithHeight = sorted.lastOrNull { it.heightCm != null && it.heightCm > 0f }

        val currentWeight = latestWithWeight?.weightKg
        val initialWeight = firstWithWeight?.weightKg
        val weightVariation = if (currentWeight != null && initialWeight != null) {
            val rawDiff = currentWeight - initialWeight
            (rawDiff * 10f).roundToInt() / 10f
        } else null

        val currentHeight = latestWithHeight?.heightCm
        val bmiResult = if (currentWeight != null && currentHeight != null) {
            BodyMetricsCalculator.calculateBMI(currentWeight, currentHeight)
        } else null

        return BodyEvolutionSummary(
            currentWeight = currentWeight,
            initialWeight = initialWeight,
            weightVariation = weightVariation,
            currentHeight = currentHeight,
            bmi = bmiResult?.value,
            bmiCategory = bmiResult?.category
        )
    }
}
