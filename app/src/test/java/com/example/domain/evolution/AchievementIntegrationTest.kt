package com.example.domain.evolution

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutSessionEntity
import com.example.data.repository.AchievementRepositoryImpl
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.ConsistencyRepositoryImpl
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.repository.AchievementEvaluationOrigin
import com.example.feature.evolution.achievements.AchievementsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AchievementIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var bodyMeasurementRepository: BodyMeasurementRepository
    private lateinit var consistencyRepository: ConsistencyRepositoryImpl
    private lateinit var achievementRepository: AchievementRepositoryImpl
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsManager = SettingsManager(context)
        bodyMeasurementRepository = BodyMeasurementRepository(database.bodyMeasurementDao())
        consistencyRepository = ConsistencyRepositoryImpl(
            workoutDao = database.workoutDao(),
            weeklyGoalDao = database.weeklyGoalDao(),
            settingsManager = settingsManager
        )
        achievementRepository = AchievementRepositoryImpl(
            achievementDao = database.achievementDao(),
            workoutDao = database.workoutDao(),
            gamificationEventDao = database.gamificationEventDao(),
            consistencyRepository = consistencyRepository,
            bodyMeasurementRepository = bodyMeasurementRepository
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testPermanentUnlock_INV01() = runTest {
        // Insert a completed workout session
        val now = System.currentTimeMillis()
        val session = WorkoutSessionEntity(
            templateId = null,
            startedAt = now - 3600000,
            finishedAt = now,
            status = SessionStatus.COMPLETED.name,
            notes = null,
            templateNameSnapshot = "Treino A"
        )
        val sessionId = database.workoutDao().insertSession(session)

        // Evaluate and unlock
        val initialUnlocks = achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.LIVE)
        assertTrue(initialUnlocks.any { it.achievementId == "first_workout" })

        // Check that first_workout is unlocked
        val achievementsBefore = achievementRepository.getAchievements()
        val firstBefore = achievementsBefore.first { it.id == "first_workout" }
        assertNotNull(firstBefore.unlockedAt)
        val initialUnlockTime = firstBefore.unlockedAt

        // Delete session (simulate data removal or recalculation where criteria is no longer met)
        database.workoutDao().deleteWorkoutSession(session.copy(id = sessionId))

        // Re-evaluate
        val newUnlocks = achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.RECONCILIATION)
        assertTrue(newUnlocks.isEmpty())

        // Invariant INV-01: Once unlocked, always unlocked!
        val achievementsAfter = achievementRepository.getAchievements()
        val firstAfter = achievementsAfter.first { it.id == "first_workout" }
        assertNotNull("Achievement must remain permanently unlocked", firstAfter.unlockedAt)
        assertEquals(initialUnlockTime, firstAfter.unlockedAt)
    }

    @Test
    fun testIdempotency_INV02() = runTest {
        val now = System.currentTimeMillis()
        database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = now - 3600000,
                finishedAt = now,
                status = SessionStatus.COMPLETED.name,
                notes = null,
                templateNameSnapshot = "Treino B"
            )
        )

        // 1st evaluation -> unlocks first_workout
        val firstRun = achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.LIVE)
        assertEquals(1, firstRun.filter { it.achievementId == "first_workout" }.size)

        // 2nd evaluation -> idempotent, no duplicate unlocks returned
        val secondRun = achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.LIVE)
        assertEquals(0, secondRun.filter { it.achievementId == "first_workout" }.size)

        // Verify DB only has 1 record for first_workout
        val storedUnlocks = database.achievementDao().getUnlocks()
        val firstWorkoutRows = storedUnlocks.filter { it.achievementId == "first_workout" }
        assertEquals(1, firstWorkoutRows.size)
    }

    @Test
    fun testConcurrency_INV08() = runTest {
        val now = System.currentTimeMillis()
        database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = now - 3600000,
                finishedAt = now,
                status = SessionStatus.COMPLETED.name,
                notes = null,
                templateNameSnapshot = "Treino C"
            )
        )

        // Run 5 simultaneous evaluations
        val deferreds = (1..5).map {
            async(Dispatchers.IO) {
                achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.LIVE)
            }
        }
        val allResults = deferreds.awaitAll()

        // Across all 5 calls, exactly 1 must have returned the new unlock
        val totalFirstWorkoutNewUnlocks = allResults.flatMap { it }.count { it.achievementId == "first_workout" }
        assertEquals(1, totalFirstWorkoutNewUnlocks)

        // DB table must contain exactly 1 row
        val stored = database.achievementDao().getUnlocks()
        assertEquals(1, stored.count { it.achievementId == "first_workout" })
    }

    @Test
    fun testLiveVsReconciliation_INV06() = runTest {
        val now = System.currentTimeMillis()
        database.workoutDao().insertSession(
            WorkoutSessionEntity(
                templateId = null,
                startedAt = now - 3600000,
                finishedAt = now,
                status = SessionStatus.COMPLETED.name,
                notes = null,
                templateNameSnapshot = "Treino D"
            )
        )

        val liveUnlocksList = mutableListOf<com.example.domain.evolution.model.achievement.AchievementUnlock>()
        val job = launch {
            achievementRepository.liveUnlocks.collect { unlock ->
                liveUnlocksList.add(unlock)
            }
        }

        // Test RECONCILIATION: silent, must NOT emit to liveUnlocks
        val reconUnlocks = achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.RECONCILIATION)
        assertEquals(1, reconUnlocks.filter { it.achievementId == "first_workout" }.size)
        // Wait a small moment to ensure no emission happens
        kotlinx.coroutines.delay(50)
        assertTrue("Reconciliation must not emit to liveUnlocks flow", liveUnlocksList.isEmpty())

        // Insert a body measurement
        bodyMeasurementRepository.insertMeasurement(
            BodyMeasurementEntity(
                date = now,
                weightKg = 75f
            )
        )

        // Test LIVE: must emit to liveUnlocks
        val liveUnlocks = achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.LIVE)
        assertEquals(1, liveUnlocks.filter { it.achievementId == "first_measurement" }.size)
        kotlinx.coroutines.delay(50)
        assertEquals(1, liveUnlocksList.filter { it.achievementId == "first_measurement" }.size)

        job.cancel()
    }

    @Test
    fun testBodyLiveUnlockViaRepositoryCallback() = runTest {
        var callbackFired = false
        bodyMeasurementRepository.onMeasurementChanged = {
            callbackFired = true
            achievementRepository.evaluateAndUnlock(AchievementEvaluationOrigin.LIVE)
        }

        bodyMeasurementRepository.insertMeasurement(
            BodyMeasurementEntity(
                date = System.currentTimeMillis(),
                weightKg = 80f
            )
        )

        assertTrue(callbackFired)
        val achievements = achievementRepository.getAchievements()
        val bodyAchievement = achievements.first { it.id == "first_measurement" }
        assertNotNull(bodyAchievement.unlockedAt)
    }

    @Test
    fun testRoomMigrations_28_29_and_29_30() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null) // in-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(28) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // version 28 schema
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase

        // Execute migration 28 -> 29
        AppDatabase.MIGRATION_28_29.migrate(db)
        val cursor29 = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='weekly_goal_history'")
        assertTrue("weekly_goal_history table must exist after MIGRATION_28_29", cursor29.moveToFirst())
        cursor29.close()

        // Execute migration 29 -> 30
        AppDatabase.MIGRATION_29_30.migrate(db)
        val cursor30 = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='achievement_unlocks'")
        assertTrue("achievement_unlocks table must exist after MIGRATION_29_30", cursor30.moveToFirst())
        cursor30.close()

        db.close()
    }
}
