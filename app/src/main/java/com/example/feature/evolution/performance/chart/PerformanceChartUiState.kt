package com.example.feature.evolution.performance.chart

import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.chart.StrengthPoint

data class PerformanceChartUiState(
    val isLoading: Boolean = true,
    val volumeHistory: List<VolumePoint> = emptyList(),
    val availableExercises: List<ExercisePerformanceEvolution> = emptyList(),
    val selectedExercise: String? = null,
    val selectedExerciseName: String? = null,
    val strengthHistory: List<StrengthPoint> = emptyList(),
    val error: String? = null
)
