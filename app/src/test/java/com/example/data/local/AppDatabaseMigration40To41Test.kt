package com.example.data.local

import android.os.Build
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A migração 40 → 41 (T19.8): `workout_templates.dayOfWeek` (um rótulo, um dia) vira
 * `workout_template_schedules` (0..N dias canônicos), e a coluna some.
 *
 * Invariantes protegidos:
 * 1. Template com dia reconhecível (`Seg`, `MONDAY`, `sábado`) vira **uma** linha de agenda
 *    canônica; `NULL`, vazio e texto que não é dia viram **zero** linhas — sem dia inventado;
 * 2. Nada mais do template muda: `id`, `programId`, nome, sigla, `orderInProgram` e `syncId`
 *    sobrevivem à recriação da tabela com os mesmos valores;
 * 3. Os filhos sobrevivem: exercícios do treino (FK cascade) e sessões (`templateId`) continuam
 *    apontando para o mesmo `id`;
 * 4. Um dia repetido no mesmo treino é impossível (chave primária composta);
 * 5. Apagar o treino leva a agenda junto (cascade); a chave estrangeira fecha;
 * 6. `AUTOINCREMENT` continua do maior `id`: um treino novo não colide com os existentes;
 * 7. Uma instalação limpa na versão 41 tem a mesma forma que o banco migrado.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppDatabaseMigration40To41Test {

    private val testDb = "migration-40-41-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migration40To41_convertsLegacyDay_keepsTemplatesAndChildren_andEnforcesUniqueDays() {
        seedVersion40()

        val db = helper.runMigrationsAndValidate(testDb, 41, true, AppDatabase.MIGRATION_40_41)
        db.execSQL("PRAGMA foreign_keys = ON")

        // 1. Rótulo legado → dia canônico; sem dia → sem linha.
        val schedules = mutableMapOf<Long, List<String>>()
        db.query("SELECT templateId, dayOfWeek FROM workout_template_schedules ORDER BY templateId, dayOfWeek").use {
            while (it.moveToNext()) {
                schedules[it.getLong(0)] = schedules.getOrDefault(it.getLong(0), emptyList()) + it.getString(1)
            }
        }
        assertEquals(listOf("MONDAY"), schedules[10])
        assertEquals("template sem dia migra para zero linhas", null, schedules[11])
        assertEquals("nome canônico já gravado por um JSON importado", listOf("MONDAY"), schedules[12])
        assertEquals("rótulo com acento e caixa diferente", listOf("SATURDAY"), schedules[13])
        assertEquals("texto que não é dia não vira dia", null, schedules[14])
        assertEquals("vazio é sem dia", null, schedules[15])

        // 2. O cabeçalho sobrevive à recriação, com os mesmos valores — e sem a coluna antiga.
        db.query("SELECT id, programId, name, shortIdentifier, orderInProgram, syncId FROM workout_templates ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals(10L, it.getLong(0))
            assertEquals(1L, it.getLong(1))
            assertEquals("Peito", it.getString(2))
            assertEquals("A", it.getString(3))
            assertEquals(0, it.getInt(4))
            assertEquals("ffffffff-0000-4000-8000-000000000010", it.getString(5))
            assertTrue(it.moveToNext())
            assertEquals(11L, it.getLong(0))
            assertEquals(null, it.getString(3))
            assertEquals(1, it.getInt(4))
        }
        db.query("PRAGMA table_info(workout_templates)").use {
            val columns = mutableListOf<String>()
            while (it.moveToNext()) columns += it.getString(it.getColumnIndexOrThrow("name"))
            assertFalse("a coluna dayOfWeek deixa de existir", "dayOfWeek" in columns)
        }
        db.query("SELECT COUNT(*) FROM workout_templates").use {
            assertTrue(it.moveToFirst())
            assertEquals(6, it.getInt(0))
        }

        // 3. Filhos: exercícios do treino e sessões continuam apontando para o mesmo treino.
        db.query("SELECT templateId, exerciseId, sortOrder FROM workout_template_exercises ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals(10L, it.getLong(0))
            assertEquals(1L, it.getLong(1))
            assertEquals(0, it.getInt(2))
            assertTrue(it.moveToNext())
            assertEquals(11L, it.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM workout_template_exercises").use {
            assertTrue(it.moveToFirst())
            assertEquals("o DROP da tabela antiga não cascateia nos exercícios", 2, it.getInt(0))
        }
        db.query("SELECT id, templateId, status FROM workout_sessions ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals(100L, it.getLong(0))
            assertEquals(10L, it.getLong(1))
            assertEquals("COMPLETED", it.getString(2))
        }
        db.query("PRAGMA foreign_key_check").use {
            assertFalse("nenhuma chave estrangeira pendurada depois da migração", it.moveToFirst())
        }

        // 4. Dia repetido é impossível; um segundo dia no mesmo treino é normal.
        val duplicateDay = runCatching {
            db.execSQL("INSERT INTO workout_template_schedules (templateId, dayOfWeek) VALUES (10, 'MONDAY')")
        }
        assertFalse("o mesmo dia duas vezes no mesmo treino é recusado pelo banco", duplicateDay.isSuccess)
        db.execSQL("INSERT INTO workout_template_schedules (templateId, dayOfWeek) VALUES (10, 'THURSDAY')")
        db.query("SELECT dayOfWeek FROM workout_template_schedules WHERE templateId = 10 ORDER BY dayOfWeek").use {
            val days = mutableListOf<String>()
            while (it.moveToNext()) days += it.getString(0)
            assertEquals(listOf("MONDAY", "THURSDAY"), days)
        }
        val orphanSchedule = runCatching {
            db.execSQL("INSERT INTO workout_template_schedules (templateId, dayOfWeek) VALUES (999, 'MONDAY')")
        }
        assertFalse("agenda sem treino é recusada pela chave estrangeira", orphanSchedule.isSuccess)

        // 5. Cascade: apagar o treino leva a agenda (e os exercícios) junto.
        db.execSQL("DELETE FROM workout_templates WHERE id = 10")
        db.query("SELECT COUNT(*) FROM workout_template_schedules WHERE templateId = 10").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM workout_template_exercises WHERE templateId = 10").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }

        // 6. AUTOINCREMENT continua do maior id conhecido.
        db.execSQL(
            "INSERT INTO workout_templates (programId, name, shortIdentifier, orderInProgram, syncId) " +
                "VALUES (1, 'Novo', 'N', 6, 'ffffffff-0000-4000-8000-000000000099')"
        )
        db.query("SELECT id FROM workout_templates WHERE name = 'Novo'").use {
            assertTrue(it.moveToFirst())
            assertTrue("id novo acima dos existentes", it.getLong(0) > 15L)
        }
        db.close()
    }

    @Test
    fun cleanInstallAtVersion41_hasTheSameShapeAsTheMigratedDatabase() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.workoutDao()
            val programId = dao.insertProgram(WorkoutProgramEntity(name = "P"))
            val templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "A"))
            dao.replaceSchedulesForTemplate(templateId, listOf("MONDAY", "THURSDAY"))
            // O mesmo dia de novo: no-op, nunca uma segunda linha.
            dao.insertSchedules(listOf(WorkoutTemplateScheduleEntity(templateId, "MONDAY")))

            val loaded = dao.getTemplatesWithScheduleForProgramSync(programId).single()
            assertEquals(listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY), loaded.scheduledDays)
            assertEquals(2, dao.getSchedulesForTemplate(templateId).size)

            dao.deleteTemplate(loaded.template)
            assertEquals(0, dao.getSchedulesForTemplate(templateId).size)
        } finally {
            database.close()
        }
    }

    private fun seedVersion40() {
        val db = helper.createDatabase(testDb, 40)

        db.execSQL(
            "INSERT INTO exercises (id, name, active, rirEnabled, isBodyweight, contentVersion, " +
                "isUserCreated, isCurated, canonicalId, slug, syncId) " +
                "VALUES (1, 'Supino Reto', 1, 0, 0, 3, 0, 1, 'canonical.supino', 'supino-reto', NULL)"
        )
        db.execSQL(
            "INSERT INTO workout_programs (id, name, description, isCurrent, externalId, contentVersion, syncId) " +
                "VALUES (1, 'ABC', 'programa', 1, NULL, 0, 'ffffffff-0000-4000-8000-000000000010')"
        )
        fun template(id: Long, name: String, shortId: String?, order: Int, day: String?) {
            db.execSQL(
                "INSERT INTO workout_templates (id, programId, name, shortIdentifier, orderInProgram, dayOfWeek, syncId) " +
                    "VALUES (?, 1, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(id, name, shortId, order, day, "ffffffff-0000-4000-8000-0000000000$id")
            )
        }
        template(10, "Peito", "A", 0, "Seg")
        template(11, "Costas", null, 1, null)
        template(12, "Pernas", "C", 2, "MONDAY")
        template(13, "Ombro", "D", 3, " sábado ")
        template(14, "Braço", "E", 4, "quando der")
        template(15, "Core", "F", 5, "")
        db.execSQL(
            "INSERT INTO workout_template_exercises (id, templateId, exerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds) " +
                "VALUES (1, 10, 1, 0, 3, 8, 12, 90), (2, 11, 1, 0, 4, 6, 10, 120)"
        )
        db.execSQL(
            "INSERT INTO workout_sessions (id, templateId, startedAt, finishedAt, status, notes, templateNameSnapshot, syncId, executionMode) " +
                "VALUES (100, 10, 1000, 2000, 'COMPLETED', NULL, 'Peito', 'ffffffff-0000-4000-8000-000000000030', 'SOLO')"
        )
        db.close()
    }
}
