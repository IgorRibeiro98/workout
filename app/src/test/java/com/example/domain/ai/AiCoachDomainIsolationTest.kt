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
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseRecommendation
import com.example.domain.ai.model.AiCoachResult
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
        val templateId = dao.insertTemplate(
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
            settingsManager = settingsManager,
            bodyMeasurementRepository = BodyMeasurementRepository(database.bodyMeasurementDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
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
        assertEquals("supino-reto-barra", context.recentSessions.single().exercises.single().exerciseId)
        assertEquals("supino-reto-barra", context.personalRecords.single().exerciseId)
        assertEquals(70f, context.personalRecords.single().value)
    }

    @Test
    fun `sessao concluida permanece identica depois de usar o Coach`() = runTest {
        val before = snapshot()

        val gateway = FakeAiCoachGateway {
            AiCoachGatewayResult.Success(
                AiCoachResponse(
                    summary = "Boa progressão de carga no supino.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = AiRecommendationType.REVIEW_LOAD.name,
                            exerciseId = "supino-reto-barra",
                            reason = "A carga subiu de 60kg para 70kg.",
                            confidence = 0.9
                        )
                    )
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
                AiCoachResponse(
                    summary = "Resumo.",
                    recommendations = listOf(
                        AiCoachResponseRecommendation(
                            type = "APPLY_NEW_LOAD",
                            exerciseId = "exercicio-inventado",
                            reason = "Motivo.",
                            confidence = 42.0
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
}
