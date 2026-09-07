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
 * A migração 32 → 33 (T16.5) é aditiva e não custa nada ao usuário.
 *
 * Ela cria **uma** tabela — a tentativa de restore durável — e não toca em nada existente. Ganhar a
 * capacidade de restaurar não pode reescrever histórico, perder identidade global, mexer na Outbox,
 * embaralhar ordem nem dar dono a dado nenhum.
 *
 * O outro invariante que ela prova é o mais importante para esta tarefa: um banco migrado nasce
 * **sem tentativa de restore em aberto**. Se a migração criasse uma linha "por padrão", o app
 * abriria acreditando que precisa recuperar algo — e recuperação é justamente o caminho que mexe no
 * dataset inteiro.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration32To33Test {

    private val testDb = "migration-32-33-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration32To33_addsRestoreAttempts_andChangesNothingElse() {
        val before = seedVersion32()

        val db = helper.runMigrationsAndValidate(testDb, 33, true, AppDatabase.MIGRATION_32_33)

        assertEquals("a tabela nova nasce vazia", 0, count(db, "restore_attempts"))
        assertEquals("nada existente pode mudar", before, currentState(db))
    }

    @Test
    fun migration32To33_leavesNoRestoreToRecover() {
        seedVersion32()

        val db = helper.runMigrationsAndValidate(testDb, 33, true, AppDatabase.MIGRATION_32_33)

        // Nenhuma tentativa pendente: abrir o app depois de atualizar não dispara recuperação.
        assertEquals(0, count(db, "restore_attempts"))
        // E o vínculo e a Outbox continuam como estavam — migrar não adota nem sincroniza.
        assertEquals(0, count(db, "cloud_data_binding"))
        assertEquals(0, count(db, "sync_outbox"))
    }

    @Test
    fun migration32To33_keepsRestoreAttemptIdentityUnique() {
        seedVersion32()
        val db = helper.runMigrationsAndValidate(testDb, 33, true, AppDatabase.MIGRATION_32_33)

        db.execSQL(attemptInsert("tentativa-de-restore"))

        // Duas linhas com o mesmo `restoreAttemptId` seriam duas tentativas se passando por uma — e
        // é esse id que nomeia os arquivos em disco daquela tentativa.
        val duplicated = runCatching { db.execSQL(attemptInsert("tentativa-de-restore")) }
        assertTrue("restoreAttemptId precisa ser único", duplicated.isFailure)
        assertEquals(1, count(db, "restore_attempts"))
    }

    /** O estado do que já existia, como texto — para comparar antes e depois sem ambiguidade. */
    private fun currentState(db: androidx.sqlite.db.SupportSQLiteDatabase): String = buildString {
        append(rows(db, "SELECT id, name, syncId, isCurrent FROM workout_programs ORDER BY id"))
        append(rows(db, "SELECT id, programId, name, orderInProgram, syncId FROM workout_templates ORDER BY id"))
        append(rows(db, "SELECT id, templateId, exerciseId, sortOrder, targetSets FROM workout_template_exercises ORDER BY id"))
        append(rows(db, "SELECT id, status, startedAt, finishedAt, notes, syncId FROM workout_sessions ORDER BY id"))
        append(rows(db, "SELECT id, sessionId, exerciseNameSnapshot, plannedOrder, executionOrder FROM exercise_sessions ORDER BY id"))
        append(rows(db, "SELECT id, exerciseSessionId, setNumber, weight, repetitions, completed FROM set_logs ORDER BY id"))
        append(rows(db, "SELECT id, date, weightKg, syncId FROM body_measurements ORDER BY id"))
        append(rows(db, "SELECT id, checkInTime, gymName, syncId FROM check_ins ORDER BY id"))
        append(rows(db, "SELECT id, name, canonicalId, isUserCreated, syncId FROM exercises ORDER BY id"))
        append(rows(db, "SELECT effectiveFromWeekStartEpochDay, goal FROM weekly_goal_history ORDER BY effectiveFromWeekStartEpochDay"))
        append(rows(db, "SELECT exerciseId, displayName, notes FROM exercise_user_overrides ORDER BY exerciseId"))
        append(rows(db, "SELECT id, ownerUid, state FROM cloud_data_binding ORDER BY id"))
        append(rows(db, "SELECT id, clientBackupId, status FROM backup_attempts ORDER BY id"))
    }

    private fun seedVersion32(): String {
        val db = helper.createDatabase(testDb, 32)

        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated, canonicalId, slug, syncId) " +
                "VALUES (1, 'Supino Reto', 1, 0, 0, 3, 0, 1, 'canonical.supino', 'supino-reto', NULL)"
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

    private fun attemptInsert(restoreAttemptId: String): String =
        "INSERT INTO restore_attempts (restoreAttemptId, backupId, ownerUid, payloadHash, " +
            "backupSchemaVersion, backupCreatedAt, datasetWasUnbound, status, createdAt, updatedAt) " +
            "VALUES ('$restoreAttemptId', 'backup-1', 'uid-A', 'hash', 1, 100, 1, 'VALIDATED', 100, 100)"

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
