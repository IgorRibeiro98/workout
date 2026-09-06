package com.example.data.local

import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A migração 30 → 31 (T16.3) não pode custar nada ao usuário.
 *
 * Ela prepara um banco de versão 30 com dado realista — programa, treinos fora de ordem alfabética,
 * exercícios do treino em ordem não trivial, uma sessão **concluída** com séries executadas,
 * medidas, check-in, catálogo canônico e exercício criado pelo usuário — migra, e exige que o
 * conteúdo continue idêntico.
 *
 * O que este teste protege, e que nenhuma inspeção de código prova: que ganhar identidade global
 * não apaga histórico, não reescreve série antiga, não perde relação e não embaralha ordem.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration30To31Test {

    private val testDb = "migration-30-31-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration30To31_preservesEveryRowRelationAndOrder_andBackfillsSyncId() {
        seedVersion30()

        val db = helper.runMigrationsAndValidate(testDb, 31, true, AppDatabase.MIGRATION_30_31)

        // ---- Nada sumiu -----------------------------------------------------------------
        assertEquals(1, count(db, "workout_programs"))
        assertEquals(2, count(db, "workout_templates"))
        assertEquals(4, count(db, "workout_template_exercises"))
        assertEquals(2, count(db, "workout_sessions"))
        assertEquals(2, count(db, "exercise_sessions"))
        assertEquals(5, count(db, "set_logs"))
        assertEquals(2, count(db, "body_measurements"))
        assertEquals(1, count(db, "check_ins"))
        assertEquals(3, count(db, "exercises"))
        assertEquals(1, count(db, "personal_records"))
        assertEquals(1, count(db, "gamification_events"))
        assertEquals(1, count(db, "xp_transactions"))

        // ---- syncId preenchido e único --------------------------------------------------
        listOf(
            "workout_programs",
            "workout_templates",
            "workout_sessions",
            "body_measurements",
            "check_ins"
        ).forEach { table ->
            assertEquals(
                "$table deveria ter syncId em toda linha",
                0,
                count(db, table, "syncId IS NULL OR TRIM(syncId) = ''")
            )
            val rows = count(db, table)
            val distinct = queryInt(db, "SELECT COUNT(DISTINCT syncId) FROM `$table`")
            assertEquals("$table tem syncId repetido", rows, distinct)
        }

        // ---- Identidade canônica preservada, sem identidade paralela --------------------
        //
        // Exercício de catálogo continua identificado por `canonicalId` e **não** ganha syncId.
        assertEquals(
            2,
            count(db, "exercises", "canonicalId IS NOT NULL AND isUserCreated = 0 AND syncId IS NULL")
        )
        assertEquals(1, count(db, "exercises", "isUserCreated = 1 AND syncId IS NOT NULL"))
        queryString(db, "SELECT canonicalId FROM exercises WHERE id = 1").let {
            assertEquals("canonical.supino", it)
        }

        // ---- Relações e IDs locais intactos ---------------------------------------------
        assertEquals(1L, queryLong(db, "SELECT programId FROM workout_templates WHERE id = 10"))
        assertEquals(10L, queryLong(db, "SELECT templateId FROM workout_sessions WHERE id = 100"))
        assertEquals(
            4,
            count(db, "workout_template_exercises", "templateId IN (10, 11)")
        )
        assertEquals(100L, queryLong(db, "SELECT sessionId FROM exercise_sessions WHERE id = 1000"))
        assertEquals(100L, queryLong(db, "SELECT sessionId FROM check_ins WHERE id = 500"))

        // ---- Ordem preservada, exercício a exercício ------------------------------------
        //
        // O treino 10 foi semeado com sortOrder 30, 10, 20 de propósito: ordem é dado de domínio,
        // não consequência do id da linha.
        assertEquals(
            listOf(10 to 3L, 20 to 2L, 30 to 1L),
            queryPairs(
                db,
                "SELECT sortOrder, exerciseId FROM workout_template_exercises " +
                    "WHERE templateId = 10 ORDER BY sortOrder ASC"
            )
        )
        assertEquals(
            listOf(1 to 1L, 2 to 2L),
            queryPairs(
                db,
                "SELECT executionOrder, plannedExerciseId FROM exercise_sessions " +
                    "WHERE sessionId = 100 ORDER BY executionOrder ASC"
            )
        )

        // ---- Histórico concluído idêntico ------------------------------------------------
        assertEquals("COMPLETED", queryString(db, "SELECT status FROM workout_sessions WHERE id = 100"))
        assertEquals(2_000L, queryLong(db, "SELECT finishedAt FROM workout_sessions WHERE id = 100"))
        assertEquals("Treino bom", queryString(db, "SELECT notes FROM workout_sessions WHERE id = 100"))
        assertEquals("Supino Reto", queryString(db, "SELECT exerciseNameSnapshot FROM exercise_sessions WHERE id = 1000"))
        assertEquals(
            listOf("1|100.0|8|1", "2|100.0|7|1", "3|95.0|6|1"),
            querySetLogs(db, 1000)
        )
        assertEquals(listOf("1|60.0|12|1", "2|60.0|10|0"), querySetLogs(db, 1001))

        // ---- A Outbox nasce vazia --------------------------------------------------------
        assertEquals(0, count(db, "sync_outbox"))

        db.close()
    }

    @Test
    fun migration30To31_enforcesUniqueSyncId() {
        seedVersion30()
        val db = helper.runMigrationsAndValidate(testDb, 31, true, AppDatabase.MIGRATION_30_31)

        val existing = queryString(db, "SELECT syncId FROM workout_templates WHERE id = 10")
        assertNotNull(existing)

        // A unicidade é do banco, não da fé em "UUID nunca colide".
        val rejected = runCatching {
            db.execSQL(
                "INSERT INTO workout_templates (id, programId, name, shortIdentifier, orderInProgram, dayOfWeek, syncId) " +
                    "VALUES (99, 1, 'Clone', 'Z', 9, NULL, '$existing')"
            )
        }.isFailure
        assertTrue("duas entidades com o mesmo syncId deveriam ser recusadas pelo banco", rejected)
        assertEquals(2, count(db, "workout_templates"))

        db.close()
    }

    @Test
    fun migration30To31_leavesCanonicalCatalogWithoutSyncId() {
        seedVersion30()
        val db = helper.runMigrationsAndValidate(testDb, 31, true, AppDatabase.MIGRATION_30_31)

        assertNull(queryString(db, "SELECT syncId FROM exercises WHERE id = 1"))
        assertNull(queryString(db, "SELECT syncId FROM exercises WHERE id = 2"))
        val custom = queryString(db, "SELECT syncId FROM exercises WHERE id = 3")
        assertNotNull(custom)
        assertFalse(custom!!.isBlank())

        db.close()
    }

    /**
     * Um banco de versão 30 com dado realista.
     *
     * Ids explícitos para que as asserções possam falar de relações concretas em vez de "a
     * primeira linha".
     */
    private fun seedVersion30() {
        val db = helper.createDatabase(testDb, 30)

        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated, canonicalId, slug) " +
                "VALUES (1, 'Supino Reto', 1, 0, 0, 3, 0, 1, 'canonical.supino', 'supino-reto')"
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated, canonicalId, slug) " +
                "VALUES (2, 'Remada Curvada', 1, 0, 0, 3, 0, 1, 'canonical.remada', 'remada-curvada')"
        )
        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated) " +
                "VALUES (3, 'Rosca da academia do bairro', 1, 0, 0, 0, 1, 0)"
        )

        db.execSQL(
            "INSERT INTO workout_programs (id, name, description, isCurrent, externalId, contentVersion) " +
                "VALUES (1, 'ABCDE Hipertrofia', 'programa base', 1, NULL, 0)"
        )
        db.execSQL(
            "INSERT INTO workout_templates (id, programId, name, shortIdentifier, orderInProgram, dayOfWeek) " +
                "VALUES (10, 1, 'Peito + Costas', 'B', 1, 'MONDAY')"
        )
        db.execSQL(
            "INSERT INTO workout_templates (id, programId, name, shortIdentifier, orderInProgram, dayOfWeek) " +
                "VALUES (11, 1, 'Quadríceps', 'A', 0, NULL)"
        )

        // Ordem não trivial: sortOrder 30, 10, 20 inseridos fora de ordem.
        db.execSQL(
            "INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds, plannedWeight, machineLabel, notes) " +
                "VALUES (900, 10, 1, 30, 4, 6, 10, 120, 100.0, 'Banco 2', 'pegada média')"
        )
        db.execSQL(
            "INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds) " +
                "VALUES (901, 10, 3, 10, 3, 10, 15, 60)"
        )
        db.execSQL(
            "INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds) " +
                "VALUES (902, 10, 2, 20, 4, 8, 12, 90)"
        )
        db.execSQL(
            "INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds) " +
                "VALUES (903, 11, 1, 0, 3, 8, 12, 90)"
        )

        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot) " +
                "VALUES (100, 10, 1000, 2000, 'COMPLETED', 'Treino bom', 'Peito + Costas')"
        )
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot) " +
                "VALUES (101, 11, 5000, NULL, 'IN_PROGRESS', NULL, 'Quadríceps')"
        )

        db.execSQL(
            "INSERT INTO exercise_sessions (id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot, sortOrder, plannedOrder, executionOrder, restDurationSecondsSnapshot) " +
                "VALUES (1000, 100, 1, 1, 'Supino Reto', 1, 1, 1, 120)"
        )
        db.execSQL(
            "INSERT INTO exercise_sessions (id, sessionId, plannedExerciseId, actualExerciseId, exerciseNameSnapshot, sortOrder, plannedOrder, executionOrder, restDurationSecondsSnapshot) " +
                "VALUES (1001, 100, 2, 2, 'Remada Curvada', 2, 2, 2, 90)"
        )

        listOf(
            "(2000, 1000, 1, 'NORMAL', 100.0, 8, 1)",
            "(2001, 1000, 2, 'NORMAL', 100.0, 7, 1)",
            "(2002, 1000, 3, 'NORMAL', 95.0, 6, 1)",
            "(2003, 1001, 1, 'NORMAL', 60.0, 12, 1)",
            "(2004, 1001, 2, 'NORMAL', 60.0, 10, 0)"
        ).forEach {
            db.execSQL(
                "INSERT INTO set_logs (id, exerciseSessionId, setNumber, type, weight, repetitions, completed) VALUES $it"
            )
        }

        db.execSQL(
            "INSERT INTO check_ins (id, checkInTime, checkOutTime, gymName, sessionId) " +
                "VALUES (500, 900, 2100, 'Academia Central', 100)"
        )
        db.execSQL(
            "INSERT INTO body_measurements (id, date, createdAt, weightKg, waistCm) VALUES (700, 800, 810, 82.5, 84.0)"
        )
        db.execSQL(
            "INSERT INTO body_measurements (id, date, createdAt, weightKg) VALUES (701, 1800, 1810, 81.9)"
        )
        db.execSQL(
            "INSERT INTO personal_records (id, exerciseId, date, prType, value) VALUES (300, 1, 2000, 'MAX_WEIGHT', 100.0)"
        )
        db.execSQL(
            "INSERT INTO gamification_events (id, type, timestamp, source, dedupeKey, metadataJson) " +
                "VALUES ('ev_1', 'WORKOUT_COMPLETED', 2000, 'ENGINE', 'dedupe_1', '{}')"
        )
        db.execSQL(
            "INSERT INTO xp_transactions (id, eventId, amount, reason, createdAt) VALUES ('xp_1', 'ev_1', 100, 'WORKOUT_COMPLETED', 2000)"
        )

        db.close()
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, where: String? = null): Int =
        queryInt(db, "SELECT COUNT(*) FROM `$table`" + (where?.let { " WHERE $it" } ?: ""))

    private fun queryInt(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { it.moveToFirst(); it.getInt(0) }

    private fun queryLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { it.moveToFirst(); it.getLong(0) }

    private fun queryString(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { if (!it.moveToFirst() || it.isNull(0)) null else it.getString(0) }

    private fun queryPairs(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): List<Pair<Int, Long>> =
        db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getLong(1))
            }
        }

    private fun querySetLogs(db: androidx.sqlite.db.SupportSQLiteDatabase, exerciseSessionId: Long): List<String> =
        db.query(
            "SELECT setNumber, weight, repetitions, completed FROM set_logs " +
                "WHERE exerciseSessionId = $exerciseSessionId ORDER BY setNumber ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("${cursor.getInt(0)}|${cursor.getFloat(1)}|${cursor.getInt(2)}|${cursor.getInt(3)}")
                }
            }
        }
}
