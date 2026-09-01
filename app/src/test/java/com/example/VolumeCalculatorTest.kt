package com.example

import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetLogEntity
import com.example.data.local.SetType
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.performance.calculator.VolumeCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeCalculatorTest {

    @Test
    fun test1_singleSetVolumeCalculation() {
        val set = SetLogEntity(
            exerciseSessionId = 1L,
            setNumber = 1,
            weight = 100f,
            repetitions = 10,
            completed = true,
            type = SetType.NORMAL.name
        )
        val volume = VolumeCalculator.calculateSetsVolume(listOf(set))
        assertEquals(1000.0, volume, 0.001)
    }

    @Test
    fun test2_multipleSetsVolumeCalculation() {
        val sets = listOf(
            SetLogEntity(exerciseSessionId = 1L, setNumber = 1, weight = 50f, repetitions = 10, completed = true, type = SetType.NORMAL.name),
            SetLogEntity(exerciseSessionId = 1L, setNumber = 2, weight = 50f, repetitions = 10, completed = true, type = SetType.NORMAL.name),
            SetLogEntity(exerciseSessionId = 1L, setNumber = 3, weight = 50f, repetitions = 10, completed = true, type = SetType.NORMAL.name)
        )
        val volume = VolumeCalculator.calculateSetsVolume(sets)
        assertEquals(1500.0, volume, 0.001)
    }

    @Test
    fun test3_fullWorkoutVolumeCalculation() {
        val benchPressSets = listOf(
            SetLogEntity(exerciseSessionId = 1L, setNumber = 1, weight = 60f, repetitions = 10, completed = true, type = SetType.NORMAL.name), // 600
            SetLogEntity(exerciseSessionId = 1L, setNumber = 2, weight = 60f, repetitions = 10, completed = true, type = SetType.NORMAL.name), // 600
            SetLogEntity(exerciseSessionId = 1L, setNumber = 3, weight = 60f, repetitions = 10, completed = true, type = SetType.NORMAL.name)  // 600
        ) // Sum = 1800

        val rowSets = listOf(
            SetLogEntity(exerciseSessionId = 2L, setNumber = 1, weight = 40f, repetitions = 10, completed = true, type = SetType.NORMAL.name), // 400
            SetLogEntity(exerciseSessionId = 2L, setNumber = 2, weight = 40f, repetitions = 10, completed = true, type = SetType.NORMAL.name), // 400
            SetLogEntity(exerciseSessionId = 2L, setNumber = 3, weight = 40f, repetitions = 10, completed = true, type = SetType.NORMAL.name)  // 400
        ) // Sum = 1200

        val exercises = listOf(
            ExerciseSessionWithSets(
                exerciseSession = ExerciseSessionEntity(id = 1, sessionId = 1, plannedExerciseId = 1, actualExerciseId = 1, exerciseNameSnapshot = "Supino"),
                sets = benchPressSets
            ),
            ExerciseSessionWithSets(
                exerciseSession = ExerciseSessionEntity(id = 2, sessionId = 1, plannedExerciseId = 2, actualExerciseId = 2, exerciseNameSnapshot = "Remada"),
                sets = rowSets
            )
        )

        val totalWorkoutVolume = VolumeCalculator.calculateWorkoutVolume(exercises)
        assertEquals(3000.0, totalWorkoutVolume, 0.001)
    }

    @Test
    fun test4_weeklyVolumeHistoryCalculation() {
        val now = System.currentTimeMillis()
        val fourDaysAgo = now - (4 * 24 * 60 * 60 * 1000L)
        val tenDaysAgo = now - (10 * 24 * 60 * 60 * 1000L)

        val week1Sets = listOf(
            SetLogEntity(exerciseSessionId = 1L, setNumber = 1, weight = 100f, repetitions = 100, completed = true, type = SetType.NORMAL.name) // 10000kg
        )
        val week2Sets = listOf(
            SetLogEntity(exerciseSessionId = 2L, setNumber = 1, weight = 150f, repetitions = 100, completed = true, type = SetType.NORMAL.name) // 15000kg
        )

        val sessionWeek1 = SessionCalendarSummary(
            session = WorkoutSessionEntity(id = 1, templateId = 1, startedAt = tenDaysAgo, finishedAt = tenDaysAgo + 3600000L),
            checkIn = null,
            exercises = listOf(
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(id = 1, sessionId = 1, plannedExerciseId = 1, actualExerciseId = 1, exerciseNameSnapshot = "Exercise W1"),
                    sets = week1Sets
                )
            )
        )

        val sessionWeek2 = SessionCalendarSummary(
            session = WorkoutSessionEntity(id = 2, templateId = 1, startedAt = fourDaysAgo, finishedAt = fourDaysAgo + 3600000L),
            checkIn = null,
            exercises = listOf(
                ExerciseSessionWithSets(
                    exerciseSession = ExerciseSessionEntity(id = 2, sessionId = 2, plannedExerciseId = 1, actualExerciseId = 1, exerciseNameSnapshot = "Exercise W2"),
                    sets = week2Sets
                )
            )
        )

        val sessions = listOf(sessionWeek1, sessionWeek2)
        val currentWeeklyVolume = VolumeCalculator.calculateWeeklyVolume(sessions, nowTimestamp = now)

        assertEquals(15000.0, currentWeeklyVolume, 0.001)
    }

    @Test
    fun test5_bodyweightExerciseZeroLoadDoesNotCrash() {
        val bodyweightSets = listOf(
            SetLogEntity(exerciseSessionId = 1L, setNumber = 1, weight = 0f, repetitions = 15, completed = true, type = SetType.NORMAL.name),
            SetLogEntity(exerciseSessionId = 1L, setNumber = 2, weight = 0f, repetitions = 12, completed = true, type = SetType.NORMAL.name)
        )
        val volume = VolumeCalculator.calculateSetsVolume(bodyweightSets)
        assertEquals(0.0, volume, 0.001)

        val setNullWeight = VolumeCalculator.calculateSetVolume(weight = null, repetitions = 10)
        assertEquals(0.0, setNullWeight, 0.001)
    }
}
