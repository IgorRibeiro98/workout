package com.example.domain.ai

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ai.WorkoutAiCoachContextBuilder
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.PRType
import com.example.data.local.PersonalRecordEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiCoachResult
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
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
 * A IA aconselha; o domínio decide.
 *
 * Este teste roda o fluxo do Coach contra o banco real do Spark e verifica que nada muda:
 * sessão concluída, séries, PRs e catálogo continuam idênticos antes e depois. Também verifica
 * que o core segue funcionando quando o Coach está indisponível.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AiCoachDomainIsolationTest {

    private lateinit var database: AppDatabase
    private lateinit var contextBuilder: WorkoutAiCoachContextBuilder
    private var sessionId: Long = 0L
    private var exerciseRowId: Long = 0L
    private var templateId: Long = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dao = database.workoutDao()

        exerciseRowId = dao.insertExercise(
            ExerciseEntity(
                name = "Supino reto com barra",
                canonicalId = "supino-reto-barra",
                primaryMuscle = "Peitoral"
            )
        )

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A")
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateId,
                exerciseId = exerciseRowId,
                sortOrder = 0,
                targetSets = 3,
                minReps = 8,
                maxReps = 12
            )
        )

        val now = System.currentTimeMillis()
        sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = now - 3_600_000L,
                finishedAt = now,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino A"
            )
        )
        val exerciseSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exerciseRowId,
                actualExerciseId = exerciseRowId,
                exerciseNameSnapshot = "Supino reto com barra"
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 1, weight = 60f, repetitions = 10, completed = true),
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 2, weight = 70f, repetitions = 8, completed = true)
            )
        )
        dao.insertPersonalRecord(
            PersonalRecordEntity(
                exerciseId = exerciseRowId,
                date = now,
                prType = PRType.MAX_WEIGHT,
                value = 70f
            )
        )

        val settingsManager = SettingsManager(context)
        settingsManager.setOverrideTemplateId(templateId)

        contextBuilder = WorkoutAiCoachContextBuilder(
            workoutDao = dao,
            settingsManager = settingsManager
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** O treino planejado, exatamente como está persistido. */
    private suspend fun templateSnapshot(): String {
        val dao = database.workoutDao()
        return buildString {
            append(dao.getTemplateById(templateId))
            dao.getTemplateExercisesWithDetails(templateId).forEach { append(it.templateExercise) }
        }
    }

    /** Insere uma sessão com o status pedido e uma série concluída de [exerciseId]. */
    private suspend fun insertSession(
        status: SessionStatus,
        exerciseId: Long,
        weight: Float,
        startedAt: Long,
        finishedAt: Long?
    ): Long {
        val dao = database.workoutDao()
        val id = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = startedAt,
                finishedAt = finishedAt,
                status = status.name
            )
        )
        val exerciseSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = id,
                plannedExerciseId = exerciseId,
                actualExerciseId = exerciseId,
                exerciseNameSnapshot = "snapshot"
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = exerciseSessionId,
                    setNumber = 1,
                    weight = weight,
                    repetitions = 10,
                    completed = true
                )
            )
        )
        return id
    }

    private suspend fun snapshot(): String {
        val dao = database.workoutDao()
        val session = dao.getSessionWithDetails(sessionId)!!
        return buildString {
            append(session.session)
            session.sortedExercises.forEach { exercise ->
                append(exercise.exerciseSession)
                exercise.sets.sortedBy { it.setNumber }.forEach { append(it) }
            }
            append(dao.getHighestPR(exerciseRowId, PRType.MAX_WEIGHT.name))
            append(dao.getExerciseById(exerciseRowId))
        }
    }

    @Test
    fun `contexto sai das autoridades canonicas preservando o id do catalogo`() = runTest {
        val context = contextBuilder.build()

        assertEquals("Treino A", context.currentWorkout?.templateName)
        assertEquals("supino-reto-barra", context.currentWorkout?.exercises?.single()?.exerciseId)
        assertEquals("supino-reto-barra", context.exerciseHistory.single().exerciseId)
        assertEquals("supino-reto-barra", context.personalRecords.single().exerciseId)
        // O PR vem de personal_records, a autoridade persistida; nada é reinferido das séries.
        assertEquals(70f, context.personalRecords.single().value)
        assertEquals(1, context.evidence.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.LIMITED, context.evidence.maxDataQuality)
    }

    @Test
    fun `sessao concluida permanece identica depois de usar o Coach`() = runTest {
        val before = snapshot()

        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Boa progressão de carga no supino.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = AiRecommendationType.REVIEW_LOAD.name,
                            exerciseId = "supino-reto-barra",
                            reason = "A carga subiu de 60kg para 70kg.",
                            confidence = 0.9,
                            evidence = "60 kg e 70 kg na sessão analisada"
                        )
                    ),
                    dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.LIMITED)
                )
            )
        }

        val result = AnalyzeWorkoutUseCase(contextBuilder, gateway)()

        assertTrue(result is AiCoachResult.Success)
        assertEquals("histórico não pode mudar por causa do Coach", before, snapshot())
    }

    @Test
    fun `resposta invalida nao altera o dominio`() = runTest {
        val before = snapshot()

        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Resumo.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = "APPLY_NEW_LOAD",
                            exerciseId = "exercicio-inventado",
                            reason = "Motivo.",
                            confidence = 42.0,
                            evidence = "Inventada."
                        )
                    )
                )
            )
        }

        val result = AnalyzeWorkoutUseCase(contextBuilder, gateway)() as AiCoachResult.Failure

        assertEquals(AiCoachErrorKind.INVALID_RESPONSE, result.kind)
        assertEquals(before, snapshot())
    }

    @Test
    fun `core continua funcionando com o Coach indisponivel`() = runTest {
        val gateway = FakeAiCoachGateway { AiCoachGatewayResult.Error(AiCoachErrorKind.UNAVAILABLE) }

        val result = AnalyzeWorkoutUseCase(contextBuilder, gateway)() as AiCoachResult.Failure
        assertEquals(AiCoachErrorKind.UNAVAILABLE, result.kind)

        val dao = database.workoutDao()
        assertNotNull("o histórico continua legível sem IA", dao.getLastCompletedSession())
        assertEquals(1, dao.getAllCompletedSessionsWithDetails().size)
        assertEquals(1, dao.getAllExercisesList().size)
        assertNotNull(dao.getHighestPR(exerciseRowId, PRType.MAX_WEIGHT.name))
    }

    @Test
    fun `template permanece identico depois da analise`() = runTest {
        val before = templateSnapshot()

        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Vale revisar a carga do supino.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = AiRecommendationType.REVIEW_LOAD.name,
                            exerciseId = "supino-reto-barra",
                            reason = "A carga ficou igual.",
                            confidence = 0.7,
                            evidence = "70 kg na última sessão"
                        )
                    ),
                    dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.LIMITED)
                )
            )
        }

        val result = AnalyzeWorkoutUseCase(contextBuilder, gateway)()

        assertTrue(result is AiCoachResult.Success)
        assertEquals("a IA recomenda, não altera o treino", before, templateSnapshot())
    }

    @Test
    fun `so sessao concluida sustenta o desempenho historico`() = runTest {
        val now = System.currentTimeMillis()
        insertSession(SessionStatus.CANCELLED, exerciseRowId, 200f, now - 7_200_000L, now - 6_000_000L)
        insertSession(SessionStatus.PLANNED, exerciseRowId, 300f, now + 3_600_000L, null)
        insertSession(SessionStatus.PAUSED, exerciseRowId, 400f, now - 1_000L, null)
        insertSession(SessionStatus.COMPLETED, exerciseRowId, 75f, now - 90_000_000L, now - 86_400_000L)

        val context = contextBuilder.build()

        val executions = context.exerciseHistory.single().executions
        // Apenas as duas COMPLETED: a de 70 kg do setUp e a de 75 kg inserida aqui.
        assertEquals(2, executions.size)
        assertEquals(listOf(70f, 75f), executions.mapNotNull { it.maxWeightKg }.sorted())
        assertEquals(2, context.evidence.sessionsAnalyzed)
        // A sessão IN_PROGRESS do app não pode virar histórico nem carga de 400 kg.
        assertTrue(executions.none { (it.maxWeightKg ?: 0f) > 100f })
    }

    @Test
    fun `historico de exercicio fora do treino analisado nao vai para o modelo`() = runTest {
        val dao = database.workoutDao()
        val squatId = dao.insertExercise(
            ExerciseEntity(
                name = "Agachamento livre",
                canonicalId = "agachamento-livre",
                primaryMuscle = "Quadríceps"
            )
        )
        dao.insertPersonalRecord(
            PersonalRecordEntity(
                exerciseId = squatId,
                date = System.currentTimeMillis(),
                prType = PRType.MAX_WEIGHT,
                value = 180f
            )
        )
        repeat(3) { index ->
            insertSession(
                status = SessionStatus.COMPLETED,
                exerciseId = squatId,
                weight = 100f + index,
                startedAt = System.currentTimeMillis() - (index + 2) * 86_400_000L,
                finishedAt = System.currentTimeMillis() - (index + 2) * 86_400_000L + 3_600_000L
            )
        }

        val context = contextBuilder.build()

        assertEquals(listOf("supino-reto-barra"), context.exerciseHistory.map { it.exerciseId })
        assertTrue("agachamento-livre" !in context.knownExerciseIds)
        assertTrue(context.personalRecords.none { it.exerciseId == "agachamento-livre" })
        assertEquals(1, context.evidence.sessionsAnalyzed)
    }

    @Test
    fun `usuario sem historico recebe analise com qualidade insuficiente`() = runTest {
        val dao = database.workoutDao()
        dao.deleteWorkoutSession(dao.getSessionById(sessionId)!!)

        val context = contextBuilder.build()

        assertEquals("Treino A", context.currentWorkout?.templateName)
        assertEquals(0, context.evidence.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.INSUFFICIENT, context.evidence.maxDataQuality)
        assertTrue(context.exerciseHistory.single().executions.isEmpty())

        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachTestData.response(
                    summary = "Ainda não há sessões concluídas para avaliar evolução.",
                    dataQuality = AiCoachTestData.dataQuality(AiDataQualityLevel.INSUFFICIENT)
                )
            )
        }

        // Falta de histórico é resposta conservadora, não erro de infraestrutura.
        val result = AnalyzeWorkoutUseCase(contextBuilder, gateway)() as AiCoachResult.Success
        assertEquals(AiDataQualityLevel.INSUFFICIENT, result.advice.dataQuality.level)
        assertEquals(0, result.advice.sessionsAnalyzed)
    }
}
