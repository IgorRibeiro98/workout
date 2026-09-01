package com.example.domain.evolution.calculator

import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SessionWithDetails
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary

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
     * Calculates weight evolution (variation) between first and current weight.
     * Formula: currentWeight - firstWeight
     *
     * Example:
     * 40kg -> 70kg = +30kg
     */
    fun calculateWeightEvolution(firstWeight: Float?, currentWeight: Float?): Float? {
        if (firstWeight == null || currentWeight == null) return null
        return currentWeight - firstWeight
    }

    /**
     * Calculates percentage growth between first and current weight.
     * Example: 10kg -> 20kg (+100%), 80kg -> 90kg (+12.5%)
     */
    fun calculatePercentageGrowth(firstWeight: Float?, currentWeight: Float?): Float {
        if (firstWeight == null || currentWeight == null || firstWeight <= 0f) return 0f
        return ((currentWeight - firstWeight) / firstWeight) * 100f
    }

    /**
     * Aggregates full workout performance summary.
     */
    fun calculateWorkoutPerformanceSummary(sessions: List<SessionCalendarSummary>): WorkoutPerformanceSummary {
        val totalSessions = sessions.size
        var totalExercises = 0
        var totalSets = 0
        var totalRepetitions = 0
        var totalVolume = 0.0
        var totalDurationMinutes = 0
        var sessionsWithDuration = 0

        for (item in sessions) {
            val session = item.session
            if (session.startedAt > 0 && session.finishedAt != null && session.finishedAt > session.startedAt) {
                val durationMinutes = ((session.finishedAt - session.startedAt) / 60000L).toInt()
                if (durationMinutes in 1..360) {
                    totalDurationMinutes += durationMinutes
                    sessionsWithDuration++
                }
            }

            totalExercises += item.exercises.size
            for (exercise in item.exercises) {
                val completedSets = exercise.sets.filter { it.completed }
                totalSets += completedSets.size
                totalRepetitions += completedSets.sumOf { it.repetitions }
                totalVolume += com.example.domain.performance.calculator.VolumeCalculator.calculateSetsVolume(exercise.sets)
            }
        }

        val avgDuration = if (sessionsWithDuration > 0) {
            totalDurationMinutes / sessionsWithDuration
        } else {
            null
        }

        return WorkoutPerformanceSummary(
            totalSessions = totalSessions,
            totalExercises = totalExercises,
            totalSets = totalSets,
            totalRepetitions = totalRepetitions,
            totalVolume = totalVolume.toFloat(),
            averageSessionDuration = avgDuration
        )
    }

    /**
     * Calculates exercise performance evolutions across completed sessions.
     * Sorted by highest percentage evolution.
     */
    fun calculateExerciseEvolutions(sessions: List<SessionCalendarSummary>): List<ExercisePerformanceEvolution> {
        if (sessions.isEmpty()) return emptyList()

        // Sort chronologically (oldest to newest)
        val chronologicalSessions = sessions.sortedBy { it.session.startedAt }

        // Group executions by exercise key
        data class SessionExerciseRecord(
            val sessionTimestamp: Long,
            val maxWeight: Float?,
            val volume: Float,
            val completedSetsCount: Int
        )

        val exerciseRecordsMap = mutableMapOf<String, Pair<String, MutableList<SessionExerciseRecord>>>()

        for (summary in chronologicalSessions) {
            val sessionTime = summary.session.startedAt
            for (exerciseSession in summary.exercises) {
                val rawId = exerciseSession.exerciseSession.actualExerciseId 
                    ?: exerciseSession.exerciseSession.plannedExerciseId
                val exerciseName = exerciseSession.exerciseSession.exerciseNameSnapshot.ifBlank { "Exercício" }
                val key = rawId?.toString() ?: exerciseName.trim().lowercase()

                val completedSets = exerciseSession.sets.filter { it.completed }
                val setsWithWeight = completedSets.filter { it.weight > 0f }

                val maxWeight = if (setsWithWeight.isNotEmpty()) {
                    setsWithWeight.maxOf { it.weight }
                } else null

                val sessionVolume = completedSets.sumOf { (it.weight * it.repetitions).toDouble() }.toFloat()

                val entry = exerciseRecordsMap.getOrPut(key) {
                    exerciseName to mutableListOf()
                }
                entry.second.add(
                    SessionExerciseRecord(
                        sessionTimestamp = sessionTime,
                        maxWeight = maxWeight,
                        volume = sessionVolume,
                        completedSetsCount = completedSets.size
                    )
                )
            }
        }

        val results = mutableListOf<ExercisePerformanceEvolution>()

        for ((key, pair) in exerciseRecordsMap) {
            val (name, records) = pair
            val weightedRecords = records.filter { it.maxWeight != null && it.maxWeight > 0f }

            val firstWeight = weightedRecords.firstOrNull()?.maxWeight
            val currentWeight = weightedRecords.lastOrNull()?.maxWeight
            val bestWeight = weightedRecords.maxOfOrNull { it.maxWeight ?: 0f }
            val weightVariation = calculateWeightEvolution(firstWeight, currentWeight)
            val totalVolume = records.sumOf { it.volume.toDouble() }.toFloat()
            val totalExecutions = records.size

            results.add(
                ExercisePerformanceEvolution(
                    exerciseId = key,
                    exerciseName = name,
                    firstWeight = firstWeight,
                    currentWeight = currentWeight,
                    bestWeight = bestWeight,
                    weightVariation = weightVariation,
                    totalExecutions = totalExecutions,
                    totalVolume = totalVolume
                )
            )
        }

        // Sort: Exercises with highest percentage growth first, then highest absolute growth, then total volume
        return results.sortedWith(
            compareByDescending<ExercisePerformanceEvolution> { evolution ->
                calculatePercentageGrowth(evolution.firstWeight, evolution.currentWeight)
            }.thenByDescending { evolution ->
                evolution.weightVariation ?: 0f
            }.thenByDescending { evolution ->
                evolution.totalVolume
            }
        )
    }

    /**
     * Extracts personal records (PRs) from completed sessions.
     */
    fun calculatePersonalRecords(sessions: List<SessionCalendarSummary>): List<PersonalRecord> {
        if (sessions.isEmpty()) return emptyList()

        val bestSetsByExercise = mutableMapOf<String, PersonalRecord>()

        for (summary in sessions) {
            val sessionTime = summary.session.startedAt
            for (exerciseSession in summary.exercises) {
                val rawId = exerciseSession.exerciseSession.actualExerciseId 
                    ?: exerciseSession.exerciseSession.plannedExerciseId
                val exerciseName = exerciseSession.exerciseSession.exerciseNameSnapshot.ifBlank { "Exercício" }
                val key = rawId?.toString() ?: exerciseName.trim().lowercase()

                val completedSets = exerciseSession.sets.filter { it.completed && it.weight > 0f }

                for (set in completedSets) {
                    val currentBest = bestSetsByExercise[key]
                    if (currentBest == null || set.weight > currentBest.maxWeight ||
                        (set.weight == currentBest.maxWeight && set.repetitions > currentBest.repetitions)
                    ) {
                        bestSetsByExercise[key] = PersonalRecord(
                            exerciseId = key,
                            exerciseName = exerciseName,
                            maxWeight = set.weight,
                            repetitions = set.repetitions,
                            achievedAt = sessionTime
                        )
                    }
                }
            }
        }

        return bestSetsByExercise.values
            .sortedWith(compareByDescending<PersonalRecord> { it.maxWeight }.thenByDescending { it.achievedAt })
    }

    /**
     * Aggregates volume history points per completed session chronologically.
     */
    fun calculateVolumeHistory(sessions: List<SessionCalendarSummary>): List<VolumePoint> {
        if (sessions.isEmpty()) return emptyList()

        val sortedSessions = sessions.sortedBy { it.session.startedAt }
        val points = mutableListOf<VolumePoint>()

        for (summary in sortedSessions) {
            var sessionVolume = 0.0
            for (exercise in summary.exercises) {
                val completedSets = exercise.sets.filter { it.completed }
                for (set in completedSets) {
                    sessionVolume += (set.weight * set.repetitions).toDouble()
                }
            }
            if (sessionVolume > 0f) {
                points.add(
                    VolumePoint(
                        date = summary.session.startedAt,
                        volume = sessionVolume.toFloat()
                    )
                )
            }
        }

        return points
    }

    /**
     * Extracts chronological strength progression points (date, weight, repetitions) for a given exercise.
     */
    fun calculateExerciseStrengthHistory(
        sessions: List<SessionCalendarSummary>,
        exerciseId: String
    ): List<StrengthPoint> {
        if (sessions.isEmpty() || exerciseId.isBlank()) return emptyList()

        val sortedSessions = sessions.sortedBy { it.session.startedAt }
        val points = mutableListOf<StrengthPoint>()

        for (summary in sortedSessions) {
            val sessionTime = summary.session.startedAt
            for (exerciseSession in summary.exercises) {
                val rawId = exerciseSession.exerciseSession.actualExerciseId 
                    ?: exerciseSession.exerciseSession.plannedExerciseId
                val exerciseName = exerciseSession.exerciseSession.exerciseNameSnapshot.ifBlank { "Exercício" }
                val key = rawId?.toString() ?: exerciseName.trim().lowercase()

                val isMatch = key.equals(exerciseId, ignoreCase = true) ||
                        exerciseName.equals(exerciseId, ignoreCase = true) ||
                        (rawId != null && rawId.toString() == exerciseId) ||
                        (exerciseSession.exerciseSession.actualExerciseId?.toString() == exerciseId) ||
                        (exerciseSession.exerciseSession.plannedExerciseId?.toString() == exerciseId)

                if (isMatch) {
                    val completedSets = exerciseSession.sets.filter { it.completed && it.weight > 0f }
                    val maxWeightSet = completedSets.maxWithOrNull(
                        compareBy<com.example.data.local.SetLogEntity> { it.weight }.thenBy { it.repetitions }
                    )

                    if (maxWeightSet != null) {
                        points.add(
                            StrengthPoint(
                                date = sessionTime,
                                weight = maxWeightSet.weight,
                                repetitions = maxWeightSet.repetitions
                            )
                        )
                    }
                }
            }
        }

        return points
    }

    /**
     * Backward-compatible helper for legacy callers
     */
    fun calculateFromCalendarSummaries(sessions: List<SessionCalendarSummary>): PerformanceEvolution {
        val summary = calculateWorkoutPerformanceSummary(sessions)
        return PerformanceEvolution(
            totalSessions = summary.totalSessions,
            totalExercises = summary.totalExercises,
            totalSets = summary.totalSets,
            totalRepetitions = summary.totalRepetitions,
            totalVolume = summary.totalVolume
        )
    }

    /**
     * Backward-compatible helper for legacy callers
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
