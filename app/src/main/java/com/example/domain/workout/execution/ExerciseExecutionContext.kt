package com.example.domain.workout.execution

import com.example.data.local.SetLogEntity

/**
 * Historical performance summary for an exercise.
 */
data class PerformanceHistory(
    val weight: Float,
    val reps: Int,
    val rir: Int? = null,
    val timestamp: Long? = null,
    val daysAgo: Long? = null,
    val completedSets: List<SetLogEntity> = emptyList()
)

/**
 * All-time personal record summary for an exercise.
 */
data class PersonalRecord(
    val maxWeight: Float,
    val repsAtMaxWeight: Int,
    val date: Long? = null
)

/**
 * Exercise historical summary metrics.
 */
data class ExercisePerformanceSummary(
    val maxWeight: Float? = null,
    val maxVolume: Float? = null,
    val totalExecutions: Int = 0
)

/**
 * Contextual execution data for the active exercise to guide the user like a digital coach.
 */
data class ExerciseExecutionContext(
    val lastPerformance: PerformanceHistory? = null,
    val personalRecord: PersonalRecord? = null,
    val suggestedLoad: Float? = null,
    val targetReps: IntRange? = null,
    val targetSets: Int? = null,
    val summary: ExercisePerformanceSummary? = null,
    val isFirstTime: Boolean = lastPerformance == null && personalRecord == null
)
