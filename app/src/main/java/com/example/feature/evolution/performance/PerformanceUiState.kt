package com.example.feature.evolution.performance

import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.model.performance.chart.StrengthPoint

data class PerformanceUiState(
    val isLoading: Boolean = true,
    val summary: WorkoutPerformanceSummary? = null,
    val topExercises: List<ExercisePerformanceEvolution> = emptyList(),
    val allExercises: List<ExercisePerformanceEvolution> = emptyList(),
    val personalRecords: List<PersonalRecord> = emptyList(),
    val volumeHistory: List<VolumePoint> = emptyList(),
    val selectedExerciseId: String? = null,
    val selectedExerciseName: String? = null,
    val strengthHistory: List<StrengthPoint> = emptyList(),
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && (summary == null || summary.totalSessions == 0) && topExercises.isEmpty() && personalRecords.isEmpty()
}
