package com.example.domain.evolution.calculator

import com.example.data.local.BodyMeasurementEntity
import com.example.domain.evolution.model.WeightEvolution
import com.example.domain.evolution.model.WeightTrend
import kotlin.math.abs
import kotlin.math.roundToInt

object WeightEvolutionCalculator {

    /**
     * Calculates weight evolution metrics from a chronological list of weights (oldest to newest).
     */
    fun calculate(weights: List<Float>): WeightEvolution {
        val validWeights = weights.filter { it > 0f }
        if (validWeights.isEmpty()) {
            return WeightEvolution(
                firstWeight = null,
                currentWeight = null,
                variation = null,
                measurementsCount = 0,
                trend = WeightTrend.UNKNOWN
            )
        }

        if (validWeights.size == 1) {
            val singleWeight = (validWeights.first() * 10f).roundToInt() / 10f
            return WeightEvolution(
                firstWeight = singleWeight,
                currentWeight = singleWeight,
                variation = 0f,
                measurementsCount = 1,
                trend = WeightTrend.STABLE
            )
        }

        val first = validWeights.first()
        val current = validWeights.last()
        val rawDiff = current - first
        val roundedVariation = (rawDiff * 10f).roundToInt() / 10f

        val trend = when {
            roundedVariation > 0.05f -> WeightTrend.UP
            roundedVariation < -0.05f -> WeightTrend.DOWN
            else -> WeightTrend.STABLE
        }

        val roundedFirst = (first * 10f).roundToInt() / 10f
        val roundedCurrent = (current * 10f).roundToInt() / 10f

        return WeightEvolution(
            firstWeight = roundedFirst,
            currentWeight = roundedCurrent,
            variation = roundedVariation,
            measurementsCount = validWeights.size,
            trend = trend
        )
    }

    /**
     * Calculates weight evolution metrics from a list of body measurement entities.
     * Sorts entities chronologically by date/createdAt before calculating.
     */
    fun calculateFromMeasurements(measurements: List<BodyMeasurementEntity>): WeightEvolution {
        val sortedWeights = measurements
            .filter { it.weightKg != null && it.weightKg > 0f }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))
            .mapNotNull { it.weightKg }

        return calculate(sortedWeights)
    }
}
