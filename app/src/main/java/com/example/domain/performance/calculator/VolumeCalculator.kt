package com.example.domain.performance.calculator

import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetLogEntity
import com.example.data.local.SetType
import com.example.domain.performance.model.volume.VolumeSummary
import com.example.domain.performance.model.volume.WorkoutVolume

object VolumeCalculator {

    /**
     * Calculates volume for a single set: weight * repetitions.
     * Returns 0.0 if weight is null, <= 0, or repetitions <= 0.
     */
    fun calculateSetVolume(weight: Float?, repetitions: Int): Double {
        if (weight == null || weight <= 0f || repetitions <= 0) return 0.0
        return (weight * repetitions).toDouble()
    }

    /**
     * Calculates total tonnage volume for completed working sets (excludes warmup).
     */
    fun calculateSetsVolume(sets: List<SetLogEntity>): Double {
        return sets.filter { it.completed && it.type != SetType.WARMUP.name }
            .sumOf { calculateSetVolume(it.weight, it.repetitions) }
    }

    /**
     * Calculates total tonnage volume for a single workout session's exercises.
     */
    fun calculateWorkoutVolume(exercises: List<ExerciseSessionWithSets>): Double {
        return exercises.sumOf { calculateSetsVolume(it.sets) }
    }

    /**
     * Builds a WorkoutVolume domain model for a workout session.
     */
    fun calculateWorkoutVolumeObject(
        sessionId: Long,
        exercises: List<ExerciseSessionWithSets>,
        calculatedAt: Long = System.currentTimeMillis()
    ): WorkoutVolume {
        return WorkoutVolume(
            workoutId = sessionId,
            totalLoad = calculateWorkoutVolume(exercises),
            calculatedAt = calculatedAt
        )
    }

    /**
     * Calculates total volume performed within the last 7 days.
     */
    fun calculateWeeklyVolume(
        sessions: List<SessionCalendarSummary>,
        nowTimestamp: Long = System.currentTimeMillis()
    ): Double {
        val sevenDaysAgo = nowTimestamp - (7 * 24 * 60 * 60 * 1000L)
        return sessions
            .filter { it.session.startedAt in sevenDaysAgo..nowTimestamp }
            .sumOf { calculateWorkoutVolume(it.exercises) }
    }

    /**
     * Calculates historical accumulated volume across all completed sessions.
     */
    fun calculateTotalVolume(sessions: List<SessionCalendarSummary>): Double {
        return sessions.sumOf { calculateWorkoutVolume(it.exercises) }
    }

    /**
     * Aggregates a VolumeSummary containing session, weekly, and total historical volume.
     */
    fun calculateVolumeSummary(
        sessions: List<SessionCalendarSummary>,
        currentSessionId: Long? = null,
        nowTimestamp: Long = System.currentTimeMillis()
    ): VolumeSummary {
        val sessionVol = if (currentSessionId != null) {
            sessions.find { it.session.id == currentSessionId }?.let { calculateWorkoutVolume(it.exercises) }
                ?: 0.0
        } else {
            sessions.maxByOrNull { it.session.startedAt }?.let { calculateWorkoutVolume(it.exercises) }
                ?: 0.0
        }

        val weeklyVol = calculateWeeklyVolume(sessions, nowTimestamp)
        val totalVol = calculateTotalVolume(sessions)

        return VolumeSummary(
            sessionVolume = sessionVol,
            weeklyVolume = weeklyVol,
            totalVolume = totalVol
        )
    }
}
