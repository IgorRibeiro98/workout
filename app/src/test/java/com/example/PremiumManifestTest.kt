package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.domain.engine.PremiumManifestImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class PremiumManifestTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context
    private lateinit var importer: PremiumManifestImporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = androidx.room.Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java
        ).allowMainThreadQueries().build()
        importer = PremiumManifestImporter(database, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test premium manifest import and retrieval`() = runBlocking {
        // 1. Import
        val result = importer.importFromAssets("catalog/exercise-content-manifest.v1.json")
        assertTrue("No errors should occur during import: ${result.errors}", result.errors.isEmpty())
        assertTrue("Should import at least 1 exercise", result.added > 0 || result.updated > 0)

        // 2. Fetch
        val dao = database.workoutDao()
        val exercise = dao.getExerciseByCanonicalId("supino-reto-barra")
        assertNotNull("Exercise should exist", exercise)
        
        // 3. Query premium data
        val exId = exercise!!.id
        val education = dao.getExerciseEducation(exId)
        val execution = dao.getExerciseExecution(exId)
        val progression = dao.getExerciseProgression(exId)
        val safety = dao.getExerciseSafety(exId)
        val substitutions = dao.getExerciseSubstitutionPremium(exId)

        assertNotNull("Education data should exist", education)
        assertNotNull("Execution data should exist", execution)
        assertNotNull("Progression data should exist", progression)
        assertNotNull("Safety data should exist", safety)
        assertNotNull("Substitution data should exist", substitutions)

        assertEquals("Médio", safety?.riskLevel)
        assertEquals("8-12", progression?.repRange)
        
        // 4. Assert UI fields wouldn't crash (can just check properties)
        assertTrue(execution!!.setup?.isNotEmpty() == true)
        assertTrue(education!!.tips?.isNotEmpty() == true)
    }
}
