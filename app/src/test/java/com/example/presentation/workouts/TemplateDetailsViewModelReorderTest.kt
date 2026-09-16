package com.example.presentation.workouts

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.SessionStatus
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import com.example.data.sync.SyncOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O editor de treino reordena por arrastar (T19.6) pelo mesmo caminho canônico de antes:
 * `sortOrder` no Room, numa transação, uma mutação de sync por gesto — e nada além da posição
 * muda. O histórico concluído não se move.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class TemplateDetailsViewModelReorderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val ownerUid = "uid-da-conta"

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var settings: SettingsManager
    private val stores = mutableListOf<ViewModelStore>()

    private var templateId = 0L
    private var otherTemplateId = 0L
    private var supinoId = 0L
    private var crucifixoId = 0L
    private var tricepsId = 0L
    private var customId = 0L
    private var sessionId = 0L

    // ids das linhas de `workout_template_exercises`, na ordem A (Supino), B (Crucifixo), C (Tríceps)
    private var a = 0L
    private var b = 0L
    private var c = 0L

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.workoutDao()
        settings = SettingsManager(context)
        repository = WorkoutRepository(
            dao,
            settingsManager = settings,
            syncMutations = SyncMutationCoordinator(
                transactions = RoomTransactionRunner(database),
                outboxDao = database.syncOutboxDao(),
                scopeProvider = { CloudSyncScope.Enabled(ownerUid) },
                clock = { 1_700_000_000_000L }
            )
        )

        supinoId = dao.insertExercise(ExerciseEntity(name = "Supino reto", canonicalId = "supino-reto-barra", primaryMuscle = "Peitoral"))
        crucifixoId = dao.insertExercise(ExerciseEntity(name = "Crucifixo", canonicalId = "crucifixo-halteres", primaryMuscle = "Peitoral"))
        tricepsId = dao.insertExercise(ExerciseEntity(name = "Tríceps corda", canonicalId = "triceps-corda", primaryMuscle = "Tríceps"))
        customId = dao.insertExercise(ExerciseEntity(name = "Meu exercício", isUserCreated = true, syncId = "custom-1"))

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Peito", shortIdentifier = "A"))
        otherTemplateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Costas", shortIdentifier = "B"))

        // Inseridas fora de ordem de propósito: a ordem canônica é `sortOrder`, nunca o `id`.
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = tricepsId, sortOrder = 2, targetSets = 3, minReps = 12, maxReps = 15, restDurationSeconds = 60)
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = supinoId, sortOrder = 0, targetSets = 4, minReps = 6, maxReps = 10, restDurationSeconds = 120, plannedWeight = 60f, machineLabel = "Banco 2")
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = crucifixoId, sortOrder = 1, targetSets = 4, minReps = 8, maxReps = 12, restDurationSeconds = 90, notes = "cotovelos levemente flexionados")
        )
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = otherTemplateId, exerciseId = customId, sortOrder = 0))
        val rows = dao.getTemplateExercisesWithDetails(templateId).map { it.templateExercise }
        a = rows.single { it.exerciseId == supinoId }.id
        b = rows.single { it.exerciseId == crucifixoId }.id
        c = rows.single { it.exerciseId == tricepsId }.id
        assertTrue("o id não segue a ordem: c foi inserido antes de a", c < a && a < b)

        // Uma sessão concluída na ordem antiga: o histórico é o que aconteceu, e não se move.
        sessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = templateId, startedAt = 1_000L, finishedAt = 5_000L, status = SessionStatus.COMPLETED.name, templateNameSnapshot = "Peito")
        )
        listOf(supinoId to "Supino reto", crucifixoId to "Crucifixo", tricepsId to "Tríceps corda").forEachIndexed { index, (exerciseId, name) ->
            val esId = dao.insertExerciseSession(
                ExerciseSessionEntity(sessionId = sessionId, plannedExerciseId = exerciseId, actualExerciseId = exerciseId, exerciseNameSnapshot = name, sortOrder = index)
            )
            dao.insertSetLogs(listOf(SetLogEntity(exerciseSessionId = esId, setNumber = 1, weight = 40f, repetitions = 10, completed = true)))
        }
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): TemplateDetailsViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TemplateDetailsViewModel(repository, settings) as T
        }
        return ViewModelProvider(store, factory)[TemplateDetailsViewModel::class.java]
    }

    /** Espera com teto de tempo real: o Room responde pelos executores dele, não pelo dispatcher de teste. */
    private suspend fun awaitExercises(
        viewModel: TemplateDetailsViewModel,
        predicate: (List<ResolvedTemplateExercise>) -> Boolean
    ): List<ResolvedTemplateExercise> =
        withContext(Dispatchers.Default) { withTimeout(10_000) { viewModel.exercises.first(predicate) } }

    private suspend fun persisted(): List<WorkoutTemplateExerciseEntity> =
        database.workoutDao().getTemplateExercisesWithDetails(templateId).map { it.templateExercise }

    private suspend fun templateOutboxEntries() =
        database.syncOutboxDao().pendingFor(ownerUid).filter { it.entityType == SyncEntityType.WORKOUT_TEMPLATE.name }

    private fun ids(list: List<ResolvedTemplateExercise>) = list.map { it.templateExercise.id }

    // ------------------------------------------------------------------------------- carregar

    @Test
    fun `carrega os exercicios na ordem de sortOrder, nao de id`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()

        val loaded = awaitExercises(viewModel) { it.size == 3 }

        assertEquals(listOf(a, b, c), ids(loaded))
        assertEquals(listOf("Supino reto", "Crucifixo", "Tríceps corda"), loaded.map { it.resolvedExercise.displayName })
    }

    // ------------------------------------------------------------------------------- reordenar

    @Test
    fun `soltar C no topo persiste C A B com sortOrder 0 1 2, mesmos ids, mesma configuracao`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }
        val before = persisted().associateBy { it.id }

        viewModel.reorderExercises(listOf(c, a, b))
        advanceUntilIdle()

        val shown = awaitExercises(viewModel) { ids(it) == listOf(c, a, b) }
        val after = persisted()
        assertEquals(listOf(c, a, b), after.map { it.id })
        assertEquals(listOf(0, 1, 2), after.map { it.sortOrder })
        assertEquals("nenhum exercício duplicado ou removido", before.keys, after.map { it.id }.toSet())
        after.forEach { row -> assertEquals(before.getValue(row.id).copy(sortOrder = row.sortOrder), row) }
        // A tela mostra exatamente o que está persistido — inclusive a configuração de cada linha.
        assertEquals(after, shown.map { it.templateExercise })
    }

    @Test
    fun `reordenar registra uma unica mutacao do agregado do treino`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }

        viewModel.reorderExercises(listOf(b, c, a))
        advanceUntilIdle()
        awaitExercises(viewModel) { ids(it) == listOf(b, c, a) }

        val entries = templateOutboxEntries()
        assertEquals(1, entries.size)
        assertEquals(SyncOperation.UPSERT.name, entries.single().operation)
    }

    @Test
    fun `soltar na mesma ordem nao escreve nem registra mutacao`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }
        val before = persisted()

        viewModel.reorderExercises(listOf(a, b, c))
        viewModel.reorderExercises(listOf(a, b, c))
        advanceUntilIdle()

        assertEquals(before, persisted())
        assertTrue(templateOutboxEntries().isEmpty())
    }

    @Test
    fun `mover para cima e para baixo pelo action sheet usa o mesmo caminho`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }

        viewModel.moveExercise(2, 1)
        advanceUntilIdle()
        awaitExercises(viewModel) { ids(it) == listOf(a, c, b) }
        assertEquals(listOf(a, c, b), persisted().map { it.id })

        viewModel.moveExercise(0, 1)
        advanceUntilIdle()
        awaitExercises(viewModel) { ids(it) == listOf(c, a, b) }
        assertEquals(listOf(c, a, b), persisted().map { it.id })
        assertEquals(listOf(0, 1, 2), persisted().map { it.sortOrder })
    }

    @Test
    fun `ordem desatualizada - id desconhecido e ausente - nao duplica nem remove`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }

        viewModel.reorderExercises(listOf(c, 999L, a))
        advanceUntilIdle()

        awaitExercises(viewModel) { ids(it) == listOf(c, a, b) }
        val after = persisted()
        assertEquals(listOf(c, a, b), after.map { it.id })
        assertEquals(listOf(0, 1, 2), after.map { it.sortOrder })
    }

    @Test
    fun `a ordem seguinte parte da ordem que a tela mostra`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }

        // Dois gestos seguidos, sem esperar o Room entre eles: o segundo enxerga o primeiro.
        viewModel.reorderExercises(listOf(c, a, b))
        viewModel.moveExercise(0, 2)
        advanceUntilIdle()

        awaitExercises(viewModel) { ids(it) == listOf(a, b, c) }
        assertEquals(listOf(a, b, c), persisted().map { it.id })
    }

    // ------------------------------------------------------------------------------ invariantes

    @Test
    fun `reordenar o template nao altera a sessao concluida nem o catalogo`() = runTest(dispatcher) {
        val dao = database.workoutDao()
        val sessionBefore = dao.getSessionById(sessionId)
        val exerciseSessionsBefore = dao.getExerciseSessionsForSession(sessionId)
        val catalogBefore = listOf(supinoId, crucifixoId, tricepsId).map { dao.getExerciseById(it) }
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }

        viewModel.reorderExercises(listOf(c, b, a))
        advanceUntilIdle()
        awaitExercises(viewModel) { ids(it) == listOf(c, b, a) }

        assertEquals(sessionBefore, dao.getSessionById(sessionId))
        assertEquals(exerciseSessionsBefore, dao.getExerciseSessionsForSession(sessionId))
        assertEquals(listOf(0, 1, 2), dao.getExerciseSessionsForSession(sessionId).sortedBy { it.id }.map { it.sortOrder })
        assertEquals(catalogBefore, listOf(supinoId, crucifixoId, tricepsId).map { dao.getExerciseById(it) })
    }

    @Test
    fun `trocar de treino descarta qualquer ordem pendente e mostra o outro treino`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.load(templateId)
        advanceUntilIdle()
        awaitExercises(viewModel) { it.size == 3 }

        viewModel.reorderExercises(listOf(c, a, b))
        viewModel.load(otherTemplateId)
        advanceUntilIdle()

        val other = awaitExercises(viewModel) { it.size == 1 }
        assertEquals("Meu exercício", other.single().resolvedExercise.displayName)
        assertTrue("um CUSTOM resolve como personalizado", other.single().resolvedExercise.isUserCreated)
        // O gesto do treino A foi persistido nele, e só nele.
        assertEquals(listOf(c, a, b), persisted().map { it.id })
    }
}
