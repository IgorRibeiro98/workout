package com.example.domain.ai

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ai.WorkoutAiAdaptationContextBuilder
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.local.XpTransactionEntity
import com.example.domain.ai.CoachExplanationTestData.advice
import com.example.domain.ai.CoachExplanationTestData.explanationResponse
import com.example.domain.ai.CoachExplanationTestData.generatedDraft
import com.example.domain.ai.CoachExplanationTestData.preferences
import com.example.domain.ai.CoachExplanationTestData.progress
import com.example.domain.ai.WorkoutGenerationTestData.catalogExercise
import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachExplanationResult
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.domain.ai.usecase.ExplainCoachDecisionUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Explicar não escreve. Nunca.
 *
 * Este teste roda os quatro `EXPLAIN_*` contra o banco real do Spark e compara uma impressão do
 * domínio inteiro antes e depois: treino, sessão concluída, séries, recordes, XP e conquistas.
 * A garantia estrutural é anterior ao teste — o caso de uso só recebe leitura — mas a regra é
 * bloqueante o bastante para ser verificada e não apenas argumentada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ExplainReadOnlyTest {

    private lateinit var database: AppDatabase
    private lateinit var contextBuilder: WorkoutAiAdaptationContextBuilder

    private var templateId: Long = 0L
    private var supinoRowId: Long = 0L
    private var sessionId: Long = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.workoutDao()
        contextBuilder = WorkoutAiAdaptationContextBuilder(dao)

        supinoRowId = dao.insertExercise(
            catalogExercise(0, "Supino reto com barra", "supino-reto-barra", "Peitoral")
        )
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Peito", shortIdentifier = "A")
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateId,
                exerciseId = supinoRowId,
                sortOrder = 0,
                targetSets = 4,
                minReps = 8,
                maxReps = 12,
                restDurationSeconds = 90,
                plannedWeight = 60f
            )
        )

        val startedAt = System.currentTimeMillis() - 2 * 86_400_000L
        sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = startedAt,
                finishedAt = startedAt + 3_600_000L,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Peito"
            )
        )
        val exerciseSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = supinoRowId,
                actualExerciseId = supinoRowId,
                exerciseNameSnapshot = "Supino reto com barra"
            )
        )
        dao.insertSetLogs(
            (1..4).map { setNumber ->
                SetLogEntity(
                    exerciseSessionId = exerciseSessionId,
                    setNumber = setNumber,
                    weight = 60f,
                    repetitions = 10,
                    completed = true
                )
            }
        )
        dao.insertPersonalRecord(
            PersonalRecordEntity(
                exerciseId = supinoRowId,
                date = startedAt,
                prType = PRType.MAX_WEIGHT,
                value = 60f
            )
        )
        database.xpTransactionDao().insertTransaction(
            XpTransactionEntity(
                id = "xp-1",
                eventId = "event-1",
                amount = 120,
                reason = "WORKOUT_COMPLETED",
                createdAt = startedAt
            )
        )
    }

    @After
    fun tearDown() = database.close()

    /** Uma impressão do domínio inteiro: se qualquer coisa mudar, a string muda. */
    private suspend fun domainSnapshot(): String {
        val dao = database.workoutDao()
        return buildString {
            append(dao.getTemplateById(templateId))
            dao.getTemplateExercisesWithDetails(templateId).forEach { append(it.templateExercise) }
            val session = dao.getSessionWithDetails(sessionId)!!
            append(session.session)
            session.sortedExercises.forEach { exercise ->
                append(exercise.exerciseSession)
                exercise.sets.sortedBy { it.setNumber }.forEach { append(it) }
            }
            append(dao.getHighestPR(supinoRowId, PRType.MAX_WEIGHT.name))
            append(dao.getAllExercisesList().size)
            append(database.achievementDao().getUnlocks())
        }
    }

    private suspend fun totalXp(): Int =
        database.xpTransactionDao().getTotalXp().first() ?: 0

    private fun loadChange() = WorkoutAdaptationChange(
        id = WorkoutAdaptationChange.idOf(WorkoutAdaptationType.ADJUST_LOAD, "supino-reto-barra"),
        type = WorkoutAdaptationType.ADJUST_LOAD,
        exerciseId = "supino-reto-barra",
        exerciseName = "Supino reto com barra",
        currentValue = WorkoutAdaptationValue.Load(60f),
        suggestedValue = WorkoutAdaptationValue.Load(62.5f),
        reason = "O alvo foi atingido com a mesma carga.",
        evidence = "60 kg em 1 execução concluída",
        confidence = 0.8
    )

    private suspend fun adaptationDraft(): WorkoutAdaptationDraft {
        val source = contextBuilder.build(templateId)!!
        return WorkoutAdaptationDraft(
            requestId = "adaptation-1",
            templateId = templateId,
            templateName = "Peito",
            sourceRevision = source.revision,
            summary = "Uma revisão de carga.",
            dataQuality = AiCoachDataQuality(AiDataQualityLevel.LIMITED, "Baseada no histórico."),
            changes = listOf(loadChange())
        )
    }

    @Test
    fun `os quatro EXPLAIN nao alteram nada no dominio`() = runTest {
        val before = domainSnapshot()
        val xpBefore = totalXp()

        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(
                    explanationResponse(limitations = listOf("Poucos dados."))
                )
            }
        )
        val useCase = ExplainCoachDecisionUseCase(gateway, contextBuilder)

        val results = listOf(
            useCase.explainAnalysisTarget(advice(), "${AiCoachAdvice.RECOMMENDATION_ID_PREFIX}:0"),
            useCase.explainGeneratedWorkout(generatedDraft(), preferences()),
            useCase.explainAdaptationChange(adaptationDraft(), loadChange().id),
            useCase.explainProgress(progress())
        )

        results.forEach { assertTrue("$it", it is AiCoachExplanationResult.Success) }
        assertEquals("nenhuma explicação pode tocar no domínio", before, domainSnapshot())
        assertEquals("explicar não concede XP", xpBefore, totalXp())
    }

    @Test
    fun `explicar nao cria treino a partir de uma proposta`() = runTest {
        val dao = database.workoutDao()
        val programId = dao.getTemplateById(templateId)!!.programId
        val templatesBefore = dao.getTemplatesForProgramSync(programId).size

        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(explanationResponse())
            }
        )

        ExplainCoachDecisionUseCase(gateway).explainGeneratedWorkout(generatedDraft(), preferences())

        assertEquals(
            "explicar uma proposta não pode salvá-la",
            templatesBefore,
            dao.getTemplatesForProgramSync(programId).size
        )
    }

    @Test
    fun `explicar adaptacao nao aplica a mudanca sugerida`() = runTest {
        val dao = database.workoutDao()
        val gateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(explanationResponse())
            }
        )

        ExplainCoachDecisionUseCase(gateway, contextBuilder)
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        // A carga sugerida era 62,5 kg. O treino continua em 60 kg.
        assertEquals(
            60f,
            dao.getTemplateExercisesWithDetails(templateId).single().templateExercise.plannedWeight
        )
    }

    @Test
    fun `explicacao continua funcionando com o provider indisponivel`() = runTest {
        val before = domainSnapshot()
        val gateway = FakeAiCoachGateway()

        val result = ExplainCoachDecisionUseCase(gateway, contextBuilder)
            .explainAdaptationChange(adaptationDraft(), loadChange().id)

        val explanation = (result as AiCoachExplanationResult.Success).explanation
        assertTrue(explanation.evidenceItems.isNotEmpty())
        assertEquals(before, domainSnapshot())
    }
}
