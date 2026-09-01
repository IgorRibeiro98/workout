package com.example.domain.evolution.calculator

import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SessionWithDetails
import com.example.domain.evolution.model.PerformanceEvolution

object PerformanceCalculator {

    /**
     * Calculates tonnage volume for a given weight, sets count, and repetitions count.
     * Formula: weightKg * sets * reps
     *
     * Example:
     * 70kg, 3 sets, 10 reps = 2100kg volume
     */
    fun calculateVolume(weightKg: Float, sets: Int, reps: Int): Float {
        if (weightKg <= 0f || sets <= 0 || reps <= 0) return 0f
        return weightKg * sets * reps
    }

    /**
     * Aggregates performance evolution metrics from completed workout session calendar summaries.
     */
    fun calculateFromCalendarSummaries(sessions: List<SessionCalendarSummary>): PerformanceEvolution {
        val totalSessions = sessions.size
        var totalExercises = 0
        var totalSets = 0
        var totalRepetitions = 0
        var totalVolume = 0.0

        for (session in sessions) {
            totalExercises += session.exercises.size
            for (exercise in session.exercises) {
                val completedSets = exercise.sets.filter { it.completed }
                totalSets += completedSets.size
                for (set in completedSets) {
                    totalRepetitions += set.repetitions
                    totalVolume += (set.weight * set.repetitions).toDouble()
                }
            }
        }

        return PerformanceEvolution(
            totalSessions = totalSessions,
            totalExercises = totalExercises,
            totalSets = totalSets,
            totalRepetitions = totalRepetitions,
            totalVolume = totalVolume.toFloat()
        )
    }

    /**
     * Aggregates performance evolution metrics from detailed workout sessions.
     */
    fun calculateFromSessionDetails(sessions: List<SessionWithDetails>): PerformanceEvolution {
        val totalSessions = sessions.size
        var totalExercises = 0
        var totalSets = 0
        var totalRepetitions = 0
        var totalVolume = 0.0

        for (session in sessions) {
            totalExercises += session.exercises.size
            for (exercise in session.exercises) {
                val completedSets = exercise.sets.filter { it.completed }
                totalSets += completedSets.size
                for (set in completedSets) {
                    totalRepetitions += set.repetitions
                    totalVolume += (set.weight * set.repetitions).toDouble()
                }
            }
        }

        return PerformanceEvolution(
            totalSessions = totalSessions,
            totalExercises = totalExercises,
            totalSets = totalSets,
            totalRepetitions = totalRepetitions,
            totalVolume = totalVolume.toFloat()
        )
    }
}
