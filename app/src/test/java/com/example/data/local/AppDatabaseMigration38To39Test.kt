package com.example.data.local

import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A migração 38 → 39 (T19.4) acrescenta `workout_sessions.executionMode` e as duas tabelas da
 * dupla local.
 *
 * Invariantes protegidos:
 * 1. Toda sessão anterior — em andamento ou concluída — passa a ser `SOLO`, sem tocar em mais nada;
 * 2. `set_logs`, `exercise_sessions` e a Outbox não mudam;
 * 3. Uma sessão nova pode ser `DUO_LOCAL` com dois participantes e o espelho do convidado;
 * 4. Um participante não pode ocupar a mesma posição duas vezes, nem o convidado ter duas linhas
 *    para a mesma série;
 * 5. Apagar a sessão leva participantes e espelho junto (cascade).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration38To39Test {

    private val testDb = "migration-38-39-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration38To39_oldSessionsBecomeSolo_andDuoTablesWork() {
        seedVersion38()

        val db = helper.runMigrationsAndValidate(testDb, 39, true, AppDatabase.MIGRATION_38_39)
        db.execSQL("PRAGMA foreign_keys = ON")

        // 1. As sessões antigas são SOLO, com o resto intacto.
        db.query("SELECT id, status, executionMode, syncId, templateNameSnapshot FROM workout_sessions ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals(100L, it.getLong(0))
            assertEquals("COMPLETED", it.getString(1))
            assertEquals("SOLO", it.getString(2))
            assertEquals("ffffffff-0000-4000-8000-000000000030", it.getString(3))
            assertEquals("Peito", it.getString(4))
            assertTrue(it.moveToNext())
            assertEquals(101L, it.getLong(0))
            assertEquals("IN_PROGRESS", it.getString(1))
            assertEquals("SOLO", it.getString(2))
        }

        // 2. Séries e exercícios da sessão antiga não mudam; a Outbox continua vazia.
        db.query("SELECT setNumber, weight, completed FROM set_logs WHERE exerciseSessionId = 1000 ORDER BY setNumber").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(60f, it.getFloat(1), 0.01f)
            assertEquals(1, it.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM sync_outbox").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM workout_session_participants").use {
            assertTrue(it.moveToFirst())
            assertEquals("sessão antiga não ganha participante", 0, it.getInt(0))
        }

        // 3. Uma sessão DUO_LOCAL nova, com os dois participantes e o espelho.
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId, executionMode) " +
                "VALUES (102, 10, 3000, NULL, 'IN_PROGRESS', NULL, 'Peito', 'ffffffff-0000-4000-8000-000000000032', 'DUO_LOCAL')"
        )
        db.execSQL(
            "INSERT INTO exercise_sessions (id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot, sortOrder, plannedOrder, executionOrder) " +
                "VALUES (1002, 102, 1, 1, 'Supino Reto', 1, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO workout_session_participants (id, sessionId, role, displayName, position, restEndsAt) " +
                "VALUES (1, 102, 'OWNER', NULL, 0, NULL), (2, 102, 'GUEST', 'João', 1, 4000)"
        )
        db.execSQL(
            "INSERT INTO workout_guest_set_logs (id, participantId, exerciseSessionId, setNumber, weight, repetitions, completed, finishedAt, rir, durationSeconds) " +
                "VALUES (1, 2, 1002, 1, 40.0, 10, 1, 3500, NULL, NULL), (2, 2, 1002, 2, 40.0, 10, 0, NULL, NULL, NULL)"
        )
        db.query("SELECT role, displayName, position, restEndsAt FROM workout_session_participants WHERE sessionId = 102 ORDER BY position").use {
            assertTrue(it.moveToFirst())
            assertEquals("OWNER", it.getString(0))
            assertTrue(it.isNull(1))
            assertTrue(it.moveToNext())
            assertEquals("GUEST", it.getString(0))
            assertEquals("João", it.getString(1))
            assertEquals(4000L, it.getLong(3))
        }

        // 4. Restrições de unicidade.
        val duplicatePosition = runCatching {
            db.execSQL(
                "INSERT INTO workout_session_participants (sessionId, role, displayName, position) VALUES (102, 'GUEST', 'Outro', 1)"
            )
        }
        assertFalse("duas pessoas na mesma posição da mesma sessão", duplicatePosition.isSuccess)
        val duplicateSet = runCatching {
            db.execSQL(
                "INSERT INTO workout_guest_set_logs (participantId, exerciseSessionId, setNumber, weight, repetitions, completed) VALUES (2, 1002, 1, 0, 0, 0)"
            )
        }
        assertFalse("o convidado não tem duas linhas para a mesma série", duplicateSet.isSuccess)

        // 5. Cascade: a sessão some, participantes e espelho somem junto.
        db.execSQL("DELETE FROM workout_sessions WHERE id = 102")
        db.query("SELECT COUNT(*) FROM workout_session_participants").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM workout_guest_set_logs").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    private fun seedVersion38() {
        val db = helper.createDatabase(testDb, 38)

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
                "VALUES (10, 1, 'Peito', 'A', 0, 'Seg', 'ffffffff-0000-4000-8000-000000000020')"
        )
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId) " +
                "VALUES (100, 10, 1000, 2000, 'COMPLETED', 'Treino bom', 'Peito', 'ffffffff-0000-4000-8000-000000000030')"
        )
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId) " +
                "VALUES (101, 10, 2500, NULL, 'IN_PROGRESS', NULL, 'Peito', 'ffffffff-0000-4000-8000-000000000031')"
        )
        db.execSQL(
            "INSERT INTO exercise_sessions (id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot, sortOrder, plannedOrder, executionOrder) " +
                "VALUES (1000, 100, 1, 1, 'Supino Reto', 1, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO set_logs (id, exerciseSessionId, setNumber, type, weight, repetitions, completed) " +
                "VALUES (5000, 1000, 1, 'NORMAL', 60.0, 10, 1)"
        )
        db.close()
    }
}
