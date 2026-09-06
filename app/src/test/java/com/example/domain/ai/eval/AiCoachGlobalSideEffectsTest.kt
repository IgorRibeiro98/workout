package com.example.domain.ai.eval

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ai.WorkoutAiAdaptationContextBuilder
import com.example.data.ai.WorkoutAiCoachContextBuilder
import com.example.data.ai.WorkoutAiGenerationContextBuilder
import com.example.data.datastore.SettingsManager
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
import com.example.data.repository.WorkoutRepository
import com.example.domain.ai.AiCoachTestData
import com.example.domain.ai.CoachExplanationTestData
import com.example.domain.ai.FakeAiCoachGateway
import com.example.domain.ai.WorkoutGenerationTestData
import com.example.domain.ai.model.AiCoachExplanationGatewayResult
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiWorkoutAdaptationChangeResponse
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.AiWorkoutAdaptationResponse
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.AdaptWorkoutResult
import com.example.domain.ai.model.GenerateWorkoutResult
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.ai.usecase.AdaptWorkoutUseCase
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import com.example.domain.ai.usecase.ApplyWorkoutAdaptationUseCase
import com.example.domain.ai.usecase.ExplainCoachDecisionUseCase
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import com.example.domain.ai.usecase.SaveGeneratedWorkoutUseCase
import com.example.domain.engine.MuscleGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A regressão global de efeito colateral: rodar **todos** os fluxos do Coach, um atrás do outro,
 * contra o banco real do Spark.
 *
 * Os testes de cada etapa da T14 já cobrem o seu próprio fluxo. Este cobre o que nenhum deles
 * cobre sozinho: depois de analisar, gerar, salvar, adaptar, aplicar e explicar, a sessão
 * concluída continua idêntica e a gamificação continua intacta — XP, conquistas e recordes não
 * se movem por causa da IA.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AiCoachGlobalSideEffectsTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository

    private var programId: Long = 0L
    private var templateId: Long = 0L
    private var sessionId: Long = 0L
    private var supinoRowId: Long = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.workoutDao()
        repository = WorkoutRepository(dao)

        supinoRowId = dao.insertExercise(
            WorkoutGenerationTestData.catalogExercise(
                0, "Supino reto com barra", "supino-reto-barra", "Peitoral"
            )
        )
        dao.insertExercise(
            WorkoutGenerationTestData.catalogExercise(
                0, "Crucifixo com halteres", "crucifixo-halteres", "Peitoral", equipment = "Halteres"
            )
        )

        programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
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

    /** Impressão da sessão concluída: se qualquer coisa nela mudar, a string muda. */
    private suspend fun completedSessionSnapshot(): String {
        val session = database.workoutDao().getSessionWithDetails(sessionId)!!
        return buildString {
            append(session.session)
            session.sortedExercises.forEach { exercise ->
                append(exercise.exerciseSession)
                exercise.sets.sortedBy { it.setNumber }.forEach { append(it) }
            }
        }
    }

    /** Impressão da gamificação: XP, conquistas e recordes reconhecidos pelo domínio. */
    private suspend fun gamificationSnapshot(): String = buildString {
        append(database.xpTransactionDao().getTotalXp().first() ?: 0)
        append("|")
        append(database.achievementDao().getUnlocks())
        append("|")
        append(database.gamificationEventDao().getAll().size)
        append("|")
        append(database.workoutDao().getHighestPR(supinoRowId, PRType.MAX_WEIGHT.name))
    }

    private fun preferences() = WorkoutGenerationPreferences(
        goal = WorkoutGoal.HYPERTROPHY,
        durationMinutes = 60,
        focusMuscleGroups = listOf(MuscleGroup.CHEST)
    )

    @Test
    fun `nenhum fluxo do Coach altera a sessao concluida nem a gamificacao`() = runTest {
        val dao = database.workoutDao()
        val settings = SettingsManager(ApplicationProvider.getApplicationContext())
        val sessionBefore = completedSessionSnapshot()
        val gamificationBefore = gamificationSnapshot()

        // 1. Analisar.
        val analyzeGateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "A carga se manteve nas execuções registradas.",
                    dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.INSUFFICIENT)
                )
            )
        }
        AnalyzeWorkoutUseCase(
            contextBuilder = WorkoutAiCoachContextBuilder(dao, settings),
            gateway = analyzeGateway
        )()

        // 2. Gerar e **confirmar** — a única escrita legítima deste fluxo.
        val generateGateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Success(
                    WorkoutGenerationTestData.response(
                        name = "Peito novo",
                        exercises = listOf(
                            WorkoutGenerationTestData.exerciseResponse("crucifixo-halteres", order = 1)
                        )
                    )
                )
            }
        )
        val generated = GenerateWorkoutUseCase(
            contextBuilder = WorkoutAiGenerationContextBuilder(dao),
            gateway = generateGateway
        )(preferences())
        assertTrue(generated is GenerateWorkoutResult.Success)
        SaveGeneratedWorkoutUseCase(repository)((generated as GenerateWorkoutResult.Success).draft)

        // 3. Adaptar e **aplicar** — a única escrita legítima daquele fluxo.
        val adaptGateway = FakeAiCoachGateway(
            adaptationResponder = {
                AiWorkoutAdaptationGatewayResult.Success(
                    AiWorkoutAdaptationResponse(
                        summary = "Uma revisão de carga.",
                        changes = listOf(
                            AiWorkoutAdaptationChangeResponse(
                                type = WorkoutAdaptationType.ADJUST_LOAD.name,
                                exerciseId = "supino-reto-barra",
                                currentWeightKg = 60.0,
                                suggestedWeightKg = 62.5,
                                reason = "O alvo foi atingido com a mesma carga.",
                                evidence = "60 kg em 1 execução concluída",
                                confidence = 0.8
                            )
                        ),
                        dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.LIMITED)
                    )
                )
            }
        )
        val adaptationContextBuilder = WorkoutAiAdaptationContextBuilder(dao)
        val adapted = AdaptWorkoutUseCase(adaptationContextBuilder, adaptGateway)(templateId)
        assertTrue(adapted is AdaptWorkoutResult.Success)
        val draft = (adapted as AdaptWorkoutResult.Success).draft
        ApplyWorkoutAdaptationUseCase(repository)(draft, setOf(draft.changes.single().id))

        // 4. Explicar — read-only por construção.
        val explainGateway = FakeAiCoachGateway(
            explanationResponder = {
                AiCoachExplanationGatewayResult.Success(
                    CoachExplanationTestData.explanationResponse()
                )
            }
        )
        val explain = ExplainCoachDecisionUseCase(
            gateway = explainGateway,
            adaptationContextBuilder = adaptationContextBuilder
        )
        explain.explainAdaptationChange(draft, draft.changes.single().id)
        explain.explainProgress(CoachExplanationTestData.progress())
        val advice = CoachExplanationTestData.advice()
        explain.explainAnalysisTarget(advice, advice.recommendations.single().id)

        // O treino planejado mudou, porque o usuário confirmou.
        assertEquals(
            62.5f,
            dao.getTemplateExercisesWithDetails(templateId).single().templateExercise.plannedWeight
        )
        assertNotNull(
            "a confirmação da geração precisa ter criado o treino",
            dao.getTemplatesForProgramSync(programId).firstOrNull { it.name == "Peito novo" }
        )

        // O que aconteceu de verdade, e o que o usuário conquistou, não.
        assertEquals(
            "sessão concluída foi alterada por um fluxo do Coach",
            sessionBefore,
            completedSessionSnapshot()
        )
        assertEquals(
            "gamificação foi alterada por um fluxo do Coach",
            gamificationBefore,
            gamificationSnapshot()
        )
    }
}
