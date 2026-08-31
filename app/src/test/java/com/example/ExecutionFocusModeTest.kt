package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.domain.engine.WorkoutEngine
import com.example.presentation.execution.ExecutionPhase
import com.example.presentation.execution.ExecutionViewModel
import com.example.service.WorkoutNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionFocusModeTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var context: Context
    private lateinit var workoutEngine: WorkoutEngine
    private lateinit var viewModel: ExecutionViewModel
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workoutDao()

        val settingsManager = SettingsManager(context)
        val notificationManager = WorkoutNotificationManager(context)
        workoutEngine = WorkoutEngine(dao, settingsManager, kotlinx.coroutines.CoroutineScope(testDispatcher))
        viewModel = ExecutionViewModel(workoutEngine, notificationManager, settingsManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `test focus mode active set determination and phase transition`() = runBlocking {
        // Setup exercises & template
        val exId1 = dao.insertExercise(
            ExerciseEntity(name = "Agachamento Livre", canonicalId = "agachamento", primaryMuscle = "Quadríceps")
        )
        val exId2 = dao.insertExercise(
            ExerciseEntity(name = "Leg Press", canonicalId = "leg-press", primaryMuscle = "Quadríceps")
        )

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Pernas"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino Pernas", orderInProgram = 0)
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exId1, sortOrder = 0, targetSets = 2, restDurationSeconds = 90)
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exId2, sortOrder = 1, targetSets = 2, restDurationSeconds = 90)
        )

        // Start workout session
        workoutEngine.startSession(templateId)

        val state1 = viewModel.state.first { !it.isLoading }
        assertNotNull(state1.sessionWithDetails)
        assertEquals(0, state1.currentExerciseIndex)
        assertEquals(ExecutionPhase.ACTIVE_SET, state1.phase)
        assertEquals(0, state1.activeSetIndex)

        // Complete set 1
        val set1 = state1.activeSet!!
        workoutEngine.updateSet(set1.copy(completed = true, finishedAt = System.currentTimeMillis()))

        // Rest timer starts -> RESTING phase
        val state2 = viewModel.state.first { it.isResting }
        assertEquals(ExecutionPhase.RESTING, state2.phase)

        // Skip rest timer -> ACTIVE_SET phase on set 2
        viewModel.skipRestTimer()
        val state3 = viewModel.state.first { !it.isResting }
        assertEquals(ExecutionPhase.ACTIVE_SET, state3.phase)
        assertEquals(1, state3.activeSetIndex)

        // Complete set 2 (last set of Ex 1)
        val set2 = state3.activeSet!!
        workoutEngine.updateSet(set2.copy(completed = true, finishedAt = System.currentTimeMillis()))

        // Skip rest timer
        viewModel.skipRestTimer()

        // Exercise 1 is completed -> EXERCISE_TRANSITION phase
        val state4 = viewModel.state.first { it.phase == ExecutionPhase.EXERCISE_TRANSITION }
        assertEquals(ExecutionPhase.EXERCISE_TRANSITION, state4.phase)
        assertTrue(state4.isExerciseCompleted)
        assertFalse(state4.isLastExercise)

        // Start next exercise -> ACTIVE_SET on Exercise 2
        viewModel.nextExercise()
        val state5 = viewModel.state.first { it.currentExerciseIndex == 1 }
        assertEquals(1, state5.currentExerciseIndex)
        assertEquals(ExecutionPhase.ACTIVE_SET, state5.phase)
        assertEquals(0, state5.activeSetIndex)
    }

    @Test
    fun `test workout recovery auto restores to first incomplete exercise`() = runBlocking {
        val exId1 = dao.insertExercise(
            ExerciseEntity(name = "Supino Reto", canonicalId = "supino-reto", primaryMuscle = "Peitoral")
        )
        val exId2 = dao.insertExercise(
            ExerciseEntity(name = "Crucifixo", canonicalId = "crucifixo", primaryMuscle = "Peitoral")
        )

        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino Peito"
            )
        )

        val exSess1 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId1,
                actualExerciseId = exId1,
                exerciseNameSnapshot = "Supino Reto",
                sortOrder = 0
            )
        )
        val exSess2 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId2,
                actualExerciseId = exId2,
                exerciseNameSnapshot = "Crucifixo",
                sortOrder = 1
            )
        )

        // All sets of Ex 1 completed
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = exSess1, setNumber = 1, weight = 80f, repetitions = 10, completed = true),
                SetLogEntity(exerciseSessionId = exSess1, setNumber = 2, weight = 80f, repetitions = 10, completed = true)
            )
        )
        // Set 1 of Ex 2 incomplete
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = exSess2, setNumber = 1, weight = 20f, repetitions = 12, completed = false),
                SetLogEntity(exerciseSessionId = exSess2, setNumber = 2, weight = 20f, repetitions = 12, completed = false)
            )
        )

        val newViewModel = ExecutionViewModel(workoutEngine, WorkoutNotificationManager(context), SettingsManager(context))

        val recoveredState = newViewModel.state.first { !it.isLoading && it.sessionWithDetails != null }
        
        // Auto restored to Exercise 2 (index 1)
        assertEquals(1, recoveredState.currentExerciseIndex)
        assertEquals(ExecutionPhase.ACTIVE_SET, recoveredState.phase)
        assertEquals(0, recoveredState.activeSetIndex)
    }

    @Test
    fun `test workout complete phase when all exercises finished`() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Rosca Direta", canonicalId = "rosca-direta", primaryMuscle = "Bíceps")
        )

        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino Braço"
            )
        )

        val exSess = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Rosca Direta",
                sortOrder = 0
            )
        )

        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = exSess, setNumber = 1, weight = 15f, repetitions = 10, completed = true)
            )
        )

        val newViewModel = ExecutionViewModel(workoutEngine, WorkoutNotificationManager(context), SettingsManager(context))
        val completedState = newViewModel.state.first { !it.isLoading && it.sessionWithDetails != null }

        assertEquals(ExecutionPhase.WORKOUT_COMPLETE, completedState.phase)
        assertTrue(completedState.isAllExercisesCompleted)
    }

    @Test
    fun `test swapped exercise updates actual exercise name in state`() = runBlocking {
        val originalExId = dao.insertExercise(
            ExerciseEntity(name = "Agachamento Barra", canonicalId = "agachamento-barra")
        )
        val swappedExId = dao.insertExercise(
            ExerciseEntity(name = "Leg Press 45", canonicalId = "leg-press-45")
        )

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Pernas"))
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino Pernas", orderInProgram = 0)
        )
        dao.insertTemplateExercise(
            WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = originalExId, sortOrder = 0, targetSets = 1)
        )

        workoutEngine.startSession(templateId)
        val state1 = viewModel.state.first { !it.isLoading }
        assertEquals("Agachamento Barra", state1.currentExercise?.exerciseSession?.exerciseNameSnapshot)

        // Swap exercise
        viewModel.swapCurrentExercise(swappedExId, permanent = false)

        val state2 = viewModel.state.first {
            it.currentExercise?.exerciseSession?.actualExerciseId == swappedExId
        }
        assertEquals("Leg Press 45", state2.currentExercise?.exerciseSession?.exerciseNameSnapshot)
    }
}
