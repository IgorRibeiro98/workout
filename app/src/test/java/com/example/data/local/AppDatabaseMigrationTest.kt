package com.example.data.local

import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigrationTest {

    private val TEST_DB = "migration-test-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun testMigration28To29And29To30PreservesData() {
        // Step 1: Create database at version 28
        var db = helper.createDatabase(TEST_DB, 28)

        // Insert sample data in version 28
        db.execSQL(
            """
            INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot)
            VALUES (1, NULL, 1000, 2000, 'COMPLETED', 'Great workout', 'Push Day')
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO gamification_events (id, type, timestamp, source, dedupeKey, metadataJson)
            VALUES ('ev_1', 'WORKOUT_COMPLETED', 2000, 'MANUAL', 'dedupe_1', '{}')
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO xp_transactions (id, eventId, amount, reason, createdAt)
            VALUES ('xp_1', 'ev_1', 100, 'WORKOUT_COMPLETED', 2000)
            """.trimIndent()
        )

        db.close()

        // Step 2: Migrate from 28 to 29 and validate schema
        db = helper.runMigrationsAndValidate(TEST_DB, 29, true, AppDatabase.MIGRATION_28_29)

        // Insert data into newly created weekly_goal_history in version 29
        db.execSQL(
            """
            INSERT INTO weekly_goal_history (effectiveFromWeekStartEpochDay, goal, createdAt)
            VALUES (19000, 4, 3000)
            """.trimIndent()
        )

        // Verify v28 data preserved in v29
        var sessionCursor = db.query("SELECT * FROM workout_sessions WHERE id = 1")
        assertTrue(sessionCursor.moveToFirst())
        assertEquals("Great workout", sessionCursor.getString(sessionCursor.getColumnIndexOrThrow("notes")))
        sessionCursor.close()

        var eventCursor = db.query("SELECT * FROM gamification_events WHERE id = 'ev_1'")
        assertTrue(eventCursor.moveToFirst())
        assertEquals("WORKOUT_COMPLETED", eventCursor.getString(eventCursor.getColumnIndexOrThrow("type")))
        eventCursor.close()

        var xpCursor = db.query("SELECT * FROM xp_transactions WHERE id = 'xp_1'")
        assertTrue(xpCursor.moveToFirst())
        assertEquals(100, xpCursor.getInt(xpCursor.getColumnIndexOrThrow("amount")))
        xpCursor.close()

        db.close()

        // Step 3: Migrate from 29 to 30 and validate schema
        db = helper.runMigrationsAndValidate(TEST_DB, 30, true, AppDatabase.MIGRATION_29_30)

        // Verify v28 and v29 data preserved in v30
        sessionCursor = db.query("SELECT * FROM workout_sessions WHERE id = 1")
        assertTrue(sessionCursor.moveToFirst())
        assertEquals(2000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("finishedAt")))
        sessionCursor.close()

        eventCursor = db.query("SELECT * FROM gamification_events WHERE id = 'ev_1'")
        assertTrue(eventCursor.moveToFirst())
        assertEquals("dedupe_1", eventCursor.getString(eventCursor.getColumnIndexOrThrow("dedupeKey")))
        eventCursor.close()

        xpCursor = db.query("SELECT * FROM xp_transactions WHERE id = 'xp_1'")
        assertTrue(xpCursor.moveToFirst())
        assertEquals("ev_1", xpCursor.getString(xpCursor.getColumnIndexOrThrow("eventId")))
        xpCursor.close()

        val goalCursor = db.query("SELECT * FROM weekly_goal_history WHERE effectiveFromWeekStartEpochDay = 19000")
        assertTrue(goalCursor.moveToFirst())
        assertEquals(4, goalCursor.getInt(goalCursor.getColumnIndexOrThrow("goal")))
        goalCursor.close()

        // Insert and verify data in the new achievement_unlocks table in v30
        db.execSQL(
            """
            INSERT INTO achievement_unlocks (achievementId, unlockedAt, triggerEventId, definitionVersion)
            VALUES ('first_workout', 2000, 'ev_1', 1)
            """.trimIndent()
        )

        val unlockCursor = db.query("SELECT * FROM achievement_unlocks WHERE achievementId = 'first_workout'")
        assertTrue(unlockCursor.moveToFirst())
        assertEquals(2000L, unlockCursor.getLong(unlockCursor.getColumnIndexOrThrow("unlockedAt")))
        assertEquals("ev_1", unlockCursor.getString(unlockCursor.getColumnIndexOrThrow("triggerEventId")))
        assertEquals(1, unlockCursor.getInt(unlockCursor.getColumnIndexOrThrow("definitionVersion")))
        unlockCursor.close()

        db.close()
    }
}
