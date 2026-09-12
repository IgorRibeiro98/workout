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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O foco do exercício na tela de execução (auditoria 2026-09-12).
 *
 * O pipeline de estado tinha duas fontes concorrentes — um índice e um id — reconciliadas por
 * efeito colateral **dentro** do `combine` que produz o estado. Convergia por sorte e pela ordem
 * do dispatcher. Estes casos fixam o comportamento que a versão corrigida precisa manter:
 *
 * - o foco nasce no primeiro exercício com série pendente;
 * - concluir o exercício em foco **não** faz o foco andar sozinho (é a fase de transição que
 *   existe para isso);
 * - selecionar um exercício não adjacente leva o foco exatamente para ele.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ExecutionViewModelFocusTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var database: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var engine: WorkoutEngine
    private lateinit var viewModel: ExecutionViewModel

    /** Ver a memória `viewmodel-scope-leak-em-teste`: o escopo tem de ser cancelado no fim. */
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.workoutDao()
        val settings = SettingsManager(context)
        settings.setRestTimerState(null)
        settings.setAutoRestTimerOnSet(false)
        engine = WorkoutEngine(dao, settings)

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A", shortIdentifier = "A")
        )
        listOf("Supino", "Remada", "Agachamento").forEachIndexed { index, name ->
            val exerciseId = dao.insertExercise(ExerciseEntity(name = name))
            dao.insertTemplateExercise(
                WorkoutTemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = exerciseId,
                    sortOrder = index,
                    targetSets = 2
                )
            )
        }
        engine.startSession(templateId)

        viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExecutionViewModel(
                workoutEngine = engine,
                notificationManager = WorkoutNotificationManager(context),
                settingsManager = settings
            ) as T
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[ExecutionViewModel::class.java]
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
        Dispatchers.resetMain()
    }

    /**
     * Mantém um assinante vivo durante o teste.
     *
     * `state` é `stateIn(WhileSubscribed(5000))`: sem assinante, `advanceUntilIdle()` avança o
     * tempo virtual além dos 5 s, o upstream para, e uma escrita posterior no banco nunca chega ao
     * estado. `backgroundScope` é cancelado pelo `runTest` no fim de cada caso.
     */
    private fun kotlinx.coroutines.test.TestScope.keepStateHot() {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    @Test
    fun `o foco nasce no primeiro exercicio pendente`() = runTest(testDispatcher) {
        keepStateHot()
        val state = viewModel.state.first { !it.isLoading }
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.currentExerciseIndex)
        assertNotNull(state.sessionWithDetails)
    }

    @Test
    fun `concluir o exercicio em foco nao move o foco sozinho`() = runTest(testDispatcher) {
        keepStateHot()
        val loaded = viewModel.state.first { !it.isLoading }

        val exercise = loaded.sessionWithDetails!!.exercises.first()
        exercise.sets.forEach { engine.updateSet(it.copy(completed = true)) }

        // O Room emite pelo executor **dele**, não pelo dispatcher de teste: `advanceUntilIdle()`
        // não espera essa emissão (memória `t178-checkins-feed`, "Room vs advanceUntilIdle").
        // Espera-se o estado que reflete as duas séries concluídas, com teto de tempo real.
        val settled = withTimeout(10_000) {
            viewModel.state.first { state ->
                state.sessionWithDetails?.exercises?.firstOrNull()?.sets?.all { it.completed } == true
            }
        }

        // Se o índice fosse derivado de "primeiro pendente" sem fixação, ele pularia para 1 e a
        // fase EXERCISE_TRANSITION nunca apareceria.
        assertEquals(0, settled.currentExerciseIndex)
        assertEquals(ExecutionPhase.EXERCISE_TRANSITION, settled.phase)
    }

    @Test
    fun `selecionar exercicio nao adjacente leva o foco para ele`() = runTest(testDispatcher) {
        keepStateHot()
        viewModel.state.first { !it.isLoading }
        advanceUntilIdle()

        viewModel.selectExercise(2)

        val settled = withTimeout(10_000) { viewModel.state.first { it.currentExerciseIndex == 2 } }
        assertEquals(2, settled.currentExerciseIndex)
    }

    @Test
    fun `avancar exercicio vai para o proximo pendente`() = runTest(testDispatcher) {
        keepStateHot()
        viewModel.state.first { !it.isLoading }
        advanceUntilIdle()

        viewModel.nextExercise()

        val settled = withTimeout(10_000) { viewModel.state.first { it.currentExerciseIndex == 1 } }
        assertEquals(1, settled.currentExerciseIndex)
    }
}
