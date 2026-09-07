package com.example.data.local

import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A migração 33 → 34 (T16.6) é aditiva e não custa nada ao usuário.
 *
 * Ela cria **três tabelas de mecanismo** — revision conhecida, cursor e conflitos — e acrescenta
 * **uma coluna anulável** à Outbox. Ganhar a capacidade de sincronizar não pode reescrever
 * histórico, perder identidade global, embaralhar ordem, dar dono a dado nenhum nem transformar
 * uma entrada pendente em outra coisa.
 *
 * O invariante mais importante para esta tarefa é o último teste: um banco migrado nasce **sem
 * revision conhecida, sem cursor e sem conflito**. Se a migração inventasse um cursor, o aparelho
 * abriria acreditando já ter lido mudanças que nunca viu — e pularia dados de verdade.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration33To34Test {

    private val testDb = "migration-33-34-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration33To34_addsSyncStateTables_andChangesNothingElse() {
        val before = seedVersion33()

        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        assertEquals("as tabelas novas nascem vazias", 0, count(db, "sync_entity_metadata"))
        assertEquals(0, count(db, "sync_cursor"))
        assertEquals(0, count(db, "sync_conflicts"))
        assertEquals("nada existente pode mudar", before, currentState(db))
    }

    @Test
    fun migration33To34_keepsPendingOutboxEntriesPending() {
        seedVersion33()

        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        db.query("SELECT status, blockedReason FROM sync_outbox WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // A coluna nova nasce nula: nenhuma entrada existente muda de significado.
            assertEquals("PENDING", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
    }

    @Test
    fun migration33To34_leavesNoCursorToResumeFrom() {
        seedVersion33()

        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        // Sem cursor: o primeiro pull deste aparelho começa do início do change log, que é a
        // única posição que ele pode provar. Um cursor inventado pularia mudanças de verdade.
        db.query("SELECT lastPulledServerSequence FROM sync_cursor").use { cursor ->
            assertEquals(0, cursor.count)
        }
        // E sem revision conhecida: o primeiro push de cada agregado nasce como criação, e é o
        // servidor quem decide se aquilo já existe.
        assertEquals(0, count(db, "sync_entity_metadata"))
    }

    @Test
    fun migration33To34_keepsSyncMetadataIdentityUnique() {
        seedVersion33()
        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        db.execSQL(metadataInsert(revision = 4))
        // Duas linhas para o mesmo agregado seriam duas opiniões sobre a mesma revision remota.
        db.execSQL(metadataInsert(revision = 5))

        db.query(
            "SELECT lastKnownServerRevision FROM sync_entity_metadata " +
                "WHERE ownerUid = 'uid-A' AND entityType = 'WORKOUT_TEMPLATE'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(5, cursor.getInt(0))
        }
    }

    @Test
    fun migration33To34_scopesCursorByAccount() {
        seedVersion33()
        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        db.execSQL("INSERT INTO sync_cursor (ownerUid, lastPulledServerSequence, lastSyncedAt) VALUES ('uid-A', 42, 1)")
        db.execSQL("INSERT INTO sync_cursor (ownerUid, lastPulledServerSequence, lastSyncedAt) VALUES ('uid-B', 7, 1)")

        // Um cursor descreve a posição em **um** change log, e o change log é por conta.
        db.query("SELECT lastPulledServerSequence FROM sync_cursor WHERE ownerUid = 'uid-A'").use {
            assertTrue(it.moveToFirst())
            assertEquals(42, it.getInt(0))
        }
        db.query("SELECT lastPulledServerSequence FROM sync_cursor WHERE ownerUid = 'uid-B'").use {
            assertTrue(it.moveToFirst())
            assertEquals(7, it.getInt(0))
        }
    }

    @Test
    fun migration33To34_conflictKeepsBothSidesWithoutResolvingAnything() {
        seedVersion33()
        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        db.execSQL(
            "INSERT INTO sync_conflicts (ownerUid, entityType, entitySyncId, kind, baseRevision, " +
                "localPayloadHash, remoteRevision, remoteServerSequence, remotePayloadHash, " +
                "remotePayload, clientMutationId, detectedAt) " +
                "VALUES ('uid-A', 'WORKOUT_TEMPLATE', 'ffffffff-0000-4000-8000-000000000020', " +
                "'STALE_LOCAL_CHANGE', 4, 'hash-local', 5, 120, 'hash-remoto', '{\"name\":\"remoto\"}', " +
                "'mutacao-1', 100)"
        )

        db.query("SELECT localPayloadHash, remoteRevision, remotePayload FROM sync_conflicts").use {
            assertTrue(it.moveToFirst())
            // Os dois lados ficam guardados. A escolha entre eles é da T16.7.
            assertEquals("hash-local", it.getString(0))
            assertEquals(5, it.getInt(1))
            assertTrue(it.getString(2).contains("remoto"))
        }

        // E o treino local continua exatamente como estava: conflito não altera domínio.
        db.query("SELECT name FROM workout_templates WHERE id = 10").use {
            assertTrue(it.moveToFirst())
            assertEquals("Peito", it.getString(0))
        }
    }

    @Test
    fun migration33To34_doesNotTouchCompletedHistory() {
        seedVersion33()
        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        db.query(
            "SELECT status, startedAt, finishedAt, notes, syncId FROM workout_sessions WHERE id = 100"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("COMPLETED", it.getString(0))
            assertEquals(1000L, it.getLong(1))
            assertEquals(2000L, it.getLong(2))
            assertEquals("Treino bom", it.getString(3))
            assertEquals("ffffffff-0000-4000-8000-000000000030", it.getString(4))
        }
        db.query("SELECT weight, repetitions, completed FROM set_logs WHERE id = 2000").use {
            assertTrue(it.moveToFirst())
            assertEquals(100.0, it.getDouble(0), 0.0001)
            assertEquals(8, it.getInt(1))
            assertEquals(1, it.getInt(2))
        }
    }

    @Test
    fun migration33To34_doesNotGiveOwnerToLocalData() {
        seedVersion33()
        val db = helper.runMigrationsAndValidate(testDb, 34, true, AppDatabase.MIGRATION_33_34)

        // Migrar não adota nada. Sem vínculo, o sync nem começa.
        assertEquals(0, count(db, "cloud_data_binding"))
        db.query("SELECT ownerUid FROM sync_entity_metadata LIMIT 1").use {
            assertEquals(0, it.count)
        }
        assertNull(firstOrNull(db, "SELECT ownerUid FROM sync_cursor LIMIT 1"))
    }

    private fun metadataInsert(revision: Int): String =
        "INSERT OR REPLACE INTO sync_entity_metadata (ownerUid, entityType, entitySyncId, " +
            "lastKnownServerRevision, lastSyncedPayloadHash, lastSyncedAt) " +
            "VALUES ('uid-A', 'WORKOUT_TEMPLATE', 'ffffffff-0000-4000-8000-000000000020', " +
            "$revision, 'hash', 100)"

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
        append(rows(db, "SELECT id, restoreAttemptId, status FROM restore_attempts ORDER BY id"))
        append(rows(db, "SELECT id, clientMutationId, ownerUid, entityType, entitySyncId, operation, status FROM sync_outbox ORDER BY id"))
    }

    private fun seedVersion33(): String {
        val db = helper.createDatabase(testDb, 33)

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
        // Uma entrada de Outbox pendente, para provar que a migração não a transforma em nada.
        db.execSQL(
            "INSERT INTO sync_outbox (id, clientMutationId, ownerUid, entityType, entitySyncId, " +
                "operation, status, createdAt, attemptCount, lastAttemptAt) " +
                "VALUES (1, 'mutacao-1', 'uid-A', 'WORKOUT_TEMPLATE', " +
                "'ffffffff-0000-4000-8000-000000000020', 'UPSERT', 'PENDING', 100, 0, NULL)"
        )

        val state = currentState(db)
        db.close()
        return state
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun firstOrNull(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { if (it.moveToFirst()) it.getString(0) else null }

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
