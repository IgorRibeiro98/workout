package com.example.feature.evolution.state

import com.example.domain.evolution.model.EvolutionSummary

data class EvolutionUiState(
    val isLoading: Boolean = true,
    val summary: EvolutionSummary? = null,
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && (summary == null || (
            (summary.currentWeight == null || summary.currentWeight <= 0f) &&
            summary.totalWorkoutSessions == 0 &&
            summary.totalExercisesPerformed == 0 &&
            summary.trainingDays == 0
        ))
}

