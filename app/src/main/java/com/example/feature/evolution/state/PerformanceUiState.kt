package com.example.feature.evolution.state

import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary

data class PerformanceUiState(
    val isLoading: Boolean = false,
    val summary: WorkoutPerformanceSummary? = null,
    val exercises: List<ExercisePerformanceEvolution> = emptyList(),
    val records: List<PersonalRecord> = emptyList(),
    val volumeHistory: List<VolumePoint> = emptyList(),
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && (summary == null || summary.totalSessions == 0) && exercises.isEmpty() && records.isEmpty()
}
