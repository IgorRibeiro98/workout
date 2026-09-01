package com.example.feature.evolution.state

import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.WeightEvolution

data class EvolutionUiState(
    val isLoading: Boolean = false,
    val summary: EvolutionSummary? = null,
    val performance: PerformanceEvolution? = null,
    val consistency: ConsistencyMetrics? = null,
    val weightEvolution: WeightEvolution? = null,
    val error: String? = null
) {
    val isEmpty: Boolean
        get() {
            if (isLoading) return false
            if (summary == null && performance == null && consistency == null && weightEvolution == null) return true
            val hasWeight = (summary?.currentWeight != null && summary.currentWeight > 0f) ||
                    (weightEvolution?.currentWeight != null && weightEvolution.currentWeight > 0f)
            val hasWorkouts = (summary?.totalWorkoutSessions ?: 0) > 0 || (performance?.totalSessions ?: 0) > 0
            val hasExercises = (summary?.totalExercisesPerformed ?: 0) > 0 || (performance?.totalExercises ?: 0) > 0
            val hasTrainingDays = (summary?.trainingDays ?: 0) > 0 || (consistency?.trainingDays ?: 0) > 0
            return !hasWeight && !hasWorkouts && !hasExercises && !hasTrainingDays
        }
}
