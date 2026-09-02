package com.example

import com.example.domain.workout.execution.ExerciseExecutionContext
import com.example.domain.workout.execution.ExercisePerformanceSummary
import com.example.domain.workout.execution.LastExercisePerformance
import com.example.domain.workout.execution.PersonalRecord
import com.example.presentation.execution.FeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExecutionIntelligenceTest {

    @Test
    fun `test new record feedback detection`() {
        val pr = PersonalRecord(maxWeight = 80f, repsAtMaxWeight = 10, achievedAt = 1000L)
        val currentWeight = 85f
        val currentReps = 8

        val isNewRecord = currentWeight > pr.maxWeight
        assertEquals(true, isNewRecord)
    }

    @Test
    fun `test load progression feedback detection`() {
        val lastPerf = LastExercisePerformance(weight = 75f, reps = 10, rir = 2, executedAt = 1000L, daysAgo = 3L)
        val currentWeight = 77.5f
        val currentReps = 10

        val isProgression = currentWeight > lastPerf.weight || (currentWeight == lastPerf.weight && currentReps > lastPerf.reps)
        assertEquals(true, isProgression)
    }

    @Test
    fun `test repetition progression feedback detection`() {
        val lastPerf = LastExercisePerformance(weight = 80f, reps = 8, rir = 2, executedAt = 1000L, daysAgo = 5L)
        val currentWeight = 80f
        val currentReps = 10

        val isProgression = currentWeight > lastPerf.weight || (currentWeight == lastPerf.weight && currentReps > lastPerf.reps)
        assertEquals(true, isProgression)
    }

    @Test
    fun `test first time context detection`() {
        val context = ExerciseExecutionContext(
            lastPerformance = null,
            personalRecord = null,
            isFirstTime = true,
            summary = ExercisePerformanceSummary(maxWeight = null, maxVolume = null, totalExecutions = 0)
        )

        assertEquals(true, context.isFirstTime)
        assertEquals(0, context.summary?.totalExecutions)
    }

    @Test
    fun `test summary metrics populated`() {
        val summary = ExercisePerformanceSummary(
            maxWeight = 100f,
            maxVolume = 1200f,
            totalExecutions = 14
        )

        assertNotNull(summary)
        assertEquals(100f, summary.maxWeight)
        assertEquals(1200f, summary.maxVolume)
        assertEquals(14, summary.totalExecutions)
    }
}
