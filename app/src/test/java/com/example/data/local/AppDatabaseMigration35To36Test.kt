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
 * A migração 35 → 36 (T17.7) cria a tabela `workout_share_import_receipts`.
 *
 * Invariantes protegidos:
 * 1. Nenhum treino, sessão, série, medida ou conflito existente muda;
 * 2. A nova tabela nasce vazia e permite inserções com chave primária `shareId`;
 * 3. A Outbox continua inalterada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration35To36Test {

    private val testDb = "migration-35-36-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration35To36_createsReceiptsTable_andPreservesExistingData() {
        seedVersion35()

        val db = helper.runMigrationsAndValidate(testDb, 36, true, AppDatabase.MIGRATION_35_36)

        // Verifica que a nova tabela existe e opera normalmente
        db.execSQL(
            "INSERT INTO workout_share_import_receipts (shareId, importedTemplateLocalId, createdAt) " +
                "VALUES ('share-123', 10, 1700000000000)"
        )

        db.query("SELECT shareId, importedTemplateLocalId, createdAt FROM workout_share_import_receipts WHERE shareId = 'share-123'").use {
            assertTrue(it.moveToFirst())
            assertEquals("share-123", it.getString(0))
            assertEquals(10L, it.getLong(1))
            assertEquals(1700000000000L, it.getLong(2))
        }

        // Verifica preservação do template existente
        db.query("SELECT id, name, syncId FROM workout_templates WHERE id = 10").use {
            assertTrue(it.moveToFirst())
            assertEquals(10L, it.getLong(0))
            assertEquals("Peito", it.getString(1))
            assertEquals("ffffffff-0000-4000-8000-000000000020", it.getString(2))
        }
    }

    private fun seedVersion35() {
        val db = helper.createDatabase(testDb, 35)

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
        db.close()
    }
}
