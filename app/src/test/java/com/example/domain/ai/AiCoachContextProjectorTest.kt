package com.example.domain.ai

import com.example.data.local.SessionStatus
import com.example.domain.ai.AiCoachTestData.completedSession
import com.example.domain.ai.AiCoachTestData.exercise
import com.example.domain.ai.AiCoachTestData.executedExercise
import com.example.domain.ai.AiCoachTestData.personalRecord
import com.example.domain.ai.AiCoachTestData.session
import com.example.domain.ai.AiCoachTestData.setLog
import com.example.domain.ai.AiCoachTestData.templateExercise
import com.example.domain.ai.model.AiDataQualityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O contexto da análise é um recorte, não uma cópia do banco.
 *
 * O que estes testes protegem: identidade canônica preservada, histórico limitado, histórico
 * relevante e campo ausente que continua ausente.
 */
class AiCoachContextProjectorTest {

    private val bench = exercise(id = 1L, name = "Supino reto com barra", canonicalId = "supino-reto-barra")
    private val userCreated = exercise(id = 2L, name = "Meu exercício", canonicalId = null)
    private val squat = exercise(id = 3L, name = "Agachamento livre", canonicalId = "agachamento-livre")
    private val exercisesById = mapOf(bench.id to bench, userCreated.id to userCreated, squat.id to squat)

    private fun project(
        templateName: String? = null,
        templateExercises: List<com.example.data.local.WorkoutTemplateExerciseEntity> = emptyList(),
        completedSessions: List<com.example.data.local.SessionCalendarSummary> = emptyList(),
        personalRecords: Map<Long, com.example.data.local.PersonalRecordEntity> = emptyMap(),
        weeklyGoal: Int? = null
    ) = AiCoachContextProjector.project(
        exercisesById = exercisesById,
        templateName = templateName,
        templateExercises = templateExercises,
        completedSessions = completedSessions,
        personalRecordsByExerciseId = personalRecords,
        weeklyGoal = weeklyGoal
    )

    private fun benchSession(index: Int, weight: Float = 60f) = completedSession(
        sessionId = index.toLong(),
        startedAt = index * 1_000_000L,
        finishedAt = index * 1_000_000L + 3_600_000L,
        exerciseRowId = bench.id,
        exerciseName = bench.name,
        sets = listOf(setLog(index.toLong() * 100, 1, weight, 10))
    )

