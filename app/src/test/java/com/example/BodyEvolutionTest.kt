package com.example

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.presentation.body.BodyEvolutionViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = MainApplication::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class BodyEvolutionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BodyMeasurementRepository
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BodyMeasurementRepository(db.bodyMeasurementDao())
    }

    @After
    fun closeDb() {
        db.close()
    }

    /**
     * Scenario 1: User with no measurements -> Empty state
     */
    @Test
    fun testScenario1_EmptyState() = runBlocking {
        val all = repository.allMeasurements.first()
        val latest = repository.latestMeasurement.first()

        assertTrue("Measurements list should be empty initially", all.isEmpty())
        assertNull("Latest measurement should be null initially", latest)
    }

    /**
     * Scenario 2: Add weight only -> Successfully saved and displayed
     */
    @Test
    fun testScenario2_AddWeightOnly() = runBlocking {
        val testDate = 1756598400000L // 30/08/2026
        val measurement = BodyMeasurementEntity(
            date = testDate,
            weightKg = 88.4f
        )

        val id = repository.insertMeasurement(measurement)
        assertTrue("Inserted ID should be positive", id > 0)

        val all = repository.allMeasurements.first()
        assertEquals(1, all.size)

        val saved = all.first()
        assertEquals(testDate, saved.date)
        assertEquals(88.4f, saved.weightKg ?: 0f, 0.01f)
        assertNull(saved.waistCm)
        assertNull(saved.rightArmCm)
        assertNull(saved.bodyFatPercentage)

        val latest = repository.latestMeasurement.first()
        assertNotNull(latest)
        assertEquals(88.4f, latest?.weightKg ?: 0f, 0.01f)
    }

    /**
     * Scenario 3: Add all measurements -> All fields properly persisted and retrieved
     */
    @Test
    fun testScenario3_AddAllMeasurements() = runBlocking {
        val testDate = 1756598400000L
        val fullMeasurement = BodyMeasurementEntity(
            date = testDate,
            weightKg = 88.4f,
            heightCm = 180.0f,
            bodyFatPercentage = 14.5f,
            waistCm = 91.0f,
            abdomenCm = 94.0f,
            chestCm = 105.0f,
            rightArmCm = 38.0f,
            leftArmCm = 37.5f,
            rightThighCm = 60.0f,
            leftThighCm = 59.5f,
            calfCm = 39.0f,
            hipCm = 101.0f
        )

        val id = repository.insertMeasurement(fullMeasurement)
        val retrieved = repository.getMeasurementById(id)

        assertNotNull("Retrieved measurement should not be null", retrieved)
        assertEquals(testDate, retrieved?.date)
        assertEquals(88.4f, retrieved?.weightKg ?: 0f, 0.01f)
        assertEquals(180.0f, retrieved?.heightCm ?: 0f, 0.01f)
        assertEquals(14.5f, retrieved?.bodyFatPercentage ?: 0f, 0.01f)
        assertEquals(91.0f, retrieved?.waistCm ?: 0f, 0.01f)
        assertEquals(94.0f, retrieved?.abdomenCm ?: 0f, 0.01f)
        assertEquals(105.0f, retrieved?.chestCm ?: 0f, 0.01f)
        assertEquals(38.0f, retrieved?.rightArmCm ?: 0f, 0.01f)
        assertEquals(37.5f, retrieved?.leftArmCm ?: 0f, 0.01f)
        assertEquals(60.0f, retrieved?.rightThighCm ?: 0f, 0.01f)
        assertEquals(59.5f, retrieved?.leftThighCm ?: 0f, 0.01f)
        assertEquals(39.0f, retrieved?.calfCm ?: 0f, 0.01f)
        assertEquals(101.0f, retrieved?.hipCm ?: 0f, 0.01f)
    }

    /**
     * Scenario 4: Invalid values validation -> Rejects invalid values and prevents save
     */
    @Test
    fun testScenario4_InvalidValuesValidation() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)

        // Case 4a: All empty fields
        viewModel.resetForm()
        val savedEmpty = viewModel.saveMeasurement()
        assertFalse("Should not save empty measurement", savedEmpty)
        assertNotNull("General error should be present for empty fields", viewModel.formState.value.errors["general"])

        // Case 4b: Invalid weight > 500
        viewModel.resetForm()
        viewModel.updateWeight("550")
        val savedTooHeavy = viewModel.saveMeasurement()
        assertFalse("Should not save weight > 500", savedTooHeavy)
        assertNotNull("Weight error should be present", viewModel.formState.value.errors["weight"])

        // Case 4c: Invalid measure > 300
        viewModel.resetForm()
        viewModel.updateWaist("350")
        val savedTooBig = viewModel.saveMeasurement()
        assertFalse("Should not save waist > 300", savedTooBig)
        assertNotNull("Waist error should be present", viewModel.formState.value.errors["waist"])

        // Case 4d: Invalid body fat > 100
        viewModel.resetForm()
        viewModel.updateBodyFat("105")
        val savedFatTooHigh = viewModel.saveMeasurement()
        assertFalse("Should not save body fat > 100", savedFatTooHigh)
        assertNotNull("Body fat error should be present", viewModel.formState.value.errors["bodyFat"])

        // Case 4e: Negative / zero values
        viewModel.resetForm()
        viewModel.updateWeight("-10")
        val savedNegative = viewModel.saveMeasurement()
        assertFalse("Should not save negative weight", savedNegative)

        // Ensure database remained clean
        val all = repository.allMeasurements.first()
        assertEquals(0, all.size)
    }

    /**
     * Scenario 5: Persistence across application re-opens (Room database persistence)
     */
    @Test
    fun testScenario5_PersistenceAndOrdering() = runBlocking {
        val measurement1 = BodyMeasurementEntity(
            date = 1000L,
            weightKg = 92.0f
        )
        val measurement2 = BodyMeasurementEntity(
            date = 2000L,
            weightKg = 90.1f
        )
        val measurement3 = BodyMeasurementEntity(
            date = 3000L,
            weightKg = 88.4f,
            waistCm = 91.0f
        )

        repository.insertMeasurement(measurement1)
        repository.insertMeasurement(measurement2)
        repository.insertMeasurement(measurement3)

        // Verify ordering: DESC by date (latest first)
        val all = repository.allMeasurements.first()
        assertEquals(3, all.size)
        assertEquals(3000L, all[0].date)
        assertEquals(88.4f, all[0].weightKg ?: 0f, 0.01f)
        assertEquals(2000L, all[1].date)
        assertEquals(90.1f, all[1].weightKg ?: 0f, 0.01f)
        assertEquals(1000L, all[2].date)
        assertEquals(92.0f, all[2].weightKg ?: 0f, 0.01f)

        // Verify latest measurement
        val latest = repository.latestMeasurement.first()
        assertEquals(3000L, latest?.date)
        assertEquals(88.4f, latest?.weightKg ?: 0f, 0.01f)
        assertEquals(91.0f, latest?.waistCm ?: 0f, 0.01f)

        // Test deletion
        repository.deleteMeasurementById(all[0].id)
        val afterDelete = repository.allMeasurements.first()
        assertEquals(2, afterDelete.size)
        assertEquals(2000L, afterDelete[0].date)
    }
}
