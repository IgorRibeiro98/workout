package com.example.domain.ai

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ai.WorkoutAiAdaptationContextBuilder
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.ai.WorkoutAdaptationTestData.loadChange
import com.example.domain.ai.WorkoutAdaptationTestData.replacementChange
import com.example.domain.ai.WorkoutAdaptationTestData.response
import com.example.domain.ai.WorkoutAdaptationTestData.restChange
import com.example.domain.ai.WorkoutAdaptationTestData.setsChange
import com.example.domain.ai.WorkoutGenerationTestData.catalogExercise
import com.example.domain.ai.model.AdaptWorkoutResult
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiWorkoutAdaptationGatewayResult
import com.example.domain.ai.model.ApplyWorkoutAdaptationResult
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.domain.ai.usecase.AdaptWorkoutUseCase
import com.example.domain.ai.usecase.ApplyWorkoutAdaptationUseCase
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
 * O Coach propõe; o usuário escolhe; o domínio aplica — e o histórico não se move.
 *
 * Este teste roda o fluxo de adaptação contra o banco real do Spark e cobre as duas metades da
 * regra: **antes** da confirmação nada muda, e **depois** dela só as mudanças escolhidas entram,
 * pelo caminho canônico de edição, sem tocar em sessões concluídas nem em outros treinos.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AdaptWorkoutPersistenceTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var contextBuilder: WorkoutAiAdaptationContextBuilder

    private var templateId: Long = 0L
    private var otherTemplateId: Long = 0L
    private var supinoRowId: Long = 0L
    private var crucifixoRowId: Long = 0L
    private var halteresRowId: Long = 0L
    private var sessionId: Long = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.workoutDao()
        repository = WorkoutRepository(dao)
        contextBuilder = WorkoutAiAdaptationContextBuilder(dao)

        supinoRowId = dao.insertExercise(
            catalogExercise(0, "Supino reto com barra", "supino-reto-barra", "Peitoral")
        )
        crucifixoRowId = dao.insertExercise(
            catalogExercise(0, "Crucifixo com halteres", "crucifixo-halteres", "Peitoral", equipment = "Halteres")
        )
        halteresRowId = dao.insertExercise(
            catalogExercise(0, "Supino reto com halteres", "supino-reto-halteres", "Peitoral", equipment = "Halteres")
        )
        dao.insertExercise(catalogExercise(0, "Remada curvada", "remada-curvada", "Dorsal"))

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Peito + Tríceps", shortIdentifier = "A")
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
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = templateId,
                exerciseId = crucifixoRowId,
                sortOrder = 1,
                targetSets = 3,
                minReps = 10,
                maxReps = 12,
                restDurationSeconds = 75,
                plannedWeight = null
            )
        )

        otherTemplateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Costas", shortIdentifier = "B")
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(
                templateId = otherTemplateId,
                exerciseId = supinoRowId,
                sortOrder = 0,
                targetSets = 3,
                minReps = 6,
                maxReps = 8,
                restDurationSeconds = 120,
                plannedWeight = 40f
            )
        )

        sessionId = insertSession(SessionStatus.COMPLETED, supinoRowId, weight = 60f, reps = 8, daysAgo = 2)
        insertSession(SessionStatus.COMPLETED, supinoRowId, weight = 60f, reps = 8, daysAgo = 5)
        insertSession(SessionStatus.COMPLETED, supinoRowId, weight = 60f, reps = 8, daysAgo = 9)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertSession(
        status: SessionStatus,
        exerciseRowId: Long,
        weight: Float,
        reps: Int,
        daysAgo: Int,
        completedSets: Boolean = true
    ): Long {
        val dao = database.workoutDao()
        val startedAt = System.currentTimeMillis() - daysAgo * 86_400_000L
        val id = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = startedAt,
                finishedAt = if (status == SessionStatus.COMPLETED) startedAt + 3_600_000L else null,
                status = status.name,
                templateNameSnapshot = "Peito + Tríceps"
            )
        )
        val exerciseSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = id,
                plannedExerciseId = exerciseRowId,
                actualExerciseId = exerciseRowId,
                exerciseNameSnapshot = "snapshot"
            )
        )
        dao.insertSetLogs(
            (1..2).map { setNumber ->
                SetLogEntity(
                    exerciseSessionId = exerciseSessionId,
                    setNumber = setNumber,
                    weight = weight,
                    repetitions = reps,
                    completed = completedSets
                )
            }
        )
        return id
    }

    private suspend fun templateSnapshot(id: Long): String {
        val dao = database.workoutDao()
        return buildString {
            append(dao.getTemplateById(id))
            dao.getTemplateExercisesWithDetails(id).forEach { append(it.templateExercise) }
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

    private suspend fun supino() = database.workoutDao()
        .getTemplateExercisesWithDetails(templateId)
        .single { it.exercise.id == supinoRowId }
        .templateExercise

    private suspend fun adapt(
        vararg changes: com.example.domain.ai.model.AiWorkoutAdaptationChangeResponse
    ): WorkoutAdaptationDraft {
        val gateway = FakeAiCoachGateway(
            adaptationResponder = {
                AiWorkoutAdaptationGatewayResult.Success(response(changes = changes.toList()))
            }
        )
        val result = AdaptWorkoutUseCase(contextBuilder, gateway)(templateId)
        return (result as AdaptWorkoutResult.Success).draft
    }

    private suspend fun apply(draft: WorkoutAdaptationDraft, ids: Set<String>) =
        ApplyWorkoutAdaptationUseCase(repository)(draft, ids)

    @Test
    fun `o contexto sai das autoridades canonicas do treino e do historico`() = runTest {
        val source = contextBuilder.build(templateId)!!

        assertEquals("Peito + Tríceps", source.context.templateName)
        assertEquals(
            setOf("supino-reto-barra", "crucifixo-halteres"),
            source.context.templateExerciseIds
        )
        val supino = source.context.plannedExercise("supino-reto-barra")!!
        assertEquals(4, supino.targetSets)
        assertEquals(8, supino.minReps)
        assertEquals(12, supino.maxReps)
        assertEquals(90, supino.restSeconds)
        assertEquals(60f, supino.plannedWeightKg)

        val history = source.context.exerciseHistory.single { it.exerciseId == "supino-reto-barra" }
        assertEquals(3, history.executions.size)
        assertEquals(3, source.context.evidence.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.GOOD, source.context.evidence.maxDataQuality)
    }

    @Test
    fun `historico respeita o limite por exercicio da analise`() = runTest {
        repeat(AiModelConfig.HISTORY_PER_EXERCISE_LIMIT + 4) { index ->
            insertSession(SessionStatus.COMPLETED, supinoRowId, weight = 50f, reps = 10, daysAgo = 20 + index)
        }

        val source = contextBuilder.build(templateId)!!

        val history = source.context.exerciseHistory.single { it.exerciseId == "supino-reto-barra" }
        assertEquals(AiModelConfig.HISTORY_PER_EXERCISE_LIMIT, history.executions.size)
    }

    @Test
    fun `sessao nao concluida nao vira desempenho`() = runTest {
        insertSession(SessionStatus.CANCELLED, supinoRowId, weight = 300f, reps = 10, daysAgo = 1)
        insertSession(SessionStatus.IN_PROGRESS, supinoRowId, weight = 400f, reps = 10, daysAgo = 0)

        val source = contextBuilder.build(templateId)!!

        val executions = source.context.exerciseHistory
            .single { it.exerciseId == "supino-reto-barra" }
            .executions
        assertEquals(3, executions.size)
        assertTrue("carga de sessão não concluída não pode entrar", executions.none { (it.maxWeightKg ?: 0f) > 100f })
    }

    @Test
    fun `sem historico o app nao oferece tipos de progressao`() = runTest {
        val dao = database.workoutDao()
        dao.getAllCompletedSessionsWithDetails().forEach { dao.deleteWorkoutSession(it.session) }

        val source = contextBuilder.build(templateId)!!

        assertEquals(0, source.context.evidence.sessionsAnalyzed)
        assertEquals(AiDataQualityLevel.INSUFFICIENT, source.context.evidence.maxDataQuality)
        assertEquals(
            listOf(WorkoutAdaptationType.ADJUST_REST.name, WorkoutAdaptationType.REPLACE_EXERCISE.name),
            source.context.allowedChangeTypes
        )
    }

    @Test
    fun `substitutos saem do catalogo e nunca incluem o que ja esta no treino`() = runTest {
        val source = contextBuilder.build(templateId)!!

        val ids = source.context.allowedReplacementIds
        assertTrue("supino-reto-halteres" in ids)
        assertTrue("exercício do treino não é substituto de si mesmo", "supino-reto-barra" !in ids)
        assertTrue("crucifixo-halteres" !in ids)
        assertTrue("substituto precisa ser do mesmo grupo", "remada-curvada" !in ids)
    }

    @Test
    fun `gerar a proposta nao altera o treino`() = runTest {
        val before = templateSnapshot(templateId)

        adapt(loadChange())

        assertEquals("propor não é alterar", before, templateSnapshot(templateId))
    }

    @Test
    fun `nenhuma mudanca selecionada nao escreve nada`() = runTest {
        val before = templateSnapshot(templateId)
        val draft = adapt(loadChange(), restChange())

        val result = apply(draft, emptySet())

        assertEquals(ApplyWorkoutAdaptationResult.NothingSelected, result)
        assertEquals(before, templateSnapshot(templateId))
    }

    @Test
    fun `aplicar uma de varias muda somente a selecionada`() = runTest {
        val draft = adapt(
            loadChange(),
            restChange(),
            setsChange()
        )
        assertEquals(3, draft.changes.size)

        val onlyLoad = draft.changes.single { it.type == WorkoutAdaptationType.ADJUST_LOAD }
        val result = apply(draft, setOf(onlyLoad.id)) as ApplyWorkoutAdaptationResult.Applied

        assertEquals(1, result.appliedChanges)
        val supino = supino()
        assertEquals(62.5f, supino.plannedWeight)
        assertEquals("descanso não foi selecionado", 90, supino.restDurationSeconds)
        assertEquals("séries não foram selecionadas", 4, supino.targetSets)
    }

    @Test
    fun `aplicar varias muda todas as selecionadas de uma vez`() = runTest {
        val draft = adapt(
            loadChange(),
            restChange(),
            setsChange(),
            com.example.domain.ai.WorkoutAdaptationTestData.repsChange()
        )

        val result = apply(draft, draft.changes.map { it.id }.toSet()) as ApplyWorkoutAdaptationResult.Applied

        assertEquals(4, result.appliedChanges)
        val supino = supino()
        assertEquals(62.5f, supino.plannedWeight)
        assertEquals(120, supino.restDurationSeconds)
        assertEquals(5, supino.targetSets)
        assertEquals(10, supino.minReps)
        assertEquals(14, supino.maxReps)
    }

    @Test
    fun `substituicao troca o exercicio e limpa a carga do movimento anterior`() = runTest {
        val draft = adapt(replacementChange())

        val result = apply(draft, draft.changes.map { it.id }.toSet()) as ApplyWorkoutAdaptationResult.Applied

        assertEquals(1, result.appliedChanges)
        val rows = database.workoutDao().getTemplateExercisesWithDetails(templateId)
        val replaced = rows.single { it.templateExercise.sortOrder == 0 }
        assertEquals(halteresRowId, replaced.templateExercise.exerciseId)
        assertNull("a carga pertencia ao exercício antigo", replaced.templateExercise.plannedWeight)
        assertEquals("séries e reps continuam do treino", 4, replaced.templateExercise.targetSets)
    }

    @Test
    fun `historico concluido permanece identico depois de aplicar`() = runTest {
        val before = historySnapshot()

        val draft = adapt(loadChange())
        apply(draft, draft.changes.map { it.id }.toSet())

        assertEquals("a adaptação vale para o futuro, não reescreve o passado", before, historySnapshot())
        assertEquals(SessionStatus.COMPLETED.name, database.workoutDao().getSessionById(sessionId)!!.status)
    }

    @Test
    fun `outro treino permanece intacto`() = runTest {
        val before = templateSnapshot(otherTemplateId)

        val draft = adapt(loadChange())
        apply(draft, draft.changes.map { it.id }.toSet())

        assertEquals("só o treino alvo muda", before, templateSnapshot(otherTemplateId))
    }

    @Test
    fun `draft obsoleto nao sobrescreve edicao manual mais recente`() = runTest {
        val draft = adapt(loadChange())

        // O usuário volta ao editor e mexe no treino antes de aplicar a sugestão.
        val edited = supino().copy(plannedWeight = 80f)
        repository.updateTemplateExerciseFull(edited)
        val afterManualEdit = templateSnapshot(templateId)

        val result = apply(draft, draft.changes.map { it.id }.toSet())

        assertEquals(ApplyWorkoutAdaptationResult.StaleDraft, result)
        assertEquals("a edição manual sobrevive", afterManualEdit, templateSnapshot(templateId))
        assertEquals(80f, supino().plannedWeight)
    }

    @Test
    fun `treino removido nao aplica nada`() = runTest {
        val draft = adapt(loadChange())
        database.workoutDao().deleteTemplate(database.workoutDao().getTemplateById(templateId)!!)

        val result = apply(draft, draft.changes.map { it.id }.toSet())

        assertTrue(result is ApplyWorkoutAdaptationResult.Failure)
    }

    @Test
    fun `valor atual revalidado no momento da aplicacao`() = runTest {
        // Draft forjado: a revisão continua a mesma, mas o valor atual não corresponde ao treino.
        val source = contextBuilder.build(templateId)!!
        val forged = WorkoutAdaptationDraft(
            requestId = "req-forjado",
            templateId = templateId,
            templateName = "Peito + Tríceps",
            sourceRevision = source.revision,
            summary = "Proposta forjada.",
            dataQuality = com.example.domain.ai.model.AiCoachDataQuality(AiDataQualityLevel.GOOD, "."),
            changes = listOf(
                WorkoutAdaptationChange(
                    id = WorkoutAdaptationChange.idOf(WorkoutAdaptationType.ADJUST_LOAD, "supino-reto-barra"),
                    type = WorkoutAdaptationType.ADJUST_LOAD,
                    exerciseId = "supino-reto-barra",
                    exerciseName = "Supino reto com barra",
                    currentValue = WorkoutAdaptationValue.Load(45f),
                    suggestedValue = WorkoutAdaptationValue.Load(50f),
                    reason = ".",
                    evidence = ".",
                    confidence = 0.5
                )
            )
        )

        val result = apply(forged, forged.changes.map { it.id }.toSet())

        assertTrue(result is ApplyWorkoutAdaptationResult.Failure)
        assertEquals(60f, supino().plannedWeight)
    }

    @Test
    fun `substituto inexistente no catalogo nao deixa treino pela metade`() = runTest {
        val draft = adapt(loadChange(), replacementChange())
        // O substituto some do catálogo entre a proposta e a aplicação.
        database.workoutDao().deleteExercise(database.workoutDao().getExerciseById(halteresRowId)!!)
        val before = templateSnapshot(templateId)

        val result = apply(draft, draft.changes.map { it.id }.toSet())

        assertTrue(result is ApplyWorkoutAdaptationResult.Failure)
        assertEquals("nenhuma escrita parcial", before, templateSnapshot(templateId))
    }

    @Test
    fun `lote de atualizacoes e atomico`() = runTest {
        val rows = database.workoutDao().getTemplateExercisesWithDetails(templateId).map { it.templateExercise }
        val before = templateSnapshot(templateId)

        // A segunda linha aponta para um exercício que não existe: a chave estrangeira recusa.
        val invalidBatch = listOf(
            rows[0].copy(plannedWeight = 99f),
            rows[1].copy(exerciseId = 999_999L)
        )

        val failed = runCatching { repository.updateTemplateExercises(invalidBatch) }.isFailure

        assertTrue("o lote inválido precisa falhar", failed)
        assertEquals("nada do lote pode ter entrado", before, templateSnapshot(templateId))
    }

    @Test
    fun `editar o treino a mao continua funcionando com o Coach indisponivel`() = runTest {
        val offlineGateway = FakeAiCoachGateway(
            adaptationResponder = { AiWorkoutAdaptationGatewayResult.Error(AiCoachErrorKind.NETWORK) }
        )

        val result = AdaptWorkoutUseCase(contextBuilder, offlineGateway)(templateId)
        assertTrue(result is AdaptWorkoutResult.Failure)

        repository.updateTemplateExerciseFull(supino().copy(targetSets = 6))

        assertEquals(6, supino().targetSets)
        assertNotNull(database.workoutDao().getTemplateById(templateId))
    }
}
