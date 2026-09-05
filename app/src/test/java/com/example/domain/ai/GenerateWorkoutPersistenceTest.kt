package com.example.domain.ai

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ai.WorkoutAiGenerationContextBuilder
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.ai.WorkoutGenerationTestData.catalogExercise
import com.example.domain.ai.WorkoutGenerationTestData.exerciseResponse
import com.example.domain.ai.WorkoutGenerationTestData.preferences
import com.example.domain.ai.WorkoutGenerationTestData.response
import com.example.domain.ai.model.AiWorkoutGenerationGatewayResult
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.GenerateWorkoutResult
import com.example.domain.ai.model.SaveGeneratedWorkoutResult
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import com.example.domain.ai.usecase.SaveGeneratedWorkoutUseCase
import com.example.domain.engine.MuscleGroup
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A IA propõe; o usuário confirma; o domínio persiste.
 *
 * Este teste roda o fluxo de geração contra o banco real do Spark e verifica as duas metades da
 * regra: **antes** da confirmação nada é escrito, e **depois** dela o treino nasce pelo mesmo
 * caminho da criação manual, sem tocar em templates existentes nem no histórico.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class GenerateWorkoutPersistenceTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var contextBuilder: WorkoutAiGenerationContextBuilder

    private var programId: Long = 0L
    private var existingTemplateId: Long = 0L
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
        contextBuilder = WorkoutAiGenerationContextBuilder(dao)

        supinoRowId = dao.insertExercise(
            catalogExercise(0, "Supino reto com barra", "supino-reto-barra", "Peitoral")
        )
        dao.insertExercise(
            catalogExercise(0, "Crucifixo com halteres", "crucifixo-halteres", "Peitoral", equipment = "Halteres")
        )
        dao.insertExercise(
            catalogExercise(0, "Agachamento livre", "agachamento-livre", "Quadríceps")
        )

        programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        existingTemplateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = existingTemplateId,
                exerciseId = supinoRowId,
                sortOrder = 0,
                targetSets = 3,
                minReps = 8,
                maxReps = 12
            )
        )

        val now = System.currentTimeMillis()
        sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = existingTemplateId,
                startedAt = now - 3_600_000L,
                finishedAt = now,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino A"
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
            listOf(
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 1, weight = 60f, repetitions = 10, completed = true),
                SetLogEntity(exerciseSessionId = exerciseSessionId, setNumber = 2, weight = 70f, repetitions = 8, completed = true)
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun templateCount(): Int = database.workoutDao().getAllTemplatesSync().size

    private suspend fun existingTemplateSnapshot(): String {
        val dao = database.workoutDao()
        return buildString {
            append(dao.getTemplateById(existingTemplateId))
            dao.getTemplateExercisesWithDetails(existingTemplateId).forEach { append(it.templateExercise) }
        }
    }

    private suspend fun historySnapshot(): String {
        val dao = database.workoutDao()
        val session = dao.getSessionWithDetails(sessionId)!!
        return buildString {
            append(session.session)
            session.sortedExercises.forEach { exercise ->
                append(exercise.exerciseSession)
                exercise.sets.sortedBy { it.setNumber }.forEach { append(it) }
            }
        }
    }

    private fun gateway(
        response: com.example.domain.ai.model.AiGeneratedWorkoutResponse = response()
    ) = FakeAiCoachGateway(
        generationResponder = { AiWorkoutGenerationGatewayResult.Success(response) }
    )

    private suspend fun generate(
        gateway: FakeAiCoachGateway = gateway()
    ): GenerateWorkoutResult = GenerateWorkoutUseCase(contextBuilder, gateway)(
        preferences(focus = listOf(MuscleGroup.CHEST))
    )

    @Test
    fun `candidatos saem do catalogo canonico com id canonico`() = runTest {
        val context = contextBuilder.build(preferences(focus = listOf(MuscleGroup.CHEST)))

        assertEquals(
            setOf("supino-reto-barra", "crucifixo-halteres"),
            context.allowedExerciseIds
        )
        assertTrue("exercício de perna não pertence a um foco de peito", context.candidateExercises.none {
            it.exerciseId == "agachamento-livre"
        })
    }

    @Test
    fun `carga enviada corresponde ao que esta persistido`() = runTest {
        val context = contextBuilder.build(preferences(focus = listOf(MuscleGroup.CHEST)))

        val evidence = context.loadEvidence.single()
        assertEquals("supino-reto-barra", evidence.exerciseId)
        // 70 kg é a maior série concluída registrada; nada é arredondado nem inventado.
        assertEquals(70f, evidence.lastWeightKg)
        assertEquals(8, evidence.lastReps)
        assertEquals(1, evidence.sessionsWithHistory)
        // O crucifixo nunca foi executado: ele simplesmente não tem carga no contexto.
        assertTrue(context.loadEvidence.none { it.exerciseId == "crucifixo-halteres" })
    }

    @Test
    fun `exercicio sem historico nao recebe carga inventada`() = runTest {
        val context = contextBuilder.build(preferences(focus = listOf(MuscleGroup.CHEST)))

        assertTrue("crucifixo-halteres" !in context.exerciseIdsWithLoadEvidence)

        val result = GenerateWorkoutUseCase(
            contextBuilder,
            gateway(
                response(
                    exercises = listOf(exerciseResponse("crucifixo-halteres", order = 1, weightKg = 40.0))
                )
            )
        )(preferences(focus = listOf(MuscleGroup.CHEST))) as GenerateWorkoutResult.Failure

        assertTrue(result.detail!!.contains("sem carga registrada"))
        assertEquals(1, templateCount())
    }

    @Test
    fun `gerar nao cria nenhum treino`() = runTest {
        val before = templateCount()

        val result = generate()

        assertTrue(result is GenerateWorkoutResult.Success)
        assertEquals("gerar é proposta, não escrita", before, templateCount())
    }

    @Test
    fun `descartar o rascunho nao deixa nada persistido`() = runTest {
        val before = templateCount()

        val draft = (generate() as GenerateWorkoutResult.Success).draft
        assertTrue("o rascunho existe só em memória", draft.exercises.isNotEmpty())
        // O usuário descarta: o rascunho some com a referência, sem nenhuma operação de banco.

        assertEquals(before, templateCount())
        assertEquals(
            "nenhum exercício foi adicionado a nenhum treino",
            1,
            database.workoutDao().getTemplateExercisesWithDetails(existingTemplateId).size
        )
    }

    @Test
    fun `sair da tela sem confirmar nao deixa treino parcial nem exercicio orfao`() = runTest {
        val templatesBefore = templateCount()
        val exercisesBefore = database.workoutDao().getAllExercisesList().size

        generate()

        assertEquals(templatesBefore, templateCount())
        assertEquals("nenhum exercício órfão é criado", exercisesBefore, database.workoutDao().getAllExercisesList().size)
    }

    @Test
    fun `confirmar cria exatamente um treino pelo fluxo canonico`() = runTest {
        val draft = (generate() as GenerateWorkoutResult.Success).draft

        val result = SaveGeneratedWorkoutUseCase(repository)(draft) as SaveGeneratedWorkoutResult.Saved

        assertEquals(2, templateCount())
        val template = database.workoutDao().getTemplateById(result.templateId)!!
        assertEquals("Peito e tríceps", template.name)
        assertEquals(programId, template.programId)
        // Entra depois do treino que já existia no programa, como um treino criado à mão.
        assertEquals(1, template.orderInProgram)

        val exercises = database.workoutDao().getTemplateExercisesWithDetails(result.templateId)
            .map { it.templateExercise }
        assertEquals(2, exercises.size)
        assertEquals(listOf(0, 1), exercises.map { it.sortOrder })
        assertEquals(supinoRowId, exercises.first().exerciseId)
        assertEquals(4, exercises.first().targetSets)
        assertEquals(8, exercises.first().minReps)
        assertEquals(12, exercises.first().maxReps)
        assertEquals(90, exercises.first().restDurationSeconds)
        assertNull("sem evidência de carga, o template nasce sem carga planejada", exercises.first().plannedWeight)
        assertEquals(3, exercises[1].targetSets)
        assertEquals(75, exercises[1].restDurationSeconds)
    }

    @Test
    fun `carga com evidencia chega ao template como carga planejada`() = runTest {
        val draft = (
            GenerateWorkoutUseCase(
                contextBuilder,
                gateway(
                    response(
                        exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, weightKg = 70.0))
                    )
                )
            )(preferences(focus = listOf(MuscleGroup.CHEST))) as GenerateWorkoutResult.Success
            ).draft

        val result = SaveGeneratedWorkoutUseCase(repository)(draft) as SaveGeneratedWorkoutResult.Saved

        val exercise = database.workoutDao().getTemplateExercisesWithDetails(result.templateId)
            .single().templateExercise
        assertEquals(70f, exercise.plannedWeight)
    }

    @Test
    fun `o treino que ja existia permanece identico`() = runTest {
        val before = existingTemplateSnapshot()

        val draft = (generate() as GenerateWorkoutResult.Success).draft
        SaveGeneratedWorkoutUseCase(repository)(draft)

        assertEquals("a IA cria treino novo, nunca altera o existente", before, existingTemplateSnapshot())
    }

    @Test
    fun `nome repetido cria um treino novo e nao sobrescreve o antigo`() = runTest {
        val draft = (generate(gateway(response(name = "Treino A"))) as GenerateWorkoutResult.Success).draft

        val result = SaveGeneratedWorkoutUseCase(repository)(draft) as SaveGeneratedWorkoutResult.Saved

        assertTrue(result.templateId != existingTemplateId)
        assertEquals(2, templateCount())
        assertNotNull("o treino homônimo continua existindo", database.workoutDao().getTemplateById(existingTemplateId))
    }

    @Test
    fun `a sessao concluida permanece identica depois de salvar`() = runTest {
        val before = historySnapshot()

        val draft = (generate() as GenerateWorkoutResult.Success).draft
        SaveGeneratedWorkoutUseCase(repository)(draft)

        assertEquals("histórico não muda por causa da IA", before, historySnapshot())
        assertEquals(SessionStatus.COMPLETED.name, database.workoutDao().getSessionById(sessionId)!!.status)
    }

    @Test
    fun `sem candidatos o provider nao e chamado e nada e persistido`() = runTest {
        val gateway = gateway()

        val result = GenerateWorkoutUseCase(contextBuilder, gateway)(
            preferences(
                focus = listOf(MuscleGroup.CALVES),
                equipment = setOf(EquipmentAvailability.KETTLEBELL)
            )
        )

        assertEquals(GenerateWorkoutResult.InsufficientCandidates, result)
        assertEquals(0, gateway.generationCallCount)
        assertEquals(1, templateCount())
    }

    @Test
    fun `criar treino manualmente continua funcionando com o Coach indisponivel`() = runTest {
        val offlineGateway = FakeAiCoachGateway(
            generationResponder = {
                AiWorkoutGenerationGatewayResult.Error(com.example.domain.ai.model.AiCoachErrorKind.NETWORK)
            }
        )

        val result = GenerateWorkoutUseCase(contextBuilder, offlineGateway)(
            preferences(focus = listOf(MuscleGroup.CHEST))
        )
        assertTrue(result is GenerateWorkoutResult.Failure)

        // O caminho manual não depende do Coach em nada.
        val manualId = repository.addTemplate(programId, "Treino manual", "M", order = 1)
        repository.addExerciseToTemplate(manualId, supinoRowId, sortOrder = 0)

        assertEquals(2, templateCount())
        assertEquals(1, database.workoutDao().getTemplateExercisesWithDetails(manualId).size)
    }

    @Test
    fun `rascunho com exercicio fora do catalogo nao escreve nada`() = runTest {
        val draft = (generate() as GenerateWorkoutResult.Success).draft
            .let { it.copy(exercises = it.exercises.map { exercise -> exercise.copy(exerciseId = "nao-existe") }) }

        val result = SaveGeneratedWorkoutUseCase(repository)(draft)

        assertTrue(result is SaveGeneratedWorkoutResult.Failure)
        assertEquals("nenhum treino pela metade", 1, templateCount())
    }
}
