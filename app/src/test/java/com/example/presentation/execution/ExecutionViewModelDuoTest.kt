package com.example.presentation.execution

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
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutExecutionMode
import com.example.data.local.WorkoutParticipantRole
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.domain.engine.WorkoutEngine
import com.example.service.WorkoutNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A tela de execução em modo dupla (T19.4), pelo estado do ViewModel.
 *
 * O que está fixado: a vez é explícita e vem do domínio; concluir a série do dono passa a vez ao
 * convidado **sem** entrar em descanso (o descanso do dono corre por trás); concluir a do convidado
 * devolve a vez ao dono, que então descansa; e um ViewModel novo sobre o mesmo banco — o que uma
 * morte de processo produz — recomeça exatamente na vez em que parou.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ExecutionViewModelDuoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settings: SettingsManager
    private lateinit var engine: WorkoutEngine
    private lateinit var viewModel: ExecutionViewModel
    private var templateId: Long = 0L

    /** Ver a memória `viewmodel-scope-leak-em-teste`: cada escopo criado é cancelado no fim. */
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.workoutDao()
        settings = SettingsManager(context)
        settings.setRestTimerState(null)
        settings.setAutoRestTimerOnSet(true)
        settings.setDefaultRestSeconds(90)
        settings.setDefaultExerciseRestSeconds(120)
        engine = WorkoutEngine(dao, settings)

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )
        listOf("Supino", "Remada").forEachIndexed { index, name ->
            val exerciseId = dao.insertExercise(ExerciseEntity(name = name))
            dao.insertTemplateExercise(
                WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = index, targetSets = 2)
            )
        }
        engine.startSession(templateId, WorkoutExecutionMode.DUO_LOCAL, "João")
        viewModel = newViewModel(engine)
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(engine: WorkoutEngine): ExecutionViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExecutionViewModel(
                workoutEngine = engine,
                notificationManager = WorkoutNotificationManager(context),
                settingsManager = settings
            ) as T
        }
        return ViewModelProvider(store, factory)[ExecutionViewModel::class.java]
    }

    private fun TestScope.keepStateHot(vm: ExecutionViewModel = viewModel) {
        backgroundScope.launch { vm.state.collect { } }
    }

    /**
     * Espera o estado que satisfaz [predicate] com teto de tempo **real**.
     *
     * O Room emite pelo executor dele, não pelo dispatcher de teste (memória `t178-checkins-feed`,
     * "Room vs advanceUntilIdle"), e um `withTimeout` direto no `runTest` conta tempo virtual: com
     * o scheduler ocioso ele salta para os 10 s antes de a emissão chegar.
     */
    private suspend fun awaitState(vm: ExecutionViewModel = viewModel, predicate: (ExecutionState) -> Boolean): ExecutionState =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) { vm.state.first { !it.isLoading && it.duo != null && predicate(it) } }
        }

    private suspend fun awaitOwnerRestTarget(): Long =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) { engine.restTimerTarget.first { it != null }!! }
        }

    @Test
    fun `a dupla nasce na vez do dono na serie 1`() = runTest(testDispatcher) {
        keepStateHot()
        val state = awaitState { it.duoTurn != null }

        assertEquals("João", state.duo!!.guestLabel)
        assertEquals(WorkoutParticipantRole.OWNER, state.currentParticipantRole)
        assertEquals(1, state.duoTurn!!.setNumber)
        assertEquals(WorkoutParticipantRole.GUEST, state.duoTurn!!.nextRole)
        assertEquals(ExecutionPhase.ACTIVE_SET, state.phase)
    }

    @Test
    fun `concluir a serie do dono passa a vez ao convidado enquanto o dono descansa`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = awaitState { it.duoTurn != null }

        viewModel.completeSet(loaded.activeSet!!)
        advanceUntilIdle()

        // A série é gravada antes de o descanso começar: espera-se pelos dois.
        val ownerTarget = awaitOwnerRestTarget()
        val settled = awaitState { it.duoTurn?.role == WorkoutParticipantRole.GUEST }
        assertEquals(1, settled.duoTurn!!.setNumber)
        assertTrue("o dono está descansando por trás", ownerTarget > System.currentTimeMillis())
        // ...mas a fase é a série do convidado, não o descanso do dono.
        assertFalse(settled.isResting)
        assertEquals(ExecutionPhase.ACTIVE_SET, settled.phase)
        assertEquals(WorkoutParticipantRole.OWNER, settled.duoTurn!!.nextRole)
        // A série do dono (`set_logs`) segue sendo a 2: a vez do convidado não mexe no cursor dele.
        assertEquals(1, settled.activeSetIndex)
    }

    @Test
    fun `concluir a serie do convidado devolve a vez ao dono que entao descansa`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = awaitState { it.duoTurn != null }
        viewModel.completeSet(loaded.activeSet!!)
        advanceUntilIdle()
        val guestTurn = awaitState { it.duoTurn?.role == WorkoutParticipantRole.GUEST }

        val guestSet = guestTurn.duoTurn!!.guestSet!!
        viewModel.completeGuestSet(guestSet, guestSet.asSetLogProjection().copy(weight = 42f, repetitions = 9))
        advanceUntilIdle()

        val ownerTarget = awaitOwnerRestTarget()
        val settled = awaitState {
            it.duoTurn?.role == WorkoutParticipantRole.OWNER && it.duoTurn?.setNumber == 2 && it.duo?.guest?.restEndsAt != null
        }
        assertTrue("o dono ainda está em descanso da série 1", settled.isResting)
        assertEquals(ExecutionPhase.RESTING, settled.phase)
        assertEquals(ownerTarget, settled.currentParticipantRestTarget)
        // O convidado descansa por trás, com o próprio relógio.
        assertNotNull(settled.duo!!.guest.restEndsAt)
        assertEquals(42f, settled.duo!!.guestSetsFor(guestSet.exerciseSessionId).first().weight, 0.01f)
        // E a série 1 do dono continua com os valores dele.
        assertEquals(loaded.activeSet!!.weight, settled.currentExercise!!.sets[0].weight, 0.01f)
    }

    @Test
    fun `um ViewModel novo sobre o mesmo banco recomeca na vez do convidado`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = awaitState { it.duoTurn != null }
        viewModel.completeSet(loaded.activeSet!!)
        advanceUntilIdle()
        awaitState { it.duoTurn?.role == WorkoutParticipantRole.GUEST }

        // Morte de processo: motor e ViewModel novos, mesmo Room.
        val restartedEngine = WorkoutEngine(dao, settings)
        val restarted = newViewModel(restartedEngine)
        keepStateHot(restarted)

        val recovered = awaitState(restarted) { it.duoTurn != null }
        assertEquals(WorkoutExecutionMode.DUO_LOCAL.name, recovered.sessionWithDetails!!.session.executionMode)
        assertEquals(WorkoutParticipantRole.GUEST, recovered.duoTurn!!.role)
        assertEquals(1, recovered.duoTurn!!.setNumber)
        assertEquals(0, recovered.currentExerciseIndex)
        assertEquals(ExecutionPhase.ACTIVE_SET, recovered.phase)
    }

    @Test
    fun `descanso do convidado sobrevive a um ViewModel novo`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = awaitState { it.duoTurn != null }
        viewModel.completeSet(loaded.activeSet!!)
        advanceUntilIdle()
        val guestTurn = awaitState { it.duoTurn?.role == WorkoutParticipantRole.GUEST }
        val guestSet = guestTurn.duoTurn!!.guestSet!!
        viewModel.completeGuestSet(guestSet, guestSet.asSetLogProjection())
        advanceUntilIdle()
        val before = awaitState { it.duo?.guest?.restEndsAt != null }

        val restarted = newViewModel(WorkoutEngine(dao, settings))
        keepStateHot(restarted)
        val recovered = awaitState(restarted) { it.duo?.guest?.restEndsAt != null }

        assertEquals("o prazo é o mesmo timestamp", before.duo!!.guest.restEndsAt, recovered.duo!!.guest.restEndsAt)
    }

    @Test
    fun `o exercicio so fica concluido quando os dois concluiram`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = awaitState { it.duoTurn != null }
        val exercise = loaded.currentExercise!!

        // O dono conclui as duas séries dele direto no motor.
        exercise.sets.forEach { engine.updateSet(it.copy(completed = true)) }
        val ownerDone = awaitState { it.currentExercise?.sets?.all { s -> s.completed } == true }

        assertFalse("o convidado ainda tem as duas séries", ownerDone.isExerciseCompleted)
        assertEquals(WorkoutParticipantRole.GUEST, ownerDone.duoTurn!!.role)
        assertEquals(1, ownerDone.duoTurn!!.setNumber)
        assertEquals(ExecutionPhase.ACTIVE_SET, ownerDone.phase)
        assertTrue(ownerDone.isPending(ownerDone.currentExercise!!))

        // O convidado conclui as duas: agora sim o exercício acabou, e a fase é a transição.
        ownerDone.duo!!.guestSetsFor(exercise.exerciseSession.id).forEach { engine.completeGuestSet(it) }
        val bothDone = awaitState { it.isExerciseCompleted }
        assertNull(bothDone.duoTurn)
        assertEquals(WorkoutParticipantRole.OWNER, bothDone.currentParticipantRole)
        assertFalse(bothDone.isAllExercisesCompleted)
        assertEquals("Remada", bothDone.nextPendingExercise!!.exerciseSession.exerciseNameSnapshot)
    }

    @Test
    fun `o treino so fica concluido quando o convidado tambem acabou`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = awaitState { it.duoTurn != null }
        val session = loaded.sessionWithDetails!!

        session.exercises.forEach { ex -> ex.sets.forEach { engine.updateSet(it.copy(completed = true)) } }
        val ownerDone = awaitState { s -> s.sessionWithDetails!!.exercises.all { ex -> ex.sets.all { it.completed } } }
        assertFalse(ownerDone.isAllExercisesCompleted)
        assertTrue(ownerDone.phase != ExecutionPhase.WORKOUT_COMPLETE)

        session.exercises.forEach { ex ->
            ownerDone.duo!!.guestSetsFor(ex.exerciseSession.id).forEach { engine.completeGuestSet(it) }
        }
        val bothDone = awaitState { it.isAllExercisesCompleted }
        assertEquals(ExecutionPhase.WORKOUT_COMPLETE, bothDone.phase)
    }
}
