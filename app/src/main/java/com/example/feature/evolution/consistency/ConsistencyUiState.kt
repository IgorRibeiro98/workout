package com.example.feature.evolution.consistency

import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint

data class ConsistencyUiState(
    val isLoading: Boolean = true,
    val summary: WorkoutConsistencySummary? = null,
    val consistencyProgress: ConsistencyProgress? = null,
    val weeklyConsistencies: List<WeeklyConsistency> = emptyList(),
    val frequencyHistory: List<WorkoutFrequencyPoint> = emptyList(),
    val workoutTimestamps: List<Long> = emptyList(),
    val error: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && (summary == null || summary.totalSessions == 0) && frequencyHistory.isEmpty()
}
