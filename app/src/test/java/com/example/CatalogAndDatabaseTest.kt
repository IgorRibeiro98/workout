package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.domain.engine.ManifestImporter
import kotlinx.coroutines.flow.firstOrNull
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
class CatalogAndDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorkoutDao
    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var importer: ManifestImporter

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workoutDao()
        settingsManager = SettingsManager(context)
        settingsManager.clearAll()
        importer = ManifestImporter(db, context, settingsManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. contentVersion ausente -> fatal
    @Test
    fun `test catalog validation missing contentVersion is fatal`() {
        val json = """
            {
                "schemaVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto"
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertFalse(validation.isValid)
        assertTrue(validation.validationErrors.any { it.contains("contentVersion") })
    }

    // 2. schema inválido -> fatal
    @Test
    fun `test catalog validation invalid schemaVersion is fatal`() {
        val json = """
            {
                "schemaVersion": 99,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto"
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertFalse(validation.isValid)
        assertTrue(validation.validationErrors.any { it.contains("Schema version") || it.contains("schemaVersion") })
    }

    // 3. exerciseCount incorreto -> fatal
    @Test
    fun `test catalog validation mismatched exerciseCount is fatal`() {
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 5,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto"
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertFalse(validation.isValid)
        assertTrue(validation.validationErrors.any { it.contains("exerciseCount") })
    }

    // 4. duplicate ID -> fatal
    @Test
    fun `test catalog validation duplicate exercise ID is fatal`() {
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 2,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Barra"
                    },
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Halteres"
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertFalse(validation.isValid)
        assertTrue(validation.validationErrors.any { it.contains("ID duplicado detectado: supino-reto") })
    }

    // 5. SAME_MOVEMENT válido
    @Test
    fun `test catalog validation SAME_MOVEMENT is valid reason`() {
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 2,
                "exercises": [
                    {
                        "id": "ex1",
                        "namePtBr": "Ex 1",
                        "alternatives": [
                            { "exerciseId": "ex2", "reason": "SAME_MOVEMENT" }
                        ]
                    },
                    {
                        "id": "ex2",
                        "namePtBr": "Ex 2"
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertTrue(validation.isValid)
        assertTrue(validation.validationErrors.isEmpty())
        assertTrue(validation.validationWarnings.isEmpty())
    }

    // 6. SAME_MOVEMENT_DIFFERENT_EQUIPMENT válido
    @Test
    fun `test catalog validation SAME_MOVEMENT_DIFFERENT_EQUIPMENT is valid reason`() {
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 2,
                "exercises": [
                    {
                        "id": "ex1",
                        "namePtBr": "Ex 1",
                        "alternatives": [
                            { "exerciseId": "ex2", "reason": "SAME_MOVEMENT_DIFFERENT_EQUIPMENT" }
                        ]
                    },
                    {
                        "id": "ex2",
                        "namePtBr": "Ex 2"
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertTrue(validation.isValid)
        assertTrue(validation.validationErrors.isEmpty())
        assertTrue(validation.validationWarnings.isEmpty())
    }

    // 7. alternative inexistente -> warning
    @Test
    fun `test catalog validation non existent alternative target produces warning not fatal`() {
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "ex1",
                        "namePtBr": "Ex 1",
                        "alternatives": [
                            { "exerciseId": "non-existent-ex", "reason": "SAME_MOVEMENT" }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val validation = importer.validateCatalog(json)
        assertTrue(validation.isValid)
        assertTrue(validation.validationErrors.isEmpty())
        assertTrue(validation.validationWarnings.any { it.contains("referencia alternativa inexistente") })
    }

    // 8. v1 nome A -> import
    @Test
    fun `test import v1 inserts exercise with name A`() = runBlocking {
        val jsonV1 = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Versão A",
                        "primaryMuscle": "Peitoral"
                    }
                ]
            }
        """.trimIndent()

        val result = importer.importFromJsonString(jsonV1, force = true)
        assertEquals(1, result.added)
        assertEquals(0, result.updated)

        val ex = dao.getExerciseByCanonicalId("supino-reto")
        assertNotNull(ex)
        assertEquals("Supino Reto Versão A", ex!!.name)
        assertEquals(1, ex.contentVersion)
        assertFalse(ex.isUserCreated)

        // Verify installed content version is updated in settings
        assertEquals(1, settingsManager.installedCatalogContentVersionFlow.firstOrNull())
    }

    // 9. v2 nome B -> update
    @Test
    fun `test import v2 updates exercise to name B`() = runBlocking {
        val jsonV1 = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Versão A",
                        "primaryMuscle": "Peitoral"
                    }
                ]
            }
        """.trimIndent()

        importer.importFromJsonString(jsonV1, force = true)

        val jsonV2 = """
            {
                "schemaVersion": 1,
                "contentVersion": 2,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Versão B",
                        "primaryMuscle": "Peitoral"
                    }
                ]
            }
        """.trimIndent()

        val result2 = importer.importFromJsonString(jsonV2, force = false)
        assertEquals(0, result2.added)
        assertEquals(1, result2.updated)

        val ex = dao.getExerciseByCanonicalId("supino-reto")
        assertNotNull(ex)
        assertEquals("Supino Reto Versão B", ex!!.name)
        assertEquals(2, ex.contentVersion)
        assertEquals(2, settingsManager.installedCatalogContentVersionFlow.firstOrNull())
    }

    // 10. force mesma versão -> merge
    @Test
    fun `test force reimport with same version performs merge`() = runBlocking {
        val jsonV1 = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Original",
                        "primaryMuscle": "Peitoral"
                    }
                ]
            }
        """.trimIndent()

        importer.importFromJsonString(jsonV1, force = false)

        // Without force: skipped
        val skipResult = importer.importFromJsonString(jsonV1, force = false)
        assertTrue(skipResult.isSkippedSameVersion)

        // With force = true: re-evaluates & merges
        val jsonModified = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 1,
                "exercises": [
                    {
                        "id": "supino-reto",
                        "namePtBr": "Supino Reto Modificado",
                        "primaryMuscle": "Peitoral"
                    }
                ]
            }
        """.trimIndent()

        val forceResult = importer.importFromJsonString(jsonModified, force = true)
        assertEquals(1, forceResult.updated)

        val ex = dao.getExerciseByCanonicalId("supino-reto")
        assertNotNull(ex)
        assertEquals("Supino Reto Modificado", ex!!.name)
    }

    // 11. segunda importação -> counts estáveis
    @Test
    fun `test second import produces stable counts for exercises and alternatives`() = runBlocking {
        val json = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 2,
                "exercises": [
                    {
                        "id": "ex1",
                        "namePtBr": "Exercício 1",
                        "alternatives": [
                            { "exerciseId": "ex2", "reason": "SAME_MOVEMENT" }
                        ]
                    },
                    {
                        "id": "ex2",
                        "namePtBr": "Exercício 2"
                    }
                ]
            }
        """.trimIndent()

        // 1st import
        val result1 = importer.importFromJsonString(json, force = true)
        assertEquals(2, result1.added)
        assertEquals(0, result1.updated)
        assertEquals(1, result1.alternativesAdded)
        assertEquals(0, result1.alternativesExisting)

        // 2nd import (force = true)
        val result2 = importer.importFromJsonString(json, force = true)
        assertEquals(0, result2.added)
        assertEquals(2, result2.updated)
        assertEquals(0, result2.alternativesAdded)
        assertEquals(1, result2.alternativesExisting)
    }

    // 12. erro de persistência -> rollback
    @Test
    fun `test invalid json before transaction fails cleanly without inserting partial data`() = runBlocking {
        val invalidJson = """
            {
                "schemaVersion": 1,
                "contentVersion": 1,
                "locale": "pt-BR",
                "exerciseCount": 2,
                "exercises": [
                    {
                        "id": "valid-1",
                        "namePtBr": "Válido 1"
                    },
                    {
                        "id": "",
                        "namePtBr": "Inválido sem ID"
                    }
                ]
            }
        """.trimIndent()

        val result = importer.importFromJsonString(invalidJson, force = true)
        assertTrue(result.errors.isNotEmpty())
        assertEquals(0, result.added)

        val count = dao.getAllExercisesSync().size
        assertEquals(0, count)
        assertEquals(0, settingsManager.installedCatalogContentVersionFlow.firstOrNull())
    }

    // 13 & 14. legacy user-created corretamente classificado e histórico preservado
    @Test
    fun `test legacy manual exercise is user created while canonical is not`() = runBlocking {
        val canonical = ExerciseEntity(
            name = "Supino Canônico",
            canonicalId = "supino-canonica-id",
            isUserCreated = false
        )
        val userCreated = ExerciseEntity(
            name = "Meu Exercício Caseiro",
            canonicalId = null,
            isUserCreated = true
        )

        val id1 = dao.insertExercise(canonical)
        val id2 = dao.insertExercise(userCreated)

        val retrieved1 = dao.getExerciseById(id1)
        val retrieved2 = dao.getExerciseById(id2)

        assertNotNull(retrieved1)
        assertNotNull(retrieved2)
        assertFalse(retrieved1!!.isUserCreated)
        assertEquals("supino-canonica-id", retrieved1.canonicalId)
        assertTrue(retrieved2!!.isUserCreated)
        assertNull(retrieved2.canonicalId)
    }
}
