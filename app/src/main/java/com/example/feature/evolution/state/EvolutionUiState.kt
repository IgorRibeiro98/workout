package com.example.feature.evolution.state

import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.WeightEvolution

data class EvolutionUiState(
    val isLoading: Boolean = true,
    val summary: EvolutionSummary? = null,
    val weightEvolution: WeightEvolution? = null,
    val performance: PerformanceEvolution? = null,
    val consistency: ConsistencyMetrics? = null,
    val error: String? = null
) {
    val isEmpty: Boolean
        get() {
            if (isLoading) return false
            if (summary == null) return true
            val hasWeight = summary.currentWeight != null && summary.currentWeight > 0f
            val hasWorkouts = summary.totalWorkoutSessions > 0
            val hasExercises = summary.totalExercisesPerformed > 0
            val hasTrainingDays = summary.trainingDays > 0
            return !hasWeight && !hasWorkouts && !hasExercises && !hasTrainingDays
        }
}
