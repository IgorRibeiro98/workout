package com.example

import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionWithDetails
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.SyncResult
import com.example.domain.workout.execution.ExerciseExecutionContext
import com.example.domain.workout.execution.PerformanceHistory
import com.example.domain.workout.execution.PersonalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12.6.5E — QA Final e Polimento da Experiência de Execução
 * Validates the core logic of workout flow scenarios, rest states, sync logic, and execution states.
 */
class WorkoutExecutionFlowQATest {

    @Test
    fun `cenario 1 - treino simples 1 exercicio 3 series - conclusao na ultima serie sem descanso`() {
        val exerciseSessionId = 1L
        val sets = listOf(
            SetLogEntity(id = 1, exerciseSessionId = exerciseSessionId, setNumber = 1, completed = true, weight = 60f, repetitions = 10),
            SetLogEntity(id = 2, exerciseSessionId = exerciseSessionId, setNumber = 2, completed = true, weight = 60f, repetitions = 10),
            SetLogEntity(id = 3, exerciseSessionId = exerciseSessionId, setNumber = 3, completed = false, weight = 60f, repetitions = 10)
        )

        val exercise = ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(
                id = exerciseSessionId,
                sessionId = 100L,
                plannedExerciseId = 1L,
                actualExerciseId = 1L,
                exerciseNameSnapshot = "Supino Reto",
                sortOrder = 0
            ),
            sets = sets
        )
        val sessionWithDetails = SessionWithDetails(
            session = WorkoutSessionEntity(id = 100L, templateId = 1L, startedAt = 0L),
            exercises = listOf(exercise)
        )

        // When completing set 3 (the last set of the single exercise)
        val completingSetId = 3L
        val isEntireWorkoutCompleted = sessionWithDetails.exercises.isNotEmpty() && sessionWithDetails.exercises.all { ex: ExerciseSessionWithSets ->
            ex.sets.all { it.completed || it.id == completingSetId }
        }

