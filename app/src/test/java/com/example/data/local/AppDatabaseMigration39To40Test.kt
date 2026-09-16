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
 * A migração 39 → 40 (T19.5) acrescenta só `workout_session_multiplayer_links`.
 *
 * Invariantes protegidos:
 * 1. Sessões, séries, participantes da dupla local e a Outbox não mudam — inclusive o modo;
 * 2. Uma sessão nova pode ser `DUO_REMOTE` sem coluna nova em `workout_sessions`;
 * 3. Uma sessão tem no máximo um vínculo, e uma sala tem no máximo um vínculo por conta;
 * 4. Apagar a sessão leva o vínculo junto (cascade) — nunca o contrário.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration39To40Test {

    private val testDb = "migration-39-40-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration39To40_keepsSessions_andLinksAreScopedByRoomAndAccount() {
        seedVersion39()

        val db = helper.runMigrationsAndValidate(testDb, 40, true, AppDatabase.MIGRATION_39_40)
        db.execSQL("PRAGMA foreign_keys = ON")

        // 1. Nada da versão anterior muda.
        db.query("SELECT id, status, executionMode FROM workout_sessions ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals(100L, it.getLong(0))
            assertEquals("COMPLETED", it.getString(1))
            assertEquals("SOLO", it.getString(2))
            assertTrue(it.moveToNext())
            assertEquals(101L, it.getLong(0))
            assertEquals("DUO_LOCAL", it.getString(2))
        }
        db.query("SELECT COUNT(*) FROM workout_session_participants").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM workout_session_multiplayer_links").use {
            assertTrue(it.moveToFirst())
            assertEquals("sessão antiga não ganha vínculo", 0, it.getInt(0))
        }

        // 2. Uma sessão DUO_REMOTE nova, com o vínculo.
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId, executionMode) " +
                "VALUES (102, 10, 3000, NULL, 'IN_PROGRESS', NULL, 'Peito', 'ffffffff-0000-4000-8000-000000000032', 'DUO_REMOTE')"
        )
        db.execSQL(
            "INSERT INTO workout_session_multiplayer_links (sessionId, roomId, accountUid, role, peerDisplayName, createdAt, finishedNotifiedAt) " +
                "VALUES (102, 'room-1', 'uid-a', 'HOST', 'João', 3000, NULL)"
        )
        db.query("SELECT roomId, accountUid, role, peerDisplayName FROM workout_session_multiplayer_links WHERE sessionId = 102").use {
            assertTrue(it.moveToFirst())
            assertEquals("room-1", it.getString(0))
            assertEquals("uid-a", it.getString(1))
            assertEquals("HOST", it.getString(2))
            assertEquals("João", it.getString(3))
        }

        // 3. Unicidade: uma sessão, um vínculo; uma sala por conta, uma sessão.
        val secondLinkSameSession = runCatching {
            db.execSQL(
                "INSERT INTO workout_session_multiplayer_links (sessionId, roomId, accountUid, role, createdAt) VALUES (102, 'room-2', 'uid-a', 'HOST', 3001)"
            )
        }
        assertFalse("uma sessão não pertence a duas salas", secondLinkSameSession.isSuccess)
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId, executionMode) " +
                "VALUES (103, 10, 3100, NULL, 'CANCELLED', NULL, 'Peito', 'ffffffff-0000-4000-8000-000000000033', 'DUO_REMOTE')"
        )
        val sameRoomSameAccount = runCatching {
            db.execSQL(
                "INSERT INTO workout_session_multiplayer_links (sessionId, roomId, accountUid, role, createdAt) VALUES (103, 'room-1', 'uid-a', 'HOST', 3100)"
            )
        }
        assertFalse("a mesma sala, na mesma conta, não vira duas sessões", sameRoomSameAccount.isSuccess)
        // Outra conta pode ter a própria sessão para a mesma sala (é o que dois aparelhos fazem).
        db.execSQL(
            "INSERT INTO workout_session_multiplayer_links (sessionId, roomId, accountUid, role, createdAt) VALUES (103, 'room-1', 'uid-b', 'GUEST', 3100)"
        )

        // 4. Cascade: a sessão some, o vínculo some junto.
        db.execSQL("DELETE FROM workout_sessions WHERE id = 102")
        db.query("SELECT COUNT(*) FROM workout_session_multiplayer_links").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    private fun seedVersion39() {
        val db = helper.createDatabase(testDb, 39)

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
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId, executionMode) " +
                "VALUES (100, 10, 1000, 2000, 'COMPLETED', 'Treino bom', 'Peito', 'ffffffff-0000-4000-8000-000000000030', 'SOLO')"
        )
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId, executionMode) " +
                "VALUES (101, 10, 2500, NULL, 'IN_PROGRESS', NULL, 'Peito', 'ffffffff-0000-4000-8000-000000000031', 'DUO_LOCAL')"
        )
        db.execSQL(
            "INSERT INTO workout_session_participants (id, sessionId, role, displayName, position, restEndsAt) " +
                "VALUES (1, 101, 'OWNER', NULL, 0, NULL), (2, 101, 'GUEST', 'João', 1, NULL)"
        )
        db.close()
    }
}
