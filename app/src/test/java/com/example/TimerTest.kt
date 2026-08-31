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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MainApplication::class)
class TimerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var settingsManager: SettingsManager
    private lateinit var workoutEngine: WorkoutEngine
    private lateinit var notificationManager: WorkoutNotificationManager

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workoutDao()
        settingsManager = SettingsManager(context)
        settingsManager.clearAll()
        workoutEngine = WorkoutEngine(dao, settingsManager)
        notificationManager = WorkoutNotificationManager(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `test start adjust and skip rest timer preserves metadata`() = runBlocking {
        val now = System.currentTimeMillis()
        val duration = 90
        val sessionId = 100L
        val exerciseSessionId = 200L
        val timerType = "REST_SET"

        workoutEngine.startRestTimer(
            durationSeconds = duration,
            workoutSessionId = sessionId,
            exerciseSessionId = exerciseSessionId,
            timerType = timerType
        )

        val target = workoutEngine.restTimerTarget.first()
        assertNotNull(target)
        assertTrue(target!! >= now + (duration * 1000L) - 500L)

        // Verify DataStore persistence of full context
        assertEquals(sessionId, settingsManager.restTimerSessionIdFlow.first { it != null })
        assertEquals(exerciseSessionId, settingsManager.restTimerExerciseSessionIdFlow.first { it != null })
        assertEquals(timerType, settingsManager.restTimerTypeFlow.first { it != null })

        // Adjust by +30 seconds
        val newTarget = workoutEngine.adjustRestTimer(30)
        assertEquals(target + 30000L, newTarget)
        assertEquals(newTarget, workoutEngine.restTimerTarget.first())

        // Ensure metadata is still preserved after adjust
        assertEquals(sessionId, settingsManager.restTimerSessionIdFlow.first { it != null })
        assertEquals(exerciseSessionId, settingsManager.restTimerExerciseSessionIdFlow.first { it != null })
        assertEquals(timerType, settingsManager.restTimerTypeFlow.first { it != null })

        // Skip timer
        workoutEngine.skipRestTimer()
        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
        assertNull(settingsManager.restTimerSessionIdFlow.first { it == null })
        assertNull(settingsManager.restTimerExerciseSessionIdFlow.first { it == null })
        assertNull(settingsManager.restTimerTypeFlow.first { it == null })
    }

    @Test
    fun `test restore timer successfully with complete valid context`() = runBlocking {
        // Setup Active Workout Session
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino Peito"
            )
        )
        val exId = dao.insertExercise(ExerciseEntity(name = "Supino", canonicalId = "supino", primaryMuscle = "Peito"))
        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino",
                sortOrder = 0
            )
        )

        val futureDeadline = System.currentTimeMillis() + 60000L
        settingsManager.setRestTimerState(
            deadlineMs = futureDeadline,
            workoutSessionId = sessionId,
            exerciseSessionId = exSessionId,
            timerType = "REST_SET"
        )

        val restored = workoutEngine.restoreTimerState()
        assertTrue("Timer should be restored", restored)
        assertEquals(futureDeadline, workoutEngine.restTimerTarget.first())
    }

    @Test
    fun `test restore timer fails on workoutSession mismatch`() = runBlocking {
        // Active session ID is 1
        val actualSessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )
        val exId = dao.insertExercise(ExerciseEntity(name = "Supino", canonicalId = "supino", primaryMuscle = "Peito"))
        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = actualSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino",
                sortOrder = 0
            )
        )

        // Saved state points to a different session (e.g. 999)
        settingsManager.setRestTimerState(
            deadlineMs = System.currentTimeMillis() + 60000L,
            workoutSessionId = 999L,
            exerciseSessionId = exSessionId,
            timerType = "REST_SET"
        )

        val restored = workoutEngine.restoreTimerState()
        assertFalse("Restore must fail on session mismatch", restored)
        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun `test restore timer fails on exerciseSession mismatch`() = runBlocking {
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )

        // Stored exerciseSessionId does not exist or belongs to another session
        settingsManager.setRestTimerState(
            deadlineMs = System.currentTimeMillis() + 60000L,
            workoutSessionId = sessionId,
            exerciseSessionId = 8888L,
            timerType = "REST_SET"
        )

        val restored = workoutEngine.restoreTimerState()
        assertFalse("Restore must fail on exercise session mismatch", restored)
        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun `test restore timer fails on invalid timerType`() = runBlocking {
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )
        val exId = dao.insertExercise(ExerciseEntity(name = "Supino", canonicalId = "supino", primaryMuscle = "Peito"))
        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino",
                sortOrder = 0
            )
        )

        // Invalid timer type
        settingsManager.setRestTimerState(
            deadlineMs = System.currentTimeMillis() + 60000L,
            workoutSessionId = sessionId,
            exerciseSessionId = exSessionId,
            timerType = "UNKNOWN_TYPE"
        )

        val restored = workoutEngine.restoreTimerState()
        assertFalse("Restore must fail on invalid timerType", restored)
        assertNull(workoutEngine.restTimerTarget.first())
    }

    @Test
    fun `test restore timer fails on expired deadline`() = runBlocking {
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )
        val exId = dao.insertExercise(ExerciseEntity(name = "Supino", canonicalId = "supino", primaryMuscle = "Peito"))
        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino",
                sortOrder = 0
            )
        )

        // Expired deadline in the past
        settingsManager.setRestTimerState(
            deadlineMs = System.currentTimeMillis() - 5000L,
            workoutSessionId = sessionId,
            exerciseSessionId = exSessionId,
            timerType = "REST_SET"
        )

        val restored = workoutEngine.restoreTimerState()
        assertFalse("Restore must fail on expired deadline", restored)
        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
    }

    @Test
    fun `test skip timer clears all state and does not finish workout session`() = runBlocking {
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )

        workoutEngine.startRestTimer(
            durationSeconds = 60,
            workoutSessionId = sessionId,
            exerciseSessionId = 10L,
            timerType = "REST_SET"
        )
        assertNotNull(workoutEngine.restTimerTarget.first())

        workoutEngine.skipRestTimer()

        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
        assertNull(settingsManager.restTimerSessionIdFlow.first { it == null })
        assertNull(settingsManager.restTimerExerciseSessionIdFlow.first { it == null })
        assertNull(settingsManager.restTimerTypeFlow.first { it == null })

        // Ensure session remains IN_PROGRESS
        val session = dao.getActiveSession()
        assertNotNull(session)
        assertEquals(SessionStatus.IN_PROGRESS.name, session!!.status)
    }

    @Test
    fun `test finish workout clears timer and sets session completed`() = runBlocking {
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )

        workoutEngine.startRestTimer(60, sessionId, 10L, "REST_SET")
        assertNotNull(workoutEngine.restTimerTarget.first())

        workoutEngine.finishSession(sessionId)

        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
        val active = dao.getActiveSession()
        assertNull("Active session should be null after completion", active)
    }

    @Test
    fun `test cancel workout clears timer and sets session cancelled`() = runBlocking {
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )

        workoutEngine.startRestTimer(60, sessionId, 10L, "REST_SET")
        assertNotNull(workoutEngine.restTimerTarget.first())

        workoutEngine.cancelSession(sessionId)

        assertNull(workoutEngine.restTimerTarget.first())
        assertNull(settingsManager.restTimerDeadlineFlow.first { it == null })
        val active = dao.getActiveSession()
        assertNull("Active session should be null after cancellation", active)
    }

    @Test
    fun `test auto rest timer hierarchy on set completion`() = runBlocking {
        // Setup Exercise
        val exId = dao.insertExercise(
            ExerciseEntity(
                name = "Supino",
                canonicalId = "supino-reto-barra",
                primaryMuscle = "Peitoral"
            )
        )

        // Setup Session
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = 1L,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = "Treino A"
            )
        )

        // 1. Level 1: ExerciseSession.restDurationSecondsSnapshot (e.g. 45s)
        val exSessionId1 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino",
                sortOrder = 0,
                restDurationSecondsSnapshot = 45
            )
        )

        val setLog1 = SetLogEntity(
            id = 1L,
            exerciseSessionId = exSessionId1,
            setNumber = 1,
            repetitions = 10,
            weight = 80f,
            completed = true
        )
        dao.insertSetLogs(listOf(setLog1))

        workoutEngine.updateSet(setLog1)
        var target = workoutEngine.restTimerTarget.first()
        assertNotNull(target)
        val now = System.currentTimeMillis()
        assertTrue("Expected ~45s target", target!! in (now + 40000L)..(now + 50000L))

        // 2. Level 2: ExerciseUserOverrideEntity.defaultRestSeconds (e.g. 75s)
        dao.insertOrUpdateOverride(
            ExerciseUserOverrideEntity(
                exerciseId = exId,
                defaultRestSeconds = 75
            )
        )

        val exSessionId2 = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino",
                sortOrder = 1,
                restDurationSecondsSnapshot = null // Null so it falls back to override
            )
        )

        val setLog2 = SetLogEntity(
            id = 2L,
            exerciseSessionId = exSessionId2,
            setNumber = 1,
            repetitions = 10,
            weight = 80f,
            completed = true
        )
        dao.insertSetLogs(listOf(setLog2))

        workoutEngine.updateSet(setLog2)
        target = workoutEngine.restTimerTarget.first()
        assertNotNull(target)
        val now2 = System.currentTimeMillis()
        assertTrue("Expected ~75s target", target!! in (now2 + 70000L)..(now2 + 80000L))
    }
}

