package com.example.feature.evolution.body

import com.example.data.local.BodyMeasurementEntity
import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.EvolutionPeriod

data class BodyEvolutionUiState(
    val isLoading: Boolean = true,
    val measurements: List<BodyMeasurementEntity> = emptyList(),
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

    val sortedMeasurements: List<BodyMeasurementEntity>
        get() = measurements.sortedWith(compareBy({ it.date }, { it.createdAt }))

    val latestMeasurement: BodyMeasurementEntity?
        get() = sortedMeasurements.lastOrNull()

    val firstMeasurement: BodyMeasurementEntity?
        get() = sortedMeasurements.firstOrNull()
}
