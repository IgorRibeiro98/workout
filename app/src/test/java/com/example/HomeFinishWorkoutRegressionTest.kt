package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.presentation.today.TodayScreen
import com.example.presentation.today.TodayViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MainApplication::class)
class HomeFinishWorkoutRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: MainApplication
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settingsManager: SettingsManager
    private lateinit var workoutEngine: WorkoutEngine
    private lateinit var repository: WorkoutRepository
    private lateinit var viewModel: TodayViewModel

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        context = app
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workoutDao()
        settingsManager = SettingsManager(context)
        settingsManager.clearAll()
        workoutEngine = WorkoutEngine(dao, settingsManager)
        repository = WorkoutRepository(dao)

        app.database = db
        app.settingsManager = settingsManager
        app.workoutEngine = workoutEngine

        viewModel = TodayViewModel(repository, settingsManager, workoutEngine)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testVisibility_noActiveSession_finishButtonNotDisplayed() {
        composeTestRule.setContent {
            MyApplicationTheme {
                TodayScreen(viewModel = viewModel, onNavigateToExecution = {})
            }
        }

        // When no active session, continue and finish buttons should not exist
        composeTestRule.onNodeWithTag("today_continue_workout_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("today_finish_workout_button").assertDoesNotExist()
    }

    @Test
    fun testVisibility_activeSession_continueAndFinishButtonsDisplayed() {
        runBlocking {
            val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 1"))
            val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Superior", shortIdentifier = "A"))
            workoutEngine.startSession(tplId)
        }

        composeTestRule.setContent {
            MyApplicationTheme {
                TodayScreen(viewModel = viewModel, onNavigateToExecution = {})
            }
        }

        // Both buttons must be visible
        composeTestRule.onNodeWithTag("today_continue_workout_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("today_finish_workout_button").assertIsDisplayed()
    }

    @Test
    fun testConfirmationDialog_opensAndCancelsWithoutModifyingSession() {
        var activeSessionBefore: WorkoutSessionEntity? = null
        runBlocking {
            val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 2"))
            val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Inferior", shortIdentifier = "B"))
            workoutEngine.startSession(tplId)
            activeSessionBefore = dao.getActiveSession()!!
        }

        composeTestRule.setContent {
            MyApplicationTheme {
                TodayScreen(viewModel = viewModel, onNavigateToExecution = {})
            }
        }

        // Click finish button
        composeTestRule.onNodeWithTag("today_finish_workout_button").performClick()

        // Confirmation dialog displayed
        composeTestRule.onNodeWithText("Finalizar treino?").assertIsDisplayed()

        // Before confirmation, session remains IN_PROGRESS
        runBlocking {
            val sessionDuringDialog = dao.getActiveSession()
            assertNotNull("Session must remain in progress while dialog is open", sessionDuringDialog)
            assertEquals(SessionStatus.IN_PROGRESS.name, sessionDuringDialog?.status)
        }

        // Cancel
        composeTestRule.onNodeWithTag("cancel_finish_workout_button").performClick()

        // Dialog dismissed and session still active
        composeTestRule.onNodeWithText("Finalizar treino?").assertDoesNotExist()
        runBlocking {
            val sessionAfterCancel = dao.getActiveSession()
            assertNotNull("Session must still be active after cancel", sessionAfterCancel)
            assertEquals(activeSessionBefore?.id, sessionAfterCancel?.id)
            assertEquals(SessionStatus.IN_PROGRESS.name, sessionAfterCancel?.status)
        }
    }

    @Test
    fun testConfirmationDialog_confirmFinishesWorkout() {
        var activeSession: WorkoutSessionEntity? = null
        runBlocking {
            val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 3"))
            val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Peito", shortIdentifier = "C"))
            workoutEngine.startSession(tplId)
            activeSession = dao.getActiveSession()!!
        }

        composeTestRule.setContent {
            MyApplicationTheme {
                TodayScreen(viewModel = viewModel, onNavigateToExecution = {})
            }
        }

        composeTestRule.onNodeWithTag("today_finish_workout_button").performClick()
        composeTestRule.onNodeWithTag("confirm_finish_workout_button").performClick()
        composeTestRule.waitForIdle()

        // Verify session marked COMPLETED via finishActiveWorkout/finishSession
        runBlocking {
            // Wait for DB transaction to complete
            var attempts = 0
            while (dao.getActiveSession() != null && attempts < 20) {
                kotlinx.coroutines.delay(50)
                attempts++
            }
            val completedSession = dao.getSessionById(activeSession!!.id)
            assertNotNull(completedSession)
            assertEquals(SessionStatus.COMPLETED.name, completedSession?.status)
            assertNotNull("finishedAt timestamp must be set", completedSession?.finishedAt)
            assertNull(dao.getActiveSession())
        }
    }

    @Test
    fun testPartialWorkout_pendingSetsRemainIncomplete() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 4"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Costas", shortIdentifier = "D"))
        val exId1 = dao.insertExercise(ExerciseEntity(name = "Puxada Frontal", primaryMuscle = "DORSAL"))
        val exId2 = dao.insertExercise(ExerciseEntity(name = "Remada Curvada", primaryMuscle = "DORSAL"))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = tplId, exerciseId = exId1, sortOrder = 0, targetSets = 3, minReps = 10, maxReps = 12))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = tplId, exerciseId = exId2, sortOrder = 1, targetSets = 2, minReps = 10, maxReps = 12))

        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!
        val exSessions = dao.getExerciseSessionsForSession(activeSession.id)
        val setsEx1 = dao.getSetLogsForExerciseSession(exSessions[0].id)
        val setsEx2 = dao.getSetLogsForExerciseSession(exSessions[1].id)

        // Mark only 1 set of ex1 completed
        dao.updateSetLog(setsEx1[0].copy(completed = true, repetitions = 10, weight = 60f))

        // Finish via workoutEngine (reused by viewModel.finishActiveWorkout)
        workoutEngine.finishSession(activeSession.id)

        // Verify
        val finishedSession = dao.getSessionById(activeSession.id)!!
        assertEquals(SessionStatus.COMPLETED.name, finishedSession.status)

        val updatedSetsEx1 = dao.getSetLogsForExerciseSession(exSessions[0].id)
        val updatedSetsEx2 = dao.getSetLogsForExerciseSession(exSessions[1].id)

        assertTrue("First set of Ex 1 must remain completed", updatedSetsEx1[0].completed)
        assertFalse("Second set of Ex 1 must remain uncompleted", updatedSetsEx1[1].completed)
        assertFalse("Third set of Ex 1 must remain uncompleted", updatedSetsEx1[2].completed)
        assertFalse("First set of Ex 2 must remain uncompleted", updatedSetsEx2[0].completed)
        assertFalse("Second set of Ex 2 must remain uncompleted", updatedSetsEx2[1].completed)
    }

    @Test
    fun testFinishSessionClearsActiveTimer() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 5"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Braços", shortIdentifier = "E"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.startRestTimer(
            durationSeconds = 60,
            workoutSessionId = activeSession.id,
            exerciseSessionId = 500L,
            timerType = "REST_SET"
        )
        assertNotNull(workoutEngine.restTimerTarget.first())

        workoutEngine.finishSession(activeSession.id)

        assertNull("Rest timer target in engine must be cleared", workoutEngine.restTimerTarget.first())
        assertNull("Rest timer deadline in settings must be null", settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun testFinishSessionWithAutoCheckOut() = runBlocking {
        settingsManager.setAutoCheckOut(true)
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 6"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Ombros", shortIdentifier = "F"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        // Verify active check-in was created with startSession
        val activeCheckIn = dao.getActiveCheckIn()
        assertNotNull("Active check-in should exist when session starts", activeCheckIn)

        workoutEngine.finishSession(activeSession.id)

        val completedCheckIn = dao.getCheckInForSession(activeSession.id)
        assertNotNull("Check-in record should exist", completedCheckIn)
        assertNotNull("Check-out time must be populated when autoCheckOut is enabled", completedCheckIn?.checkOutTime)
    }

    @Test
    fun testCompletedSessionAppearsInHistory() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Prog 7"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino Pernas", shortIdentifier = "G"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.finishSession(activeSession.id)

        val allCompleted = dao.getAllCompletedSessionsWithDetails()
        assertTrue("Completed session must be in history list", allCompleted.any { it.session.id == activeSession.id })
    }
}
