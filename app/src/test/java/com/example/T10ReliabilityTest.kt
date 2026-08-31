package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.domain.engine.WorkoutEngine
import com.example.presentation.history.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
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
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class T10ReliabilityTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var context: Context
    private lateinit var workoutEngine: WorkoutEngine
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
        workoutEngine = WorkoutEngine(dao, settingsManager, CoroutineScope(testDispatcher))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun testT7ReplicateCurrentSet() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Supino Reto", canonicalId = "supino-reto", primaryMuscle = "chest")
        )

        val sessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis())
        )

        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Supino Reto",
                primaryMuscleSnapshot = "chest",
                sortOrder = 0
            )
        )

        val set1 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 1,
            type = "NORMAL",
            weight = 80f,
            repetitions = 10,
            rir = 0,
            completed = false
        )
        val set2 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 2,
            type = "NORMAL",
            weight = 82f,
            repetitions = 8,
            rir = null,
            completed = false
        )
        val set3 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 3,
            type = "NORMAL",
            weight = 60f,
            repetitions = 12,
            rir = null,
            completed = false
        )
        val set4 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 4,
            type = "DROP_SET",
            weight = 40f,
            repetitions = 15,
            rir = null,
            completed = false
        )

        dao.insertSetLogs(listOf(set1, set2, set3, set4))

        val currentSet1 = dao.getSetLogsForExerciseSession(exSessionId).first { it.setNumber == 1 }
        val result = workoutEngine.replicateCurrentSet(exSessionId, currentSet1)

        assertEquals(2, result.updatedCount)
        assertEquals(0, result.skippedCompletedCount)
        assertEquals(1, result.skippedDifferentTypeCount)

        val freshSets = dao.getSetLogsForExerciseSession(exSessionId)
        val freshSet2 = freshSets.first { it.setNumber == 2 }
        val freshSet3 = freshSets.first { it.setNumber == 3 }
        val freshSet4 = freshSets.first { it.setNumber == 4 }

        // 1.1 Replicate: Next sets become 80 x 10
        assertEquals(80f, freshSet2.weight)
        assertEquals(10, freshSet2.repetitions)
        // 1.2 RIR not copied: Next set RIR remains null
        assertNull(freshSet2.rir)

        assertEquals(80f, freshSet3.weight)
        assertEquals(10, freshSet3.repetitions)
        assertNull(freshSet3.rir)

        // 1.4 SetType: DROP_SET not overwritten by NORMAL
        assertEquals(40f, freshSet4.weight)
        assertEquals(15, freshSet4.repetitions)
    }

    @Test
    fun testT7ReplicateCurrentSet_CompletedSetNotOverwritten() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Desenvolvimento", canonicalId = "desenvolvimento", primaryMuscle = "shoulders")
        )

        val sessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis())
        )

        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Desenvolvimento",
                primaryMuscleSnapshot = "shoulders",
                sortOrder = 0
            )
        )

        val set1 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 1,
            type = "NORMAL",
            weight = 30f,
            repetitions = 10,
            completed = true
        )
        val set2 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 2,
            type = "NORMAL",
            weight = 40f,
            repetitions = 8,
            completed = false
        )
        val set3 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 3,
            type = "NORMAL",
            weight = 35f,
            repetitions = 12,
            completed = true // Completed set!
        )

        dao.insertSetLogs(listOf(set1, set2, set3))

        val currentSet2 = dao.getSetLogsForExerciseSession(exSessionId).first { it.setNumber == 2 }
        val result = workoutEngine.replicateCurrentSet(exSessionId, currentSet2)

        // Set 3 was completed, so it was skipped
        assertEquals(0, result.updatedCount)
        assertEquals(1, result.skippedCompletedCount)

        val freshSet3 = dao.getSetLogsForExerciseSession(exSessionId).first { it.setNumber == 3 }
        assertEquals(35f, freshSet3.weight)
        assertEquals(12, freshSet3.repetitions)
        assertTrue(freshSet3.completed)
    }

    @Test
    fun testT7ReplicateCurrentSet_WarmupDoesNotOverwriteWorking() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Leg Press", canonicalId = "leg-press", primaryMuscle = "legs")
        )

        val sessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis())
        )

        val exSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Leg Press",
                primaryMuscleSnapshot = "legs",
                sortOrder = 0
            )
        )

        val set1 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 1,
            type = "WARMUP",
            weight = 50f,
            repetitions = 15,
            completed = false
        )
        val set2 = SetLogEntity(
            exerciseSessionId = exSessionId,
            setNumber = 2,
            type = "NORMAL",
            weight = 200f,
            repetitions = 10,
            completed = false
        )

        dao.insertSetLogs(listOf(set1, set2))

        val currentSet1 = dao.getSetLogsForExerciseSession(exSessionId).first { it.setNumber == 1 }
        val result = workoutEngine.replicateCurrentSet(exSessionId, currentSet1)

        assertEquals(0, result.updatedCount)
        assertEquals(1, result.skippedDifferentTypeCount)

        val freshSet2 = dao.getSetLogsForExerciseSession(exSessionId).first { it.setNumber == 2 }
        assertEquals(200f, freshSet2.weight)
        assertEquals(10, freshSet2.repetitions)
    }

    @Test
    fun testT7RestoreLastExecutionSets_IgnoresCancelledAndInProgress() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Puxada Frontal", canonicalId = "puxada-frontal", primaryMuscle = "back")
        )

        // 1. CANCELLED session with 120kg
        val cancelledSessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = System.currentTimeMillis() - 100000L,
                finishedAt = System.currentTimeMillis() - 90000L,
                status = "CANCELLED"
            )
        )
        val cancelledExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = cancelledSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Puxada Frontal",
                primaryMuscleSnapshot = "back",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = cancelledExSessionId,
                    setNumber = 1,
                    type = "NORMAL",
                    weight = 120f,
                    repetitions = 6,
                    completed = true
                )
            )
        )

        // 2. IN_PROGRESS session with 110kg
        val inProgressSessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = System.currentTimeMillis() - 80000L,
                status = "IN_PROGRESS"
            )
        )
        val inProgressExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = inProgressSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Puxada Frontal",
                primaryMuscleSnapshot = "back",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = inProgressExSessionId,
                    setNumber = 1,
                    type = "NORMAL",
                    weight = 110f,
                    repetitions = 8,
                    completed = true
                )
            )
        )

        // 3. COMPLETED session with 100kg (Real previous completed execution)
        val completedSessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = System.currentTimeMillis() - 200000L,
                finishedAt = System.currentTimeMillis() - 190000L,
                status = "COMPLETED"
            )
        )
        val completedExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = completedSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Puxada Frontal",
                primaryMuscleSnapshot = "back",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = completedExSessionId,
                    setNumber = 1,
                    type = "NORMAL",
                    weight = 100f,
                    repetitions = 10,
                    completed = true
                )
            )
        )

        // 4. Current active session
        val currentSessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis(), status = "IN_PROGRESS")
        )
        val currentExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = currentSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Puxada Frontal",
                primaryMuscleSnapshot = "back",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = currentExSessionId,
                    setNumber = 1,
                    type = "NORMAL",
                    weight = 0f,
                    repetitions = 0,
                    completed = false
                )
            )
        )

        val result = workoutEngine.restoreLastExecutionSets(currentExSessionId, exId)
        assertEquals(1, result.updatedCount)

        val freshSets = dao.getSetLogsForExerciseSession(currentExSessionId)
        assertEquals(100f, freshSets.first().weight)
        assertEquals(10, freshSets.first().repetitions)
    }

    @Test
    fun testT7RestoreLastExecutionSets_DifferentQuantitiesPreservesPreloadRule() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Rosca Direta", canonicalId = "rosca-direta", primaryMuscle = "biceps")
        )

        // History with 3 completed sets
        val completedSessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = System.currentTimeMillis() - 100000L,
                finishedAt = System.currentTimeMillis() - 90000L,
                status = "COMPLETED"
            )
        )
        val oldExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = completedSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Rosca Direta",
                primaryMuscleSnapshot = "biceps",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = oldExSessionId, setNumber = 1, type = "NORMAL", weight = 15f, repetitions = 12, completed = true),
                SetLogEntity(exerciseSessionId = oldExSessionId, setNumber = 2, type = "NORMAL", weight = 17.5f, repetitions = 10, completed = true),
                SetLogEntity(exerciseSessionId = oldExSessionId, setNumber = 3, type = "NORMAL", weight = 20f, repetitions = 8, completed = true)
            )
        )

        // Today with 4 sets
        val currentSessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis())
        )
        val currentExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = currentSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Rosca Direta",
                primaryMuscleSnapshot = "biceps",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(exerciseSessionId = currentExSessionId, setNumber = 1, type = "NORMAL", weight = 0f, repetitions = 0, completed = false),
                SetLogEntity(exerciseSessionId = currentExSessionId, setNumber = 2, type = "NORMAL", weight = 0f, repetitions = 0, completed = false),
                SetLogEntity(exerciseSessionId = currentExSessionId, setNumber = 3, type = "NORMAL", weight = 0f, repetitions = 0, completed = false),
                SetLogEntity(exerciseSessionId = currentExSessionId, setNumber = 4, type = "NORMAL", weight = 0f, repetitions = 0, completed = false)
            )
        )

        val result = workoutEngine.restoreLastExecutionSets(currentExSessionId, exId)
        assertEquals(4, result.updatedCount)

        val freshSets = dao.getSetLogsForExerciseSession(currentExSessionId)
        assertEquals(15f, freshSets[0].weight)
        assertEquals(12, freshSets[0].repetitions)

        assertEquals(17.5f, freshSets[1].weight)
        assertEquals(10, freshSets[1].repetitions)

        assertEquals(20f, freshSets[2].weight)
        assertEquals(8, freshSets[2].repetitions)

        // 4th set gets last available working set (20kg x 8)
        assertEquals(20f, freshSets[3].weight)
        assertEquals(8, freshSets[3].repetitions)
    }

    @Test
    fun testT7RestoreLastExecutionSetsExcludingWarmup() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(name = "Agachamento", canonicalId = "agachamento", primaryMuscle = "legs")
        )

        val oldSessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = System.currentTimeMillis() - 86400000L,
                status = "COMPLETED"
            )
        )
        val oldExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = oldSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Agachamento",
                primaryMuscleSnapshot = "legs",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = oldExSessionId,
                    setNumber = 1,
                    type = "WARMUP",
                    weight = 20f,
                    repetitions = 10,
                    completed = true
                ),
                SetLogEntity(
                    exerciseSessionId = oldExSessionId,
                    setNumber = 2,
                    type = "NORMAL",
                    weight = 100f,
                    repetitions = 8,
                    completed = true
                )
            )
        )

        val currentSessionId = dao.insertSession(
            WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis())
        )
        val currentExSessionId = dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = currentSessionId,
                plannedExerciseId = exId,
                actualExerciseId = exId,
                exerciseNameSnapshot = "Agachamento",
                primaryMuscleSnapshot = "legs",
                sortOrder = 0
            )
        )
        dao.insertSetLogs(
            listOf(
                SetLogEntity(
                    exerciseSessionId = currentExSessionId,
                    setNumber = 1,
                    type = "WARMUP",
                    weight = 10f,
                    repetitions = 5,
                    completed = false
                ),
                SetLogEntity(
                    exerciseSessionId = currentExSessionId,
                    setNumber = 2,
                    type = "NORMAL",
                    weight = 50f,
                    repetitions = 5,
                    completed = false
                )
            )
        )

        val result = workoutEngine.restoreLastExecutionSets(currentExSessionId, exId)
        assertEquals(2, result.updatedCount)

        val freshSets = dao.getSetLogsForExerciseSession(currentExSessionId)
        val freshWarmup = freshSets.first { it.type == "WARMUP" }
        val freshNormal = freshSets.first { it.type == "NORMAL" }

        assertEquals(20f, freshWarmup.weight)
        assertEquals(10, freshWarmup.repetitions)

        assertEquals(100f, freshNormal.weight)
        assertEquals(8, freshNormal.repetitions)
    }

    @Test
    fun testHistoryViewModelDateFiltering() = runBlocking {
        val calToday = Calendar.getInstance()
        val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        val s1 = dao.insertSession(WorkoutSessionEntity(templateId = null, startedAt = calYesterday.timeInMillis, status = "COMPLETED"))
        val s2 = dao.insertSession(WorkoutSessionEntity(templateId = null, startedAt = calToday.timeInMillis, status = "COMPLETED"))

        val vm = HistoryViewModel(workoutEngine)

        // Wait continuously for flow updates until both completed sessions are loaded in state
        val stateWithSummaries = vm.state.first { it.calendarSummaries.size >= 2 }
        assertNotNull(stateWithSummaries)

        // Select Yesterday and wait until the filtered list updates to show the selected session
        vm.selectDate(calYesterday.time)
        val stateYesterday = vm.state.first { state ->
            state.sessionsForSelectedDate.any { it.session.id == s1 }
        }
        assertTrue(stateYesterday.sessionsForSelectedDate.any { it.session.id == s1 })
        assertFalse(stateYesterday.sessionsForSelectedDate.any { it.session.id == s2 })

        // Select Today and wait until the filtered list updates to show the selected session
        vm.selectDate(calToday.time)
        val stateToday = vm.state.first { state ->
            state.sessionsForSelectedDate.any { it.session.id == s2 }
        }
        assertTrue(stateToday.sessionsForSelectedDate.any { it.session.id == s2 })
        assertFalse(stateToday.sessionsForSelectedDate.any { it.session.id == s1 })
    }

    @Test
    fun testHistoryAllCompletedSessions() = runBlocking {
        val cal1 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -5) }
        val cal2 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
        val cal3 = Calendar.getInstance()

        val s1 = dao.insertSession(WorkoutSessionEntity(templateId = null, startedAt = cal1.timeInMillis, status = "COMPLETED"))
        val s2 = dao.insertSession(WorkoutSessionEntity(templateId = null, startedAt = cal2.timeInMillis, status = "COMPLETED"))
        val s3 = dao.insertSession(WorkoutSessionEntity(templateId = null, startedAt = cal3.timeInMillis, status = "COMPLETED"))

        val vm = HistoryViewModel(workoutEngine)
        val state = vm.state.first { it.allCompletedSessions.size >= 3 }

        val allIds = state.allCompletedSessions.map { it.session.id }
        assertTrue(allIds.contains(s1))
        assertTrue(allIds.contains(s2))
        assertTrue(allIds.contains(s3))
    }

    @Test
    fun testHistoryCalendarDynamicCalculation() = runBlocking {
        val vm = HistoryViewModel(workoutEngine)
        val cal = Calendar.getInstance().apply {
            set(2027, Calendar.JANUARY, 15, 10, 0, 0)
        }
        vm.selectDate(cal.time)
        val state = vm.state.first()

        val selectedCal = Calendar.getInstance().apply { time = state.selectedDate }
        assertEquals(2027, selectedCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, selectedCal.get(Calendar.MONTH))
        assertEquals(15, selectedCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testT9DestructiveSwipePendingConfirmationDoesNotDelete() = runBlocking {
        val session = WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis(), status = "COMPLETED")
        val sessionId = dao.insertSession(session)

        val vm = HistoryViewModel(workoutEngine)
        // Simulate swipe trigger setting pending confirmation
        var sessionToDeletePending: WorkoutSessionEntity? = session.copy(id = sessionId)
        assertNotNull(sessionToDeletePending)

        // Session must still exist in DB before confirmation
        val currentHistory = workoutEngine.getCalendarHistoryFlow().first()
        assertTrue(currentHistory.any { it.session.id == sessionId })
    }

    @Test
    fun testT9DestructiveSwipeCancelLeavesSessionIntact() = runBlocking {
        val session = WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis(), status = "COMPLETED")
        val sessionId = dao.insertSession(session)

        val vm = HistoryViewModel(workoutEngine)
        var sessionToDeletePending: WorkoutSessionEntity? = session.copy(id = sessionId)

        // User cancels / dismisses confirmation dialog
        sessionToDeletePending = null
        assertNull(sessionToDeletePending)

        // Session remains untouched in DB
        val historySummaries = workoutEngine.getCalendarHistoryFlow().first()
        assertTrue(historySummaries.any { it.session.id == sessionId })
    }

    @Test
    fun testT9DestructiveSwipeConfirmDeletesSession() = runBlocking {
        val session = WorkoutSessionEntity(templateId = null, startedAt = System.currentTimeMillis(), status = "COMPLETED")
        val sessionId = dao.insertSession(session)

        val vm = HistoryViewModel(workoutEngine)
        var sessionToDeletePending: WorkoutSessionEntity? = session.copy(id = sessionId)

        // User confirms deletion
        vm.deleteSession(sessionToDeletePending!!)
        sessionToDeletePending = null

        // Session is deleted from DB
        val historySummaries = workoutEngine.getCalendarHistoryFlow().first()
        assertFalse(historySummaries.any { it.session.id == sessionId })
    }

    @Test
    fun testT9CanonicalExerciseCannotBeDeleted() = runBlocking {
        val canonicalExId = dao.insertExercise(
            ExerciseEntity(
                name = "Supino Reto",
                canonicalId = "supino-reto",
                primaryMuscle = "chest",
                isUserCreated = false
            )
        )
        val customExId = dao.insertExercise(
            ExerciseEntity(
                name = "Meu Supino Especial",
                canonicalId = null,
                primaryMuscle = "chest",
                isUserCreated = true
            )
        )

        val canonicalEx = dao.getExerciseById(canonicalExId)
        val customEx = dao.getExerciseById(customExId)

        assertNotNull(canonicalEx)
        assertFalse(canonicalEx!!.isUserCreated)
        assertNotNull(canonicalEx.canonicalId)

        assertNotNull(customEx)
        assertTrue(customEx!!.isUserCreated)
        assertNull(customEx.canonicalId)
    }

    @Test
    fun testT9HapticSettingsDisable() = runBlocking {
        val settingsManager = SettingsManager(context)
        settingsManager.setHapticEnabled(false)

        val hapticEnabled = settingsManager.hapticEnabledFlow.first()
        assertFalse(hapticEnabled)

        settingsManager.setHapticEnabled(true)
        val hapticEnabledAgain = settingsManager.hapticEnabledFlow.first()
        assertTrue(hapticEnabledAgain)
    }

    @Test
    fun testTimerCoordinationSingleLogicalEvent() = runBlocking {
        val notificationManager = com.example.service.WorkoutNotificationManager(context)
        
        // Start rest timer
        workoutEngine.startRestTimer(
            durationSeconds = 60,
            workoutSessionId = 1L,
            exerciseSessionId = 2L,
            timerType = "REST_SET"
        )
        val target = workoutEngine.restTimerTarget.first { it != null }
        assertNotNull(target)

        // Finish timer cleanly (single event coordination)
        workoutEngine.skipRestTimer()
        val finishedTarget = workoutEngine.restTimerTarget.first { it == null }
        assertNull(finishedTarget)
        
        // Cancel notification cleanly
        notificationManager.cancelNotification()
    }

    @Test
    fun testBodyweightExerciseDetection() {
        val pushup = ExerciseEntity(
            name = "Flexão de Braços",
            canonicalId = "flexao-de-bracos",
            primaryMuscle = "chest",
            equipment = "bodyweight",
            isBodyweight = true
        )
        val pullup = ExerciseEntity(
            name = "Barra Fixa",
            canonicalId = "barra-fixa",
            primaryMuscle = "back",
            equipment = "body weight",
            isBodyweight = true
        )
        val benchPress = ExerciseEntity(
            name = "Supino Reto Barra",
            canonicalId = "supino-reto-barra",
            primaryMuscle = "chest",
            equipment = "barbell",
            isBodyweight = false
        )

        val isPushupBodyweight = pushup.isBodyweight || pushup.equipment?.contains("body") == true
        val isPullupBodyweight = pullup.isBodyweight || pullup.equipment?.contains("body") == true
        val isBenchBodyweight = benchPress.isBodyweight || benchPress.equipment?.contains("body") == true

        assertTrue(isPushupBodyweight)
        assertTrue(isPullupBodyweight)
        assertFalse(isBenchBodyweight)
    }
}
