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
class ExampleRobolectricTest {

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
                "formatVersion": 1,
                "locale": "pt-BR",
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
        assertTrue(result.localePtBr)
    }

    @Test
    fun `test transactional import of exercises and alternatives`() = runBlocking {
        val importer = ManifestImporter(db, context)
        val json = """
            [
                {
                    "exercise": {
                        "id": "supino-reto-barra",
                        "identity": { "namePtBr": "Supino Reto com Barra" },
                        "muscles": { "primary": [{ "namePtBr": "Peitoral" }] },
                        "alternatives": [
                            { "exerciseId": "supino-reto-halteres", "reason": "SAME_MUSCLE" },
                            { "exerciseId": "supino-reto-barra", "reason": "SELF" }
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
        """.trimIndent()

        val result = importer.importFromJsonString(json)
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
    fun `test program importer transaction`() = runBlocking {
        val exId = dao.insertExercise(
            ExerciseEntity(
                name = "Agachamento Livre",
                canonicalId = "agachamento-livre-barra",
                primaryMuscle = "Quadríceps"
            )
        )

        val pImporter = ProgramImporter(db, context)
        val programJson = """
            {
                "program": {
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

        val result = pImporter.importProgramFromJson(programJson)
        assertTrue(result.success)
        assertEquals(1, result.workoutsCount)
        assertEquals(1, result.exercisesCount)
        assertEquals(0, result.missingExercises)

        val programs = dao.getAllProgramsSync()
        assertEquals(1, programs.size)
        assertEquals("Hipertrofia Pernas", programs[0].name)
    }
}

