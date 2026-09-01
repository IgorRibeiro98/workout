package com.example.feature.evolution.consistency

import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint

data class ConsistencyUiState(
    val isLoading: Boolean = true,
    val summary: WorkoutConsistencySummary? = null,
    val frequencyHistory: List<WorkoutFrequencyPoint> = emptyList(),
    val error: String? = null
)
