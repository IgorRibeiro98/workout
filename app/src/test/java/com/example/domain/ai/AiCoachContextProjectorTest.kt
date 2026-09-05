package com.example.domain.ai

import com.example.domain.ai.AiCoachTestData.completedSession
import com.example.domain.ai.AiCoachTestData.exercise
import com.example.domain.ai.AiCoachTestData.personalRecord
import com.example.domain.ai.AiCoachTestData.setLog
import com.example.domain.ai.AiCoachTestData.templateExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O contexto do Coach é um recorte, não uma cópia do banco.
 *
 * O que estes testes protegem: identidade canônica preservada, histórico limitado e campo
 * ausente que continua ausente.
 */
class AiCoachContextProjectorTest {

    private val bench = exercise(id = 1L, name = "Supino reto com barra", canonicalId = "supino-reto-barra")
    private val userCreated = exercise(id = 2L, name = "Meu exercício", canonicalId = null)
    private val exercisesById = mapOf(bench.id to bench, userCreated.id to userCreated)

    @Test
    fun `exercicio do treino mantem o id canonico e nao o nome`() {
        val context = AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id, plannedWeight = 60f)),
            recentSessions = emptyList(),
            personalRecordsByExerciseId = emptyMap(),
            weeklyGoal = 4,
            bodyWeightKg = 80f
        )

        val planned = context.currentWorkout!!.exercises.single()
        assertEquals("supino-reto-barra", planned.exerciseId)
        assertEquals("Supino reto com barra", planned.name)
        assertEquals(3, planned.targetSets)
        assertEquals(60f, planned.plannedWeightKg)
        assertTrue("supino-reto-barra" in context.knownExerciseIds)
    }

    @Test
    fun `exercicio sem canonicalId recebe identidade local deterministica e nunca o nome`() {
        val context = AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = userCreated.id)),
            recentSessions = emptyList(),
            personalRecordsByExerciseId = emptyMap(),
            weeklyGoal = null,
            bodyWeightKg = null
        )

        val planned = context.currentWorkout!!.exercises.single()
        assertEquals("${AiCoachContextProjector.LOCAL_ID_PREFIX}2", planned.exerciseId)
    }

    @Test
    fun `historico enviado respeita o limite configurado`() {
        val sessions = (1..12).map { index ->
            completedSession(
                sessionId = index.toLong(),
                startedAt = index * 1_000_000L,
                finishedAt = index * 1_000_000L + 3_600_000L,
                exerciseRowId = bench.id,
                exerciseName = bench.name,
                sets = listOf(setLog(index.toLong() * 100, 1, 60f, 10))
            )
        }

        val context = AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = null,
            templateExercises = emptyList(),
            recentSessions = sessions,
            personalRecordsByExerciseId = emptyMap(),
            weeklyGoal = null,
            bodyWeightKg = null
        )

        assertEquals(AiModelConfig.RECENT_SESSIONS_LIMIT, context.recentSessions.size)
        assertEquals(AiModelConfig.RECENT_SESSIONS_LIMIT, context.athlete.completedSessionsInWindow)
        // As mais recentes primeiro: a sessão 12 entra, a sessão 1 não.
        assertEquals(12L * 1_000_000L + 3_600_000L, context.recentSessions.first().finishedAtEpochMs)
    }

    @Test
    fun `campos que o app nao possui permanecem ausentes`() {
        val context = AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = null,
            templateExercises = emptyList(),
            recentSessions = emptyList(),
            personalRecordsByExerciseId = emptyMap(),
            weeklyGoal = null,
            bodyWeightKg = null
        )

        assertNull(context.currentWorkout)
        assertNull(context.athlete.weeklyGoal)
        assertNull(context.athlete.bodyWeightKg)
        assertTrue(context.recentSessions.isEmpty())
        assertTrue(context.personalRecords.isEmpty())
    }

    @Test
    fun `sessao projetada resume execucao sem carregar series brutas`() {
        val session = completedSession(
            sessionId = 7L,
            startedAt = 0L,
            finishedAt = 3_600_000L,
            exerciseRowId = bench.id,
            exerciseName = bench.name,
            sets = listOf(
                setLog(700L, 1, 60f, 10),
                setLog(700L, 2, 70f, 8),
                setLog(700L, 3, 70f, 6, completed = false)
            )
        )

        val context = AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = null,
            templateExercises = emptyList(),
            recentSessions = listOf(session),
            personalRecordsByExerciseId = emptyMap(),
            weeklyGoal = null,
            bodyWeightKg = null
        )

        val executed = context.recentSessions.single().exercises.single()
        assertEquals("supino-reto-barra", executed.exerciseId)
        assertEquals(2, executed.completedSets)
        assertEquals(70f, executed.maxWeightKg)
        assertEquals(18, executed.totalReps)
        assertEquals(60, context.recentSessions.single().durationMinutes)
    }

    @Test
    fun `PR de exercicio fora do contexto nao e enviado`() {
        val session = completedSession(
            sessionId = 1L,
            startedAt = 0L,
            finishedAt = 3_600_000L,
            exerciseRowId = bench.id,
            exerciseName = bench.name,
            sets = listOf(setLog(100L, 1, 60f, 10))
        )

        val context = AiCoachContextProjector.project(
            exercisesById = exercisesById,
            templateName = null,
            templateExercises = emptyList(),
            recentSessions = listOf(session),
            personalRecordsByExerciseId = mapOf(
                bench.id to personalRecord(bench.id, 100f, 500L),
                userCreated.id to personalRecord(userCreated.id, 50f, 600L)
            ),
            weeklyGoal = null,
            bodyWeightKg = null
        )

        val record = context.personalRecords.single()
        assertEquals("supino-reto-barra", record.exerciseId)
        assertEquals(100f, record.value)
    }
}
