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
     * MainViewModelFactory requires non-nullable BodyMeasurementRepository.
     */
    @Test
    fun testViewModelFactory_DirectInjectionOfBodyMeasurementRepository() {
        val app = context.applicationContext as MainApplication
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
     * Cadastrar: Peso: -10 -> Bloquear salvamento
     */
    @Test
    fun testMandatory2_BlockNegativeWeight() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)
        viewModel.initNewMeasurement()
        viewModel.updateWeight("-10")

        val saved = viewModel.saveMeasurementSuspending()
        assertFalse("Should block negative weight", saved)
        assertNotNull("Should contain weight error message", viewModel.formState.value.errors["weight"])

        val all = repository.allMeasurements.first()
        assertTrue("Database should remain empty after invalid entry", all.isEmpty())
    }

    /**
     * Teste 3 (Mandatory Test 3):
     * Criar: 88.4kg -> Editar: 87.9kg -> Registro atualizado
     */
    @Test
    fun testMandatory3_EditExistingMeasurement() = runBlocking {
        val viewModel = BodyEvolutionViewModel(repository)

        // 1. Initial creation (88.4 kg)
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

        // 3. Update values (edit to: 87.9)
        viewModel.updateWeight("87.9")
        viewModel.updateWaist("90.5")

        val updated = viewModel.saveMeasurementSuspending()
        assertTrue("Should save update successfully", updated)

        // 4. Verify in repository
        val afterUpdate = repository.getMeasurementById(id)
        assertNotNull(afterUpdate)
        assertEquals(87.9f, afterUpdate?.weightKg ?: 0f, 0.01f)
        assertEquals(90.5f, afterUpdate?.waistCm ?: 0f, 0.01f)

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

    /**
     * Dedicated tests for BodyMeasurementValidator domain logic
     */
    @Test
    fun testBodyMeasurementValidator_Rules() {
        // Weight: 0 < peso <= 500
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateWeight("88.4") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateWeight("500") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateWeight("0") is com.example.domain.body.ValidationResult.Error)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateWeight("-10") is com.example.domain.body.ValidationResult.Error)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateWeight("600") is com.example.domain.body.ValidationResult.Error)

        // Height: 50 <= altura <= 300
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateHeight("171") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateHeight("50") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateHeight("300") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateHeight("49") is com.example.domain.body.ValidationResult.Error)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateHeight("301") is com.example.domain.body.ValidationResult.Error)

        // Measures: 0 < medida <= 300
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyMeasure("91", "Cintura") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyMeasure("300", "Peito") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyMeasure("0", "Abdômen") is com.example.domain.body.ValidationResult.Error)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyMeasure("301", "Coxa") is com.example.domain.body.ValidationResult.Error)

        // Body Fat: 0 < percentual < 100
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyFat("15.5") is com.example.domain.body.ValidationResult.Success)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyFat("0") is com.example.domain.body.ValidationResult.Error)
        assertTrue(com.example.domain.body.BodyMeasurementValidator.validateBodyFat("100") is com.example.domain.body.ValidationResult.Error)
    }
}