    @Test
    fun `exercicio do treino mantem o id canonico e nao o nome`() {
        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id, plannedWeight = 60f)),
            weeklyGoal = 4
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
        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = userCreated.id))
        )

        val planned = context.currentWorkout!!.exercises.single()
        assertEquals("${AiCoachContextProjector.LOCAL_ID_PREFIX}2", planned.exerciseId)
    }

    @Test
    fun `historico por exercicio respeita o limite configurado`() {
        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id)),
            completedSessions = (1..12).map { benchSession(it) }
        )

        val history = context.exerciseHistory.single()
        assertEquals("supino-reto-barra", history.exerciseId)
        assertEquals(AiModelConfig.HISTORY_PER_EXERCISE_LIMIT, history.executions.size)
        assertEquals(AiModelConfig.HISTORY_PER_EXERCISE_LIMIT, history.sessionsAnalyzed)
        // As mais recentes primeiro: a sessão 12 entra, a sessão 1 não.
        assertEquals(12L * 1_000_000L + 3_600_000L, history.executions.first().finishedAtEpochMs)
    }

    @Test
    fun `historico de exercicio fora do treino analisado nao e enviado`() {
        val squatSessions = (1..4).map { index ->
            completedSession(
                sessionId = 100L + index,
                startedAt = index * 1_000_000L,
                finishedAt = index * 1_000_000L + 3_600_000L,
                exerciseRowId = squat.id,
                exerciseName = squat.name,
                sets = listOf(setLog((100L + index) * 100, 1, 100f, 5))
            )
        }

        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id)),
            completedSessions = squatSessions + benchSession(50)
        )

        assertEquals(listOf("supino-reto-barra"), context.exerciseHistory.map { it.exerciseId })
        assertTrue("agachamento-livre" !in context.knownExerciseIds)
        // Só a sessão que contém o exercício analisado sustenta a análise.
        assertEquals(1, context.evidence.sessionsAnalyzed)
    }

    @Test
    fun `sessao nao concluida nao vira desempenho realizado`() {
        val notCompleted = listOf(
            SessionStatus.PLANNED,
            SessionStatus.IN_PROGRESS,
            SessionStatus.PAUSED,
            SessionStatus.CANCELLED
        ).mapIndexed { index, status ->
            session(
                sessionId = 200L + index,
                startedAt = index * 1_000L,
                finishedAt = null,
                status = status,
                exercises = listOf(
                    executedExercise(
                        exerciseSessionId = (200L + index) * 100,
                        sessionId = 200L + index,
                        exerciseRowId = bench.id,
                        exerciseName = bench.name,
                        sets = listOf(setLog((200L + index) * 100, 1, 999f, 99))
                    )
                )
            )
        }

        // A autoridade de "concluída" é a consulta do WorkoutDao; o projetor recebe apenas o que
        // ela devolveu. Aqui o teste prova o outro lado: nada além dessa lista entra no contexto.
        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id)),
            completedSessions = emptyList()
        )

        assertTrue(notCompleted.isNotEmpty())
        assertTrue(context.exerciseHistory.single().executions.isEmpty())
        assertEquals(0, context.evidence.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.INSUFFICIENT, context.evidence.maxDataQuality)
    }

    @Test
    fun `serie nao concluida nao entra na execucao`() {
        val session = completedSession(
            sessionId = 7L,
            startedAt = 0L,
            finishedAt = 3_600_000L,
            exerciseRowId = bench.id,
            exerciseName = bench.name,
            sets = listOf(
                setLog(700L, 1, 60f, 10),
                setLog(700L, 2, 70f, 8),
                setLog(700L, 3, 90f, 6, completed = false)
            )
        )

        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id)),
            completedSessions = listOf(session)
        )

        val execution = context.exerciseHistory.single().executions.single()
        assertEquals(2, execution.completedSets)
        assertEquals(70f, execution.maxWeightKg)
        assertEquals(18, execution.totalReps)
    }

    @Test
    fun `campos que o app nao possui permanecem ausentes`() {
        val context = project()

        assertNull(context.currentWorkout)
        assertNull(context.athlete.weeklyGoal)
        assertTrue(context.exerciseHistory.isEmpty())
        assertTrue(context.personalRecords.isEmpty())
        assertEquals(0, context.evidence.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.INSUFFICIENT, context.evidence.maxDataQuality)
    }

    @Test
    fun `sem treino em foco o recorte e o ultimo treino concluido`() {
        val benchOnly = benchSession(9)
        val squatOnly = completedSession(
            sessionId = 8L,
            startedAt = 1_000L,
            finishedAt = 2_000L,
            exerciseRowId = squat.id,
            exerciseName = squat.name,
            sets = listOf(setLog(800L, 1, 100f, 5))
        )

        val context = project(completedSessions = listOf(benchOnly, squatOnly))

        assertNull(context.currentWorkout)
        assertEquals(listOf("supino-reto-barra"), context.exerciseHistory.map { it.exerciseId })
    }

    @Test
    fun `qualidade dos dados acompanha quantas sessoes sustentam a analise`() {
        val template = listOf(templateExercise(exerciseId = bench.id))

        assertEquals(
            AiDataQualityLevel.INSUFFICIENT,
            project(templateName = "A", templateExercises = template).evidence.maxDataQuality
        )
        assertEquals(
            AiDataQualityLevel.LIMITED,
            project(
                templateName = "A",
                templateExercises = template,
                completedSessions = listOf(benchSession(1))
            ).evidence.maxDataQuality
        )
        assertEquals(
            AiDataQualityLevel.GOOD,
            project(
                templateName = "A",
                templateExercises = template,
                completedSessions = (1..3).map { benchSession(it) }
            ).evidence.maxDataQuality
        )
    }

    @Test
    fun `PR de exercicio fora do contexto nao e enviado`() {
        val context = project(
            templateName = "Treino A",
            templateExercises = listOf(templateExercise(exerciseId = bench.id)),
            completedSessions = listOf(benchSession(1)),
            personalRecords = mapOf(
                bench.id to personalRecord(bench.id, 100f, 500L),
                squat.id to personalRecord(squat.id, 180f, 600L)
            )
        )

        val record = context.personalRecords.single()
        assertEquals("supino-reto-barra", record.exerciseId)
        assertEquals(100f, record.value)
    }
}
