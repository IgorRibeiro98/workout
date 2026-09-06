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

/**
 * A migração 31 → 32 (T16.4) é aditiva e não custa nada ao usuário.
 *
 * Ela cria duas tabelas — o vínculo do conjunto de dados com uma Conta Spark e a tentativa de
 * backup durável — e **não toca em nada existente**. O que este teste protege é justamente isso:
 * ganhar a capacidade de backup não pode reescrever histórico, perder identidade global, mexer na
 * Outbox nem embaralhar ordem.
 *
 * Também prova o estado inicial correto: um banco migrado nasce **sem dono**. Se a migração
 * criasse um vínculo "por padrão", ela teria adotado os dados de alguém sem ninguém confirmar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration31To32Test {

    private val testDb = "migration-31-32-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration31To32_addsBackupTables_andChangesNothingElse() {
        val before = seedVersion31()

        val db = helper.runMigrationsAndValidate(testDb, 32, true, AppDatabase.MIGRATION_31_32)

        // ---- As tabelas novas existem e nascem vazias ------------------------------------
        assertEquals(0, count(db, "cloud_data_binding"))
        assertEquals(0, count(db, "backup_attempts"))

        // ---- Nada existente mudou --------------------------------------------------------
        assertEquals(before, currentState(db))
    }

    @Test
    fun migration31To32_leavesDatasetWithoutOwner() {
        seedVersion31()

        val db = helper.runMigrationsAndValidate(testDb, 32, true, AppDatabase.MIGRATION_31_32)

        // Migrar não é adotar. O padrão continua sendo dado local sem dono remoto, e só a
        // confirmação explícita do usuário grava a primeira linha aqui.
        assertEquals(0, count(db, "cloud_data_binding"))
        // E a Outbox continua exatamente como estava: migrar não produz intenção de sync.
        assertEquals(0, count(db, "sync_outbox"))
    }

    @Test
    fun migration31To32_keepsBackupAttemptIdentityUnique() {
        seedVersion31()
        val db = helper.runMigrationsAndValidate(testDb, 32, true, AppDatabase.MIGRATION_31_32)

        db.execSQL(attemptInsert("tentativa-abc"))

        // Duas linhas com o mesmo `clientBackupId` seriam duas tentativas se passando por uma — e
        // é sobre esse id que a idempotência do servidor funciona.
        val duplicated = runCatching { db.execSQL(attemptInsert("tentativa-abc")) }
        assertTrue("clientBackupId precisa ser único", duplicated.isFailure)
        assertEquals(1, count(db, "backup_attempts"))
    }

    /** O estado do que já existia, como texto — para comparar antes e depois sem ambiguidade. */
    private fun currentState(db: androidx.sqlite.db.SupportSQLiteDatabase): String = buildString {
        append(rows(db, "SELECT id, name, syncId, isCurrent FROM workout_programs ORDER BY id"))
        append(rows(db, "SELECT id, programId, name, shortIdentifier, orderInProgram, syncId FROM workout_templates ORDER BY id"))
        append(rows(db, "SELECT id, templateId, exerciseId, sortOrder, targetSets FROM workout_template_exercises ORDER BY id"))
        append(rows(db, "SELECT id, status, startedAt, finishedAt, notes, syncId FROM workout_sessions ORDER BY id"))
        append(rows(db, "SELECT id, sessionId, exerciseNameSnapshot, plannedOrder, executionOrder FROM exercise_sessions ORDER BY id"))
        append(rows(db, "SELECT id, exerciseSessionId, setNumber, weight, repetitions, completed FROM set_logs ORDER BY id"))
        append(rows(db, "SELECT id, date, weightKg, syncId FROM body_measurements ORDER BY id"))
        append(rows(db, "SELECT id, checkInTime, gymName, syncId FROM check_ins ORDER BY id"))
        append(rows(db, "SELECT id, name, canonicalId, isUserCreated, syncId FROM exercises ORDER BY id"))
        append(rows(db, "SELECT effectiveFromWeekStartEpochDay, goal FROM weekly_goal_history ORDER BY effectiveFromWeekStartEpochDay"))
        append(rows(db, "SELECT exerciseId, displayName, notes FROM exercise_user_overrides ORDER BY exerciseId"))
    }

    private fun seedVersion31(): String {
        val db = helper.createDatabase(testDb, 31)

        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated, canonicalId, slug, syncId) " +
                "VALUES (1, 'Supino Reto', 1, 0, 0, 3, 0, 1, 'canonical.supino', 'supino-reto', NULL)"
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated, syncId) " +
                "VALUES (2, 'Rosca do canto', 1, 0, 0, 0, 1, 0, 'ffffffff-0000-4000-8000-000000000002')"
        )

        db.execSQL(
            "INSERT INTO workout_programs (id, name, description, isCurrent, externalId, contentVersion, syncId) " +
                "VALUES (1, 'ABCDE', 'programa', 1, NULL, 0, 'ffffffff-0000-4000-8000-000000000010')"
        )
        db.execSQL(
            "INSERT INTO workout_templates (id, programId, name, shortIdentifier, orderInProgram, dayOfWeek, syncId) " +
                "VALUES (10, 1, 'Peito', 'A', 0, 'MONDAY', 'ffffffff-0000-4000-8000-000000000020')"
        )
        db.execSQL(
            "INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds) " +
                "VALUES (900, 10, 1, 0, 4, 6, 10, 120)"
        )

        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId) " +
                "VALUES (100, 10, 1000, 2000, 'COMPLETED', 'Treino bom', 'Peito', 'ffffffff-0000-4000-8000-000000000030')"
        )
        db.execSQL(
            "INSERT INTO exercise_sessions (id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot, sortOrder, plannedOrder, executionOrder, restDurationSecondsSnapshot) " +
                "VALUES (1000, 100, 1, 1, 'Supino Reto', 1, 1, 1, 120)"
        )
        db.execSQL(
            "INSERT INTO set_logs (id, exerciseSessionId, setNumber, type, weight, repetitions, completed) " +
                "VALUES (2000, 1000, 1, 'NORMAL', 100.0, 8, 1)"
        )

        db.execSQL(
            "INSERT INTO body_measurements (id, date, createdAt, weightKg, syncId) " +
                "VALUES (1, 500, 500, 80.0, 'ffffffff-0000-4000-8000-000000000040')"
        )
        db.execSQL(
            "INSERT INTO check_ins (id, checkInTime, checkOutTime, gymName, sessionId, syncId) " +
                "VALUES (1, 400, 900, 'Academia', 100, 'ffffffff-0000-4000-8000-000000000050')"
        )
        db.execSQL(
            "INSERT INTO weekly_goal_history (effectiveFromWeekStartEpochDay, goal, createdAt) VALUES (20700, 4, 100)"
        )
        db.execSQL(
            "INSERT INTO exercise_user_overrides (exerciseId, displayName, notes, customPhotoUri, defaultRestSeconds, updatedAt) " +
                "VALUES (1, 'Supino do canto', 'pegada média', 'content://foto/1', 75, 100)"
        )

        val state = currentState(db)
        db.close()
        return state
    }

    private fun attemptInsert(clientBackupId: String): String =
        "INSERT INTO backup_attempts (clientBackupId, ownerUid, deviceId, backupSchemaVersion, " +
            "coveredOutboxSequence, payloadHash, payload, itemCount, sizeBytes, createdAt, status, attemptCount) " +
            "VALUES ('$clientBackupId', 'uid-A', 'device', 1, 0, 'hash', '{}', 0, 2, 100, 'PENDING', 0)"

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        append(if (cursor.isNull(column)) "null" else cursor.getString(column)).append('|')
                    }
                    append('\n')
                }
            }
        }
}
