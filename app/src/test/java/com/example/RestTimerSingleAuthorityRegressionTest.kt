package com.example

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.domain.engine.WorkoutEngine
import com.example.service.RestNotificationReceiver
import com.example.service.WorkoutNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MainApplication::class)
class RestTimerSingleAuthorityRegressionTest {

    private lateinit var app: MainApplication
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settingsManager: SettingsManager
    private lateinit var workoutEngine: WorkoutEngine
    private lateinit var notificationManager: WorkoutNotificationManager

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
        notificationManager = WorkoutNotificationManager(context)

        // Inject into application instance for Receiver interactions
        app.database = db
        app.settingsManager = settingsManager
        app.workoutEngine = workoutEngine
        app.notificationManager = notificationManager
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testCountdownVisualReachingZeroDoesNotSkipCentralTimer() = runBlocking {
        // 1. Start a session and rest timer
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Teste 1"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino A", shortIdentifier = "A"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.startRestTimer(
            durationSeconds = 1,
            workoutSessionId = activeSession.id,
            exerciseSessionId = 10L,
            timerType = "REST_SET"
        )
        val targetBefore = workoutEngine.restTimerTarget.first()
        assertNotNull("Timer target must be set in engine", targetBefore)

        // 2. Simulating visual countdown loop reaching 0 (target time in past)
        // Central engine target must remain set because UI does NOT call onSkip()
        val targetStillActive = workoutEngine.restTimerTarget.first()
        assertEquals("Central authority must preserve timer state even if clock reaches target", targetBefore, targetStillActive)
        assertNotNull("Deadline in datastore must remain intact", settingsManager.restTimerDeadlineFlow.first { it != null })

        // 3. Only the official alarm receiver event triggers clearing and completion alert
        val receiver = RestNotificationReceiver()
        val finishIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_TIMER_FINISHED
            putExtra("exerciseName", "Supino Reto")
        }
        receiver.handleIntent(finishIntent, workoutEngine, settingsManager, notificationManager)

        // 4. Central state is now cleared cleanly exactly once
        assertNull("Timer target must be null after receiver authority executes", workoutEngine.restTimerTarget.first())
        assertNull("Datastore deadline must be cleared", settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun testTimerExpirationViaReceiverAuthority() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Teste 2"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino B", shortIdentifier = "B"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.startRestTimer(
            durationSeconds = 45,
            workoutSessionId = activeSession.id,
            exerciseSessionId = 20L,
            timerType = "REST_SET"
        )
        assertNotNull(workoutEngine.restTimerTarget.first())

        val receiver = RestNotificationReceiver()
        val finishIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_TIMER_FINISHED
            putExtra("exerciseName", "Desenvolvimento")
        }
        receiver.handleIntent(finishIntent, workoutEngine, settingsManager, notificationManager)

        assertNull("Timer target must be null after receiver finishes", workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun testTimerAdd30sPreservesMetadata() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Teste 3"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino C", shortIdentifier = "C"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.startRestTimer(
            durationSeconds = 30,
            workoutSessionId = activeSession.id,
            exerciseSessionId = 55L,
            timerType = "REST_SET"
        )
        val initialTarget = workoutEngine.restTimerTarget.first()!!

        // Trigger ACTION_ADD_30S
        val receiver = RestNotificationReceiver()
        val add30sIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_ADD_30S
            putExtra("exerciseName", "Agachamento")
        }
        receiver.handleIntent(add30sIntent, workoutEngine, settingsManager, notificationManager)

        val updatedTarget = workoutEngine.restTimerTarget.first()!!
        assertTrue("Target must be increased by 30 seconds", updatedTarget >= initialTarget + 29000L)

        // Verify preserved metadata
        assertEquals(activeSession.id, settingsManager.restTimerSessionIdFlow.first { it != null })
        assertEquals(55L, settingsManager.restTimerExerciseSessionIdFlow.first { it != null })
        assertEquals("REST_SET", settingsManager.restTimerTypeFlow.first { it != null })
    }

    @Test
    fun testManualSkipCancelsTimerCleanly() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Teste 4"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino D", shortIdentifier = "D"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.startRestTimer(
            durationSeconds = 60,
            workoutSessionId = activeSession.id,
            exerciseSessionId = 99L,
            timerType = "REST_SET"
        )
        assertNotNull(workoutEngine.restTimerTarget.first())

        // Trigger ACTION_SKIP
        val receiver = RestNotificationReceiver()
        val skipIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_SKIP
        }
        receiver.handleIntent(skipIntent, workoutEngine, settingsManager, notificationManager)

        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun testFinishSessionClearsActiveRestTimer() = runBlocking {
        val progId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Teste 5"))
        val tplId = dao.insertTemplate(WorkoutTemplateEntity(programId = progId, name = "Treino E", shortIdentifier = "E"))
        workoutEngine.startSession(tplId)
        val activeSession = dao.getActiveSession()!!

        workoutEngine.startRestTimer(
            durationSeconds = 60,
            workoutSessionId = activeSession.id,
            exerciseSessionId = 1L,
            timerType = "REST_SET"
        )
        assertNotNull(workoutEngine.restTimerTarget.first())

        workoutEngine.finishSession(activeSession.id)

        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
    }
}
