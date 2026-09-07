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
 * A migração 34 → 35 (T16.7) acrescenta **uma coluna** a uma tabela de mecanismo.
 *
 * O tombstone do Spark mora no servidor: localmente, apagar continua sendo apagar, com o cascade de
 * sempre. O que muda aqui é só a memória de uma decisão do usuário sobre um conflito —
 * `sync_conflicts.status` — para que ela sobreviva ao processo morrer entre o toque e o envio.
 *
 * Os invariantes que este teste protege:
 *
 * 1. nenhum treino, sessão, série, medida ou identidade global muda;
 * 2. todo conflito que já existia continua **pendente**: a migração não decide nada por ninguém;
 * 3. a Outbox continua exatamente como estava — nem uma entrada bloqueada volta para a fila, nem
 *    uma pendente é confirmada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration34To35Test {

    private val testDb = "migration-34-35-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration34To35_addsConflictStatus_andChangesNothingElse() {
        val before = seedVersion34()

        val db = helper.runMigrationsAndValidate(testDb, 35, true, AppDatabase.MIGRATION_34_35)

        assertEquals("nada existente pode mudar", before, currentState(db))
    }

    @Test
    fun migration34To35_leavesEveryExistingConflictPending() {
        seedVersion34()

        val db = helper.runMigrationsAndValidate(testDb, 35, true, AppDatabase.MIGRATION_34_35)

        // Um conflito migrado descreve exatamente o que descrevia: algo que ninguém resolveu. Se a
        // migração o marcasse como decidido, o usuário perderia a tela que precisa ver — e a
        // alteração dele ficaria esperando um envio que nunca foi pedido.
        db.query("SELECT status, remotePayload FROM sync_conflicts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
            assertTrue("os dois lados continuam guardados", cursor.getString(1).contains("remoto"))
        }
    }

    @Test
    fun migration34To35_keepsOutboxExactlyAsItWas() {
        seedVersion34()

        val db = helper.runMigrationsAndValidate(testDb, 35, true, AppDatabase.MIGRATION_34_35)

        db.query("SELECT status, blockedReason FROM sync_outbox WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("PENDING", it.getString(0))
            assertTrue(it.isNull(1))
        }
        db.query("SELECT status, blockedReason FROM sync_outbox WHERE id = 2").use {
            assertTrue(it.moveToFirst())
            // Uma alteração recusada não volta para a fila por causa de uma migração: reenviá-la
            // produziria a mesma recusa, e agora ela tem uma tela onde ser decidida.
            assertEquals("BLOCKED", it.getString(0))
            assertEquals("STALE", it.getString(1))
        }
    }

    @Test
    fun migration34To35_doesNotTouchCompletedHistory() {
        seedVersion34()

        val db = helper.runMigrationsAndValidate(testDb, 35, true, AppDatabase.MIGRATION_34_35)

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
    fun migration34To35_keepsCursorAndKnownRevisions() {
        seedVersion34()

        val db = helper.runMigrationsAndValidate(testDb, 35, true, AppDatabase.MIGRATION_34_35)

        // Zerar qualquer um dos dois faria o aparelho reprocessar a conta inteira ou tratar a
        // primeira edição depois da atualização como conflito falso.
        db.query("SELECT lastPulledServerSequence FROM sync_cursor WHERE ownerUid = 'uid-A'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1842, it.getInt(0))
        }
        db.query(
            "SELECT lastKnownServerRevision FROM sync_entity_metadata WHERE ownerUid = 'uid-A'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(4, it.getInt(0))
        }
    }

    private fun currentState(db: androidx.sqlite.db.SupportSQLiteDatabase): String = buildString {
        append(rows(db, "SELECT id, name, syncId, isCurrent FROM workout_programs ORDER BY id"))
        append(rows(db, "SELECT id, programId, name, orderInProgram, syncId FROM workout_templates ORDER BY id"))
        append(rows(db, "SELECT id, status, startedAt, finishedAt, notes, syncId FROM workout_sessions ORDER BY id"))
        append(rows(db, "SELECT id, exerciseSessionId, setNumber, weight, repetitions, completed FROM set_logs ORDER BY id"))
        append(rows(db, "SELECT id, date, weightKg, syncId FROM body_measurements ORDER BY id"))
        append(rows(db, "SELECT id, name, canonicalId, isUserCreated, syncId FROM exercises ORDER BY id"))
        append(rows(db, "SELECT id, clientMutationId, ownerUid, entityType, entitySyncId, operation, status, blockedReason FROM sync_outbox ORDER BY id"))
        append(rows(db, "SELECT ownerUid, entityType, entitySyncId, lastKnownServerRevision FROM sync_entity_metadata ORDER BY entitySyncId"))
        append(rows(db, "SELECT ownerUid, lastPulledServerSequence FROM sync_cursor ORDER BY ownerUid"))
        append(rows(db, "SELECT ownerUid, entityType, entitySyncId, kind, baseRevision, remoteRevision, remotePayload FROM sync_conflicts ORDER BY entitySyncId"))
    }

    private fun seedVersion34(): String {
        val db = helper.createDatabase(testDb, 34)

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
        // Uma pendente e uma bloqueada: a migração não pode mexer em nenhuma das duas.
        db.execSQL(
            "INSERT INTO sync_outbox (id, clientMutationId, ownerUid, entityType, entitySyncId, " +
                "operation, status, createdAt, attemptCount, lastAttemptAt, blockedReason) " +
                "VALUES (1, 'mutacao-1', 'uid-A', 'BODY_MEASUREMENT', " +
                "'ffffffff-0000-4000-8000-000000000040', 'UPSERT', 'PENDING', 100, 0, NULL, NULL)"
        )
        db.execSQL(
            "INSERT INTO sync_outbox (id, clientMutationId, ownerUid, entityType, entitySyncId, " +
                "operation, status, createdAt, attemptCount, lastAttemptAt, blockedReason) " +
                "VALUES (2, 'mutacao-2', 'uid-A', 'WORKOUT_TEMPLATE', " +
                "'ffffffff-0000-4000-8000-000000000020', 'UPSERT', 'BLOCKED', 100, 1, 200, 'STALE')"
        )
        db.execSQL(
            "INSERT INTO sync_entity_metadata (ownerUid, entityType, entitySyncId, " +
                "lastKnownServerRevision, lastSyncedPayloadHash, lastSyncedAt) " +
                "VALUES ('uid-A', 'WORKOUT_TEMPLATE', 'ffffffff-0000-4000-8000-000000000020', 4, 'hash', 100)"
        )
        db.execSQL(
            "INSERT INTO sync_cursor (ownerUid, lastPulledServerSequence, lastSyncedAt) " +
                "VALUES ('uid-A', 1842, 100)"
        )
        db.execSQL(
            "INSERT INTO sync_conflicts (ownerUid, entityType, entitySyncId, kind, baseRevision, " +
                "localPayloadHash, remoteRevision, remoteServerSequence, remotePayloadHash, " +
                "remotePayload, clientMutationId, detectedAt) " +
                "VALUES ('uid-A', 'WORKOUT_TEMPLATE', 'ffffffff-0000-4000-8000-000000000020', " +
                "'STALE_LOCAL_CHANGE', 4, 'hash-local', 5, 120, 'hash-remoto', '{\"name\":\"remoto\"}', " +
                "'mutacao-2', 100)"
        )

        val state = currentState(db)
        db.close()
        return state
    }

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
