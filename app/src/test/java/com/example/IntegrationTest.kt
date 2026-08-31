package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.domain.engine.ManifestImporter
import com.example.domain.engine.ProgramImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workoutDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Treino", appName)
    }

    @Test
    fun `test catalog validation with valid manifest`() {
        val importer = ManifestImporter(db, context)
        val validJson = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "exercise": {
                            "id": "supino-reto-barra",
                            "identity": { "namePtBr": "Supino Reto com Barra" },
                            "muscles": { "primary": [{ "namePtBr": "Peitoral" }] },
                            "equipment": { "required": [{ "namePtBr": "Barra" }] }
                        }
                    }
                ]
            }
        """.trimIndent()

        val result = importer.validateCatalog(validJson)
        assertTrue(result.isValid)
        assertEquals(1, result.exerciseCount)
        assertEquals(1, result.contentVersion)
        assertTrue(result.localePtBr)
        assertTrue(result.validationErrors.isEmpty())
    }

    @Test
    fun `test catalog validation fails when required field is missing or invalid`() {
        val importer = ManifestImporter(db, context)
        val invalidJson = """
            {
                "schemaVersion": 1,
                "locale": "en-US",
                "exerciseCount": 5,
                "exercises": []
            }
        """.trimIndent()

        val result = importer.validateCatalog(invalidJson)
        assertFalse(result.isValid)
        assertTrue(result.validationErrors.any { it.contains("contentVersion") })
        assertTrue(result.validationErrors.any { it.contains("Locale incompatível") })
        assertTrue(result.validationErrors.any { it.contains("exerciseCount") })
    }

    @Test
    fun `test transactional import of exercises and alternatives`() = runBlocking {
        val importer = ManifestImporter(db, context)
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 2,
                "exercises": [
                    {
                        "exercise": {
                            "id": "supino-reto-barra",
                            "identity": { "namePtBr": "Supino Reto com Barra" },
                            "muscles": { "primary": [{ "namePtBr": "Peitoral" }] },
                            "alternatives": [
                                { "exerciseId": "supino-reto-halteres", "reason": "SAME_MUSCLE" },
                                { "exerciseId": "supino-reto-barra", "reason": "SAME_MUSCLE" }
                            ]
                        }
                    },
                    {
                        "exercise": {
                            "id": "supino-reto-halteres",
                            "identity": { "namePtBr": "Supino Reto com Halteres" },
                            "muscles": { "primary": [{ "namePtBr": "Peitoral" }] }
                        }
                    }
                ]
            }
        """.trimIndent()

        val result = importer.importFromJsonString(json, force = true)
        assertEquals(2, result.added)
        assertEquals(1, result.alternativesAdded)

        val ex1 = dao.getExerciseByCanonicalId("supino-reto-barra")
        val ex2 = dao.getExerciseByCanonicalId("supino-reto-halteres")
        assertNotNull(ex1)
        assertNotNull(ex2)

        val alts = dao.getExplicitAlternatives(ex1!!.id)
        assertEquals(1, alts.size)
        assertEquals(ex2!!.id, alts[0].id)
    }

    @Test
    fun `test program importer idempotent with externalId and contentVersion`() = runBlocking {
        dao.insertExercise(
            ExerciseEntity(
                name = "Agachamento Livre",
                canonicalId = "agachamento-livre-barra",
                primaryMuscle = "Quadríceps"
            )
        )

        val pImporter = ProgramImporter(db, context)
        val programJsonV1 = """
            {
                "program": {
                    "id": "prog-pernas-1",
                    "contentVersion": 1,
                    "name": "Hipertrofia Pernas",
                    "workouts": [
                        {
                            "name": "Treino A",
                            "shortCode": "A",
                            "exercises": [
                                {
                                    "exerciseId": "agachamento-livre-barra",
                                    "sets": 4,
                                    "minReps": 8,
                                    "maxReps": 10,
                                    "restSeconds": 120
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        // 1st Import: fresh
        val result1 = pImporter.importProgramFromJson(programJsonV1)
        assertTrue(result1.success)
        assertFalse(result1.isSkippedSameVersion)
        assertEquals(1, result1.workoutsCount)
        assertEquals(1, result1.exercisesCount)

        var programs = dao.getAllProgramsSync()
        assertEquals(1, programs.size)
        assertEquals("Hipertrofia Pernas", programs[0].name)
        assertEquals("prog-pernas-1", programs[0].externalId)
        assertEquals(1, programs[0].contentVersion)

        // 2nd Import: same version -> idempotent skip
        val result2 = pImporter.importProgramFromJson(programJsonV1)
        assertTrue(result2.success)
        assertTrue(result2.isSkippedSameVersion)

        // Still exactly 1 program
        programs = dao.getAllProgramsSync()
        assertEquals(1, programs.size)

        // 3rd Import: version 2 -> updates program
        val programJsonV2 = """
            {
                "program": {
                    "id": "prog-pernas-1",
                    "contentVersion": 2,
                    "name": "Hipertrofia Pernas Avançado",
                    "workouts": [
                        {
                            "name": "Treino A",
                            "shortCode": "A",
                            "exercises": [
                                {
                                    "exerciseId": "agachamento-livre-barra",
                                    "sets": 5,
                                    "minReps": 6,
                                    "maxReps": 8,
                                    "restSeconds": 150
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        val result3 = pImporter.importProgramFromJson(programJsonV2)
        assertTrue(result3.success)
        assertFalse(result3.isSkippedSameVersion)

        programs = dao.getAllProgramsSync()
        assertEquals(1, programs.size)
        assertEquals("Hipertrofia Pernas Avançado", programs[0].name)
        assertEquals(2, programs[0].contentVersion)
    }

    @Test
    fun `test program import without externalId always creates new program and never dedupes by name`() = runBlocking {
        dao.insertExercise(
            ExerciseEntity(
                name = "Supino Reto",
                canonicalId = "supino-reto",
                primaryMuscle = "Peitoral"
            )
        )

        val pImporter = ProgramImporter(db, context)
        val jsonNoId = """
            {
                "program": {
                    "name": "Treino de Peito",
                    "workouts": [
                        {
                            "name": "Peito 1",
                            "shortCode": "A",
                            "exercises": [
                                { "exerciseId": "supino-reto", "sets": 3, "minReps": 10, "maxReps": 12 }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        val r1 = pImporter.importProgramFromJson(jsonNoId)
        assertTrue(r1.success)

        val r2 = pImporter.importProgramFromJson(jsonNoId)
        assertTrue(r2.success)

        val programs = dao.getAllProgramsSync()
        assertEquals(2, programs.size)
        assertEquals("Treino de Peito", programs[0].name)
        assertEquals("Treino de Peito", programs[1].name)
        assertNull(programs[0].externalId)
        assertNull(programs[1].externalId)
        assertNotEquals(programs[0].id, programs[1].id)
    }

    @Test
    fun `test historical WorkoutSessions remain intact when program is updated`() = runBlocking {
        dao.insertExercise(ExerciseEntity(name = "Agachamento", canonicalId = "agachamento"))
        val pImporter = ProgramImporter(db, context)
        val jsonV1 = """
            {
                "program": {
                    "id": "prog-hist-test",
                    "contentVersion": 1,
                    "name": "Programa Original",
                    "workouts": [
                        {
                            "name": "Treino A",
                            "shortCode": "A",
                            "exercises": [{ "exerciseId": "agachamento", "sets": 3 }]
                        }
                    ]
                }
            }
        """.trimIndent()
        pImporter.importProgramFromJson(jsonV1)

        val template = dao.getAllTemplatesSync().first()
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = template.id,
                startedAt = 1000L,
                finishedAt = 2000L,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino A Antigo"
            )
        )
        dao.insertExerciseSession(
            ExerciseSessionEntity(
                sessionId = sessionId,
                plannedExerciseId = null,
                actualExerciseId = null,
                exerciseNameSnapshot = "Agachamento Histórico",
                sortOrder = 0
            )
        )

        // Now update program to v2
        val jsonV2 = """
            {
                "program": {
                    "id": "prog-hist-test",
                    "contentVersion": 2,
                    "name": "Programa Atualizado",
                    "workouts": [
                        {
                            "name": "Treino A Novo",
                            "shortCode": "A",
                            "exercises": [{ "exerciseId": "agachamento", "sets": 4 }]
                        }
                    ]
                }
            }
        """.trimIndent()
        val updateRes = pImporter.importProgramFromJson(jsonV2)
        assertTrue(updateRes.success)

        // Historical session is completely untouched
        val histSession = dao.getSessionById(sessionId)
        assertNotNull(histSession)
        assertEquals("Treino A Antigo", histSession!!.templateNameSnapshot)
        assertEquals(SessionStatus.COMPLETED.name, histSession.status)

        val histExSessions = dao.getExerciseSessionsForSession(sessionId)
        assertEquals(1, histExSessions.size)
        assertEquals("Agachamento Histórico", histExSessions[0].exerciseNameSnapshot)
    }

    @Test
    fun `test program importer rollback on validation or transaction error`() = runBlocking {
        val pImporter = ProgramImporter(db, context)
        val invalidJson = """
            {
                "program": {
                    "id": "prog-fail",
                    "name": "Programa Vazio Sem Treinos",
                    "workouts": []
                }
            }
        """.trimIndent()

        val res = pImporter.importProgramFromJson(invalidJson)
        assertFalse(res.success)

        val programs = dao.getAllProgramsSync()
        assertTrue(programs.none { it.externalId == "prog-fail" })
    }

    @Test
    fun `test MainActivity launches successfully without crashing`() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
    }
}
