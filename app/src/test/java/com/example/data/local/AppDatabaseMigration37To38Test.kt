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
 * A migração 37 → 38 (T19.3) recria `workout_share_import_receipts` para que um recibo possa
 * apontar para um treino **ou** para um programa.
 *
 * Invariantes protegidos:
 * 1. Os recibos de treino existentes (T17.7) sobrevivem inteiros, com `importedProgramLocalId`
 *    nulo;
 * 2. A tabela aceita um recibo de programa, sem treino;
 * 3. `shareId` continua sendo a chave primária — o mesmo share não ganha dois recibos;
 * 4. Nenhum treino, programa, sessão ou linha da Outbox muda.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration37To38Test {

    private val testDb = "migration-37-38-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration37To38_preservesTemplateReceipts_andAcceptsProgramReceipts() {
        seedVersion37()

        val db = helper.runMigrationsAndValidate(testDb, 38, true, AppDatabase.MIGRATION_37_38)

        // 1. O recibo de treino da T17.7 continua lá, intacto.
        db.query(
            "SELECT shareId, importedTemplateLocalId, importedProgramLocalId, createdAt " +
                "FROM workout_share_import_receipts WHERE shareId = 'share-template'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("share-template", it.getString(0))
            assertEquals(10L, it.getLong(1))
            assertTrue("um recibo antigo é de treino, nunca de programa", it.isNull(2))
            assertEquals(1700000000000L, it.getLong(3))
        }

        // 2. Um recibo de programa entra sem treino.
        db.execSQL(
            "INSERT INTO workout_share_import_receipts (shareId, importedTemplateLocalId, importedProgramLocalId, createdAt) " +
                "VALUES ('share-program', NULL, 1, 1700000001000)"
        )
        db.query(
            "SELECT importedTemplateLocalId, importedProgramLocalId FROM workout_share_import_receipts WHERE shareId = 'share-program'"
        ).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals(1L, it.getLong(1))
        }

        // 3. A chave primária sobreviveu à recriação.
        val duplicate = runCatching {
            db.execSQL(
                "INSERT INTO workout_share_import_receipts (shareId, importedTemplateLocalId, importedProgramLocalId, createdAt) " +
                    "VALUES ('share-program', NULL, 2, 1700000002000)"
            )
        }
        assertFalse("o mesmo shareId não pode ganhar dois recibos", duplicate.isSuccess)

        // 4. Treino, programa e sessão continuam como estavam.
        db.query("SELECT id, name, syncId FROM workout_templates WHERE id = 10").use {
            assertTrue(it.moveToFirst())
            assertEquals("Peito", it.getString(1))
            assertEquals("ffffffff-0000-4000-8000-000000000020", it.getString(2))
        }
        db.query("SELECT isCurrent, syncId FROM workout_programs WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals("ffffffff-0000-4000-8000-000000000010", it.getString(1))
        }
        db.query("SELECT status, notes FROM workout_sessions WHERE id = 100").use {
            assertTrue(it.moveToFirst())
            assertEquals("COMPLETED", it.getString(0))
            assertEquals("Treino bom", it.getString(1))
        }
        db.query("SELECT COUNT(*) FROM sync_outbox").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    private fun seedVersion37() {
        val db = helper.createDatabase(testDb, 37)

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
            "INSERT INTO workout_share_import_receipts (shareId, importedTemplateLocalId, createdAt) " +
                "VALUES ('share-template', 10, 1700000000000)"
        )
        db.close()
    }
}
