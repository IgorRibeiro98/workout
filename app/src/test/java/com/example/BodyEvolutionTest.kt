package com.example

import com.example.data.local.BodyMeasurementDao
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.evolution.calculator.BodyMetricsCalculator
import com.example.domain.evolution.calculator.WeightEvolutionCalculator
import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.WeightTrend
import com.example.feature.evolution.body.BodyEvolutionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BodyEvolutionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createFakeDao(initial: List<BodyMeasurementEntity> = emptyList()): BodyMeasurementDao {
        val flow = MutableStateFlow(initial)
        return object : BodyMeasurementDao {
            override fun getAllMeasurements(): Flow<List<BodyMeasurementEntity>> = flow
            override suspend fun getAllMeasurementsSync(): List<BodyMeasurementEntity> = flow.value
            override fun getLatestMeasurement(): Flow<BodyMeasurementEntity?> = flowOf(flow.value.firstOrNull())
            override suspend fun getLatestMeasurementSync(): BodyMeasurementEntity? = flow.value.firstOrNull()
            override fun getMeasurementById(id: Long): Flow<BodyMeasurementEntity?> = flowOf(flow.value.find { it.id == id })
            override suspend fun getMeasurementByIdSync(id: Long): BodyMeasurementEntity? = flow.value.find { it.id == id }
            override suspend fun insertMeasurement(measurement: BodyMeasurementEntity): Long = 1L
            override suspend fun updateMeasurement(measurement: BodyMeasurementEntity) {}
            override suspend fun deleteMeasurement(measurement: BodyMeasurementEntity) {}
            override suspend fun deleteMeasurementById(id: Long) {}
        }
    }

    /**
     * Teste 1 — Usuário sem medidas
     * Entrada: []
     * Esperado: currentWeight = null, bmi = null, hasMeasurements = false
     */
    @Test
    fun testUserWithoutMeasurements() = runTest {
        val fakeRepo = BodyMeasurementRepository(createFakeDao(emptyList()))

        val viewModel = BodyEvolutionViewModel(fakeRepo)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.currentWeight)
        assertNull(state.bmi)
        assertNull(state.weightVariation)
        assertFalse(state.hasMeasurements)
        assertTrue(state.measurements.isEmpty())
    }

    /**
     * Teste 2 — Peso
     * Entrada: 90kg, 88kg
     * Esperado: 88kg atual, -2kg variação
     */
    @Test
    fun testWeightEvolution_WithWeightLoss() = runTest {
        val m1 = BodyMeasurementEntity(
            id = 1L,
            date = 1000L,
            weightKg = 90.0f
        )
        val m2 = BodyMeasurementEntity(
            id = 2L,
            date = 2000L,
            weightKg = 88.0f
        )

        val fakeRepo = BodyMeasurementRepository(createFakeDao(listOf(m1, m2)))

        val viewModel = BodyEvolutionViewModel(fakeRepo)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(88.0f, state.currentWeight ?: 0f, 0.01f)
        assertEquals(90.0f, state.initialWeight ?: 0f, 0.01f)
        assertEquals(-2.0f, state.weightVariation ?: 0f, 0.01f)
    }

    /**
     * Teste 3 — IMC
     * Entrada: Peso: 88,4 kg, Altura: 171 cm (1,71 m)
     * Esperado: IMC: 30,2 (OBESITY)
     */
    @Test
    fun testBmiCalculation() = runTest {
        val bmiResult = BodyMetricsCalculator.calculateBMI(weightKg = 88.4f, heightCm = 171.0f)
        assertNotNull(bmiResult)
        assertEquals(30.2f, bmiResult?.value ?: 0f, 0.05f)
        assertEquals(BMICategory.OBESITY, bmiResult?.category)

        // Test with ViewModel
        val measurement = BodyMeasurementEntity(
            id = 1L,
            date = 1000L,
            weightKg = 88.4f,
            heightCm = 171.0f
        )
        val fakeRepo = BodyMeasurementRepository(createFakeDao(listOf(measurement)))

        val viewModel = BodyEvolutionViewModel(fakeRepo)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(88.4f, state.currentWeight ?: 0f, 0.01f)
        assertEquals(171.0f, state.currentHeight ?: 0f, 0.01f)
        assertEquals(30.2f, state.bmi ?: 0f, 0.05f)
        assertEquals(BMICategory.OBESITY, state.bmiCategory)
    }

    /**
     * Teste 4 — Medidas parciais
     * Entrada: Peso, Cintura, Braço
     * Esperado: Apenas essas medidas populadas
     */
    @Test
    fun testPartialMeasurements() = runTest {
        val measurement = BodyMeasurementEntity(
            id = 1L,
            date = 1000L,
            weightKg = 85.0f,
            waistCm = 90.0f,
            rightArmCm = 38.0f,
            chestCm = null,
            hipCm = null
        )

        assertNotNull(measurement.weightKg)
        assertNotNull(measurement.waistCm)
        assertNotNull(measurement.rightArmCm)
        assertNull(measurement.chestCm)
        assertNull(measurement.hipCm)
    }

    /**
     * Teste 5 — Histórico de Peso
     * Entrada: 01/08 90kg, 15/08 89kg, 30/08 88kg
     * Esperado: 3 medições ordenadas cronologicamente com variação de -2kg
     */
    @Test
    fun testWeightHistory() = runTest {
        val m1 = BodyMeasurementEntity(id = 1L, date = 1000L, weightKg = 90.0f)
        val m2 = BodyMeasurementEntity(id = 2L, date = 2000L, weightKg = 89.0f)
        val m3 = BodyMeasurementEntity(id = 3L, date = 3000L, weightKg = 88.0f)

        val weightEvolution = WeightEvolutionCalculator.calculateFromMeasurements(listOf(m3, m1, m2))
        assertEquals(90.0f, weightEvolution.firstWeight ?: 0f, 0.01f)
        assertEquals(88.0f, weightEvolution.currentWeight ?: 0f, 0.01f)
        assertEquals(-2.0f, weightEvolution.variation ?: 0f, 0.01f)
        assertEquals(3, weightEvolution.measurementsCount)
        assertEquals(WeightTrend.DOWN, weightEvolution.trend)
    }
}
