package com.example

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.body.BodyMetricsCalculator
import com.example.domain.engine.WorkoutEngine
import com.example.presentation.MainViewModelFactory
import com.example.presentation.body.BodyEvolutionViewModel
import com.example.service.WorkoutNotificationManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
     * Requirement 1: ViewModel Factory Architecture
     * BodyEvolutionViewModel must receive BodyMeasurementRepository directly.
     * Throw explicit IllegalStateException if repository is missing and never fallback to casting another DAO.
     */
    @Test
    fun testViewModelFactory_ExplicitExceptionWhenRepoMissing() {
        val app = context.applicationContext as MainApplication
        val factoryWithoutRepo = MainViewModelFactory(
            repository = app.repository,
            settingsManager = app.settingsManager,
            workoutEngine = app.workoutEngine,
            notificationManager = app.notificationManager,
            bodyMeasurementRepository = null
        )

        assertThrows(IllegalStateException::class.java) {
            factoryWithoutRepo.create(BodyEvolutionViewModel::class.java)
        }

        val factoryWithRepo = MainViewModelFactory(
            repository = app.repository,
            settingsManager = app.settingsManager,
            workoutEngine = app.workoutEngine,
            notificationManager = app.notificationManager,
            bodyMeasurementRepository = repository
        )
        val vm = factoryWithRepo.create(BodyEvolutionViewModel::class.java)
        assertNotNull(vm)
    }

    /**
     * Teste 1 (Mandatory Test 1):
     * Cadastrar: Peso: 88.4, Cintura: 91 -> Salvar com sucesso
     */
    @Test
    fun testMandatory1_SaveWeightAndWaistSuccess() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)
        viewModel.initNewMeasurement()
        viewModel.updateWeight("88.4")
        viewModel.updateWaist("91")

        val saved = viewModel.saveMeasurementSuspending()
        assertTrue("Should save valid weight and waist successfully", saved)

        val all = repository.allMeasurements.first()
        assertEquals(1, all.size)
        val firstItem = all.first()
        assertEquals(88.4f, firstItem.weightKg ?: 0f, 0.01f)
        assertEquals(91.0f, firstItem.waistCm ?: 0f, 0.01f)
    }

    /**
     * Teste 2 (Mandatory Test 2):
     * Cadastrar: Peso: -5 -> Bloquear salvamento
     */
    @Test
    fun testMandatory2_BlockNegativeWeight() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)
        viewModel.initNewMeasurement()
        viewModel.updateWeight("-5")

        val saved = viewModel.saveMeasurementSuspending()
        assertFalse("Should block negative weight", saved)
        assertNotNull("Should contain weight error message", viewModel.formState.value.errors["weight"])

        val all = repository.allMeasurements.first()
        assertTrue("Database should remain empty after invalid entry", all.isEmpty())
    }

    /**
     * Teste 3 (Mandatory Test 3):
     * Editar registro existente -> Dados atualizados
     */
    @Test
    fun testMandatory3_EditExistingMeasurement() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)

        // 1. Initial creation
        val initialDate = 1756598400000L
        val initial = BodyMeasurementEntity(
            date = initialDate,
            weightKg = 88.4f,
            waistCm = 91.0f
        )
        val id = repository.insertMeasurement(initial)

        val createdEntity = repository.getMeasurementById(id)
        assertNotNull(createdEntity)

        // 2. Load for edit
        viewModel.loadForEdit(createdEntity!!)
        assertTrue("Form should be in edit mode", viewModel.formState.value.isEditMode)
        assertEquals(id, viewModel.formState.value.editingMeasurementId)
        assertEquals("88.4", viewModel.formState.value.weightKg)
        assertEquals("91", viewModel.formState.value.waistCm)

        // 3. Update values (new weight: 86.3, new waist: 88.5)
        viewModel.updateWeight("86.3")
        viewModel.updateWaist("88.5")

        val updated = viewModel.saveMeasurementSuspending()
        assertTrue("Should save update successfully", updated)

        // 4. Verify in repository
        val afterUpdate = repository.getMeasurementById(id)
        assertNotNull(afterUpdate)
        assertEquals(86.3f, afterUpdate?.weightKg ?: 0f, 0.01f)
        assertEquals(88.5f, afterUpdate?.waistCm ?: 0f, 0.01f)

        val all = repository.allMeasurements.first()
        assertEquals(1, all.size)
    }

    /**
     * Teste 4 (Mandatory Test 4):
     * Informar: Peso: 88.4, Altura: 171 -> Mostrar IMC: 30.2, Obesidade
     */
    @Test
    fun testMandatory4_BmiCalculation() {
        val result = BodyMetricsCalculator.calculateBmi(weightKg = 88.4f, heightCm = 171f)
        assertNotNull("BMI result should not be null when weight and height exist", result)
        assertEquals(30.2f, result?.bmi ?: 0f, 0.01f)
        assertEquals("30.2", result?.formattedBmi)
        assertEquals("Obesidade", result?.classification)
        assertTrue(result?.isObese == true)
    }

    /**
     * Teste 5 (Mandatory Test 5):
     * Sem altura -> Não mostrar IMC
     */
    @Test
    fun testMandatory5_NoHeightNoBmi() {
        val resultWithoutHeight = BodyMetricsCalculator.calculateBmi(weightKg = 88.4f, heightCm = null)
        assertNull("BMI should be null if height is not provided", resultWithoutHeight)

        val resultWithoutWeight = BodyMetricsCalculator.calculateBmi(weightKg = null, heightCm = 171f)
        assertNull("BMI should be null if weight is not provided", resultWithoutWeight)
    }

    /**
     * Additional Validations Coverage:
     * - Height bounds: 50 <= height <= 300
     * - Measure bounds: 0 < measure <= 300
     * - Body Fat bounds: 0 < fat < 100
     */
    @Test
    fun testAdditionalFieldValidations() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)

        // Height too small (< 50)
        viewModel.initNewMeasurement()
        viewModel.updateHeight("40")
        assertFalse(viewModel.saveMeasurementSuspending())
        assertNotNull(viewModel.formState.value.errors["height"])

        // Height too large (> 300)
        viewModel.initNewMeasurement()
        viewModel.updateHeight("350")
        assertFalse(viewModel.saveMeasurementSuspending())
        assertNotNull(viewModel.formState.value.errors["height"])

        // Body fat >= 100
        viewModel.initNewMeasurement()
        viewModel.updateBodyFat("100")
        assertFalse(viewModel.saveMeasurementSuspending())
        assertNotNull(viewModel.formState.value.errors["bodyFat"])

        // Measure > 300
        viewModel.initNewMeasurement()
        viewModel.updateChest("310")
        assertFalse(viewModel.saveMeasurementSuspending())
        assertNotNull(viewModel.formState.value.errors["chest"])
    }

    /**
     * BMI Category classifications coverage
     */
    @Test
    fun testBmiClassifications() {
        // Underweight (< 18.5)
        val under = BodyMetricsCalculator.calculateBmi(50f, 175f)
        assertEquals("Baixo peso", under?.classification)
        assertTrue(under?.isUnderweight == true)

        // Normal (18.5 - 24.9)
        val normal = BodyMetricsCalculator.calculateBmi(70f, 175f)
        assertEquals("Normal", normal?.classification)
        assertTrue(normal?.isNormal == true)

        // Overweight (25 - 29.9)
        val over = BodyMetricsCalculator.calculateBmi(80f, 175f)
        assertEquals("Sobrepeso", over?.classification)
        assertTrue(over?.isOverweight == true)

        // Obese (>= 30)
        val obese = BodyMetricsCalculator.calculateBmi(100f, 175f)
        assertEquals("Obesidade", obese?.classification)
        assertTrue(obese?.isObese == true)
    }
}