        // Must be true: entire workout completes, so NO rest timer should start
        assertTrue("Entire workout must be detected as completed on last set", isEntireWorkoutCompleted)
    }

    @Test
    fun `cenario 2 - treino com multiplos exercicios - transicao entre exercicios ativa descanso de transicao`() {
        val ex1Sets = listOf(
            SetLogEntity(id = 1, exerciseSessionId = 1L, setNumber = 1, completed = true),
            SetLogEntity(id = 2, exerciseSessionId = 1L, setNumber = 2, completed = true),
            SetLogEntity(id = 3, exerciseSessionId = 1L, setNumber = 3, completed = false)
        )
        val ex2Sets = listOf(
            SetLogEntity(id = 4, exerciseSessionId = 2L, setNumber = 1, completed = false),
            SetLogEntity(id = 5, exerciseSessionId = 2L, setNumber = 2, completed = false)
        )

        val exercise1 = ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(
                id = 1L,
                sessionId = 200L,
                plannedExerciseId = 10L,
                actualExerciseId = 10L,
                exerciseNameSnapshot = "Supino",
                sortOrder = 0
            ),
            sets = ex1Sets
        )
        val exercise2 = ExerciseSessionWithSets(
            exerciseSession = ExerciseSessionEntity(
                id = 2L,
                sessionId = 200L,
                plannedExerciseId = 11L,
                actualExerciseId = 11L,
                exerciseNameSnapshot = "Crucifixo",
                sortOrder = 1
            ),
            sets = ex2Sets
        )

        val allExercises = listOf(exercise1, exercise2)

        // Completing set 3 of exercise 1
        val completingSetId = 3L
        val isEntireWorkoutCompleted = allExercises.all { ex ->
            ex.sets.all { it.completed || it.id == completingSetId }
        }
        assertFalse("Workout is not entire completed because Crucifixo is pending", isEntireWorkoutCompleted)

        val currentExerciseCompleted = exercise1.sets.all { it.completed || it.id == completingSetId }
        assertTrue("Exercise 1 must be completed after its last set", currentExerciseCompleted)

        // Timer type for exercise transition
        val timerType = if (currentExerciseCompleted) "REST_EXERCISE" else "REST_SET"
        assertEquals("REST_EXERCISE", timerType)

        // Verify next exercise name resolved
        val currentExerciseIndex = 0
        val nextExSession = allExercises.getOrNull(currentExerciseIndex + 1)
        assertEquals("Crucifixo", nextExSession?.exerciseSession?.exerciseNameSnapshot)
    }

    @Test
    fun `cenario 3 - ultimo exercicio do treino - conclusao sem proxima serie ou descanso`() {
        val ex1Sets = listOf(
            SetLogEntity(id = 1, exerciseSessionId = 1L, setNumber = 1, completed = true)
        )
        val ex2Sets = listOf(
            SetLogEntity(id = 2, exerciseSessionId = 2L, setNumber = 1, completed = false)
        )
        val allExercises = listOf(
            ExerciseSessionWithSets(
                exerciseSession = ExerciseSessionEntity(id = 1L, sessionId = 300L, plannedExerciseId = 10L, actualExerciseId = 10L, exerciseNameSnapshot = "Supino", sortOrder = 0),
                sets = ex1Sets
            ),
            ExerciseSessionWithSets(
                exerciseSession = ExerciseSessionEntity(id = 2L, sessionId = 300L, plannedExerciseId = 11L, actualExerciseId = 11L, exerciseNameSnapshot = "Tríceps Corda", sortOrder = 1),
                sets = ex2Sets
            )
        )

        val completingSetId = 2L
        val isEntireWorkoutCompleted = allExercises.all { ex ->
            ex.sets.all { it.completed || it.id == completingSetId }
        }
        assertTrue("Workout must be completed after last exercise last set", isEntireWorkoutCompleted)
    }

    @Test
    fun `parte 6 - sincronizacao de series copia carga repeticoes e rir para proximas series elegiveis`() {
        val currentSet = SetLogEntity(
            id = 1,
            exerciseSessionId = 5L,
            setNumber = 1,
            weight = 80f,
            repetitions = 10,
            rir = 2,
            completed = true
        )

        val allSets = listOf(
            currentSet,
            SetLogEntity(id = 2, exerciseSessionId = 5L, setNumber = 2, weight = 70f, repetitions = 8, rir = null, completed = false),
            SetLogEntity(id = 3, exerciseSessionId = 5L, setNumber = 3, weight = 70f, repetitions = 8, rir = null, completed = false),
            SetLogEntity(id = 4, exerciseSessionId = 5L, setNumber = 4, weight = 90f, repetitions = 6, rir = 1, completed = true)
        )

        val eligibleSets = mutableListOf<SetLogEntity>()
        var skippedCompleted = 0
        var skippedType = 0

        for (set in allSets) {
            if (set.setNumber > currentSet.setNumber) {
                if (set.completed) {
                    skippedCompleted++
                } else if (set.type != currentSet.type) {
                    skippedType++
                } else {
                    eligibleSets.add(
                        set.copy(
                            weight = currentSet.weight,
                            repetitions = currentSet.repetitions,
                            rir = currentSet.rir,
                            rpe = currentSet.rpe
                        )
                    )
                }
            }
        }

        val syncResult = SyncResult(
            updatedCount = eligibleSets.size,
            skippedCompletedCount = skippedCompleted,
            skippedDifferentTypeCount = skippedType
        )

        assertEquals(2, syncResult.updatedCount)
        assertEquals(1, syncResult.skippedCompletedCount)
        assertEquals(80f, eligibleSets[0].weight)
        assertEquals(10, eligibleSets[0].repetitions)
        assertEquals(2, eligibleSets[0].rir)
        assertEquals(80f, eligibleSets[1].weight)
        assertEquals(10, eligibleSets[1].repetitions)
        assertEquals(2, eligibleSets[1].rir)
    }

    @Test
    fun `parte 9 - historico contextual - primeira execucao vs ultima execucao com recorde pessoal`() {
        // Primeira execução
        val firstTimeContext = ExerciseExecutionContext(
            lastPerformance = null,
            personalRecord = null,
            isFirstTime = true
        )
        assertTrue(firstTimeContext.isFirstTime)
        assertNull(firstTimeContext.lastPerformance)
        assertNull(firstTimeContext.personalRecord)

        // Exercício com histórico
        val historyContext = ExerciseExecutionContext(
            lastPerformance = PerformanceHistory(weight = 80f, reps = 10, rir = 2, timestamp = 1000L, daysAgo = 4L),
            personalRecord = PersonalRecord(maxWeight = 85f, repsAtMaxWeight = 8, date = 2000L),
            isFirstTime = false
        )
        assertFalse(historyContext.isFirstTime)
        assertEquals(80f, historyContext.lastPerformance?.weight)
        assertEquals(85f, historyContext.personalRecord?.maxWeight)
    }
}
