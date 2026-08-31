package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun migrateAllAndVerifyDataPreservation() {
        // Create earliest DB manually (v10) with initial test data
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
            .name(TEST_DB)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DbHelperV10.SCHEMA.split("\n").forEach {
                        if (it.isNotBlank()) {
                            db.execSQL(it)
                        }
                    }

                    // 1. Insert Canonical Exercise (canonicalId set, isUserCreated does not exist in v10)
                    db.execSQL("INSERT INTO exercises (id, name, canonicalId, primaryMuscle, equipment, active, rirEnabled, isBodyweight, contentVersion) VALUES (1, 'Supino Reto', 'supino-reto-barra', 'Peitoral', 'Barra', 1, 0, 0, 1)")

                    // 2. Insert Legacy Manual Exercise (canonicalId null)
                    db.execSQL("INSERT INTO exercises (id, name, canonicalId, primaryMuscle, equipment, active, rirEnabled, isBodyweight, contentVersion) VALUES (2, 'Rosca Customizada Caseira', NULL, 'Bíceps', 'Halteres', 1, 0, 0, 1)")

                    // 3. Insert WorkoutProgram
                    db.execSQL("INSERT INTO workout_programs (id, name, isCurrent) VALUES (1, 'Programa Força', 1)")

                    // 4. Insert WorkoutTemplate
                    db.execSQL("INSERT INTO workout_templates (id, programId, name, shortIdentifier, orderInProgram) VALUES (1, 1, 'Treino A', 'A', 0)")

                    // 5. Insert WorkoutTemplateExercise
                    db.execSQL("INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds) VALUES (1, 1, 1, 0, 3, 8, 12, 90)")

                    // 6. Insert WorkoutSession
                    db.execSQL("INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot) VALUES (1, 1, 10000, 12000, 'COMPLETED', 'Excelente treino', 'Treino A')")

                    // 7. Insert ExerciseSession
                    db.execSQL("INSERT INTO exercise_sessions (id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot, sortOrder) VALUES (1, 1, 1, 1, 'Supino Reto', 0)")

                    // 8. Insert SetLog
                    db.execSQL("INSERT INTO set_logs (id, exerciseSessionId, setNumber, type, weight, repetitions, completed, startedAt, finishedAt) VALUES (1, 1, 1, 'NORMAL', 80.0, 10, 1, 10100, 10150)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
            
        val factory = FrameworkSQLiteOpenHelperFactory()
        val db = factory.create(config).writableDatabase
        db.close()

        // Validate target schema (v17) using generated 17.json
        val validatedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            17,
            true,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17
        )
        
        // 1. Validate Columns exist
        validatedDb.query("SELECT * FROM workout_programs").use {
            assert(it.getColumnIndex("externalId") != -1)
            assert(it.getColumnIndex("contentVersion") != -1)
        }

        // 2. Validate Canonical Exercise preservation & classification (isUserCreated == 0)
        validatedDb.query("SELECT id, name, canonicalId, isUserCreated FROM exercises WHERE id = 1").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals("Supino Reto", it.getString(it.getColumnIndexOrThrow("name")))
            org.junit.Assert.assertEquals("supino-reto-barra", it.getString(it.getColumnIndexOrThrow("canonicalId")))
            org.junit.Assert.assertEquals(0, it.getInt(it.getColumnIndexOrThrow("isUserCreated")))
        }

        // 3. Validate Legacy Manual Exercise preservation & classification (isUserCreated == 1)
        validatedDb.query("SELECT id, name, canonicalId, isUserCreated FROM exercises WHERE id = 2").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals("Rosca Customizada Caseira", it.getString(it.getColumnIndexOrThrow("name")))
            org.junit.Assert.assertEquals(1, it.getInt(it.getColumnIndexOrThrow("isUserCreated")))
        }

        // 4. Validate WorkoutProgram data preserved
        validatedDb.query("SELECT id, name, isCurrent, contentVersion FROM workout_programs WHERE id = 1").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals("Programa Força", it.getString(it.getColumnIndexOrThrow("name")))
            org.junit.Assert.assertEquals(1, it.getInt(it.getColumnIndexOrThrow("isCurrent")))
            org.junit.Assert.assertEquals(0, it.getInt(it.getColumnIndexOrThrow("contentVersion")))
        }

        // 5. Validate WorkoutTemplate data preserved
        validatedDb.query("SELECT id, programId, name, shortIdentifier FROM workout_templates WHERE id = 1").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("programId")))
            org.junit.Assert.assertEquals("Treino A", it.getString(it.getColumnIndexOrThrow("name")))
        }

        // 6. Validate WorkoutSession data preserved
        validatedDb.query("SELECT id, templateId, status, templateNameSnapshot FROM workout_sessions WHERE id = 1").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("templateId")))
            org.junit.Assert.assertEquals("COMPLETED", it.getString(it.getColumnIndexOrThrow("status")))
            org.junit.Assert.assertEquals("Treino A", it.getString(it.getColumnIndexOrThrow("templateNameSnapshot")))
        }

        // 7. Validate ExerciseSession data preserved
        validatedDb.query("SELECT id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot FROM exercise_sessions WHERE id = 1").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("sessionId")))
            org.junit.Assert.assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("plannedExerciseId")))
            org.junit.Assert.assertEquals("Supino Reto", it.getString(it.getColumnIndexOrThrow("exerciseNameSnapshot")))
        }

        // 8. Validate SetLog data preserved (and new nullable rpe/rir columns exist)
        validatedDb.query("SELECT id, exerciseSessionId, weight, repetitions, completed, rpe, rir FROM set_logs WHERE id = 1").use {
            assert(it.moveToFirst())
            org.junit.Assert.assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("exerciseSessionId")))
            org.junit.Assert.assertEquals(80.0, it.getDouble(it.getColumnIndexOrThrow("weight")), 0.01)
            org.junit.Assert.assertEquals(10, it.getInt(it.getColumnIndexOrThrow("repetitions")))
            org.junit.Assert.assertEquals(1, it.getInt(it.getColumnIndexOrThrow("completed")))
            assert(it.isNull(it.getColumnIndexOrThrow("rpe")))
            assert(it.isNull(it.getColumnIndexOrThrow("rir")))
        }
        
        validatedDb.close()
    }
}
