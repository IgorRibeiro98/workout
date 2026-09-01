package com.example.feature.evolution.body

import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.BodyEvolutionSummary
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.EvolutionPeriod
import com.example.domain.evolution.model.WeightEvolution

data class BodyEvolutionUiState(
    val isLoading: Boolean = true,
    val summary: BodyEvolutionSummary? = null,
    val measurements: List<BodyMeasurement> = emptyList(),
    val weightEvolution: WeightEvolution? = null,
    val currentWeight: Float? = null,
    val initialWeight: Float? = null,
    val weightVariation: Float? = null,
    val currentHeight: Float? = null,
    val bmi: Float? = null,
    val bmiCategory: BMICategory? = null,
    val selectedPeriod: EvolutionPeriod = EvolutionPeriod.ALL_TIME,
    val error: String? = null
) {
    val hasMeasurements: Boolean
        get() = measurements.isNotEmpty()

    val sortedMeasurements: List<BodyMeasurement>
        get() = measurements.sortedBy { it.date }

    val latestMeasurement: BodyMeasurement?
        get() = sortedMeasurements.lastOrNull()

    val firstMeasurement: BodyMeasurement?
        get() = sortedMeasurements.firstOrNull()
}
