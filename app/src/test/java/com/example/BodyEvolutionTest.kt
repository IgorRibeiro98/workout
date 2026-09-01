package com.example

import com.example.data.local.BodyMeasurementDao
import com.example.data.local.BodyMeasurementEntity
import com.example.data.mapper.toDomain
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.evolution.calculator.BodyEvolutionCalculator
import com.example.domain.evolution.calculator.BodyMetricsCalculator
import com.example.domain.evolution.calculator.WeightEvolutionCalculator
import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.BodyMeasurement
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
     * Teste 1 — Mapper
     * Entity -> Domain Model
     * Preservar todos os dados e mapeamento correto
     */
    @Test
    fun testBodyMeasurementMapper() {
        val entity = BodyMeasurementEntity(
            id = 42L,
            date = 1690848000000L,
            weightKg = 88.4f,
            heightCm = 171.0f,
            waistCm = 91.0f,
            abdomenCm = 94.0f,
            chestCm = 102.0f,
            leftArmCm = 37.5f,
            rightArmCm = 38.0f,
            leftThighCm = 58.0f,
            rightThighCm = 58.5f,
            calfCm = 38.0f,
            hipCm = 100.0f,
            bodyFatPercentage = 18.5f
        )

        val domain = entity.toDomain()

        assertEquals(42L, domain.id)
        assertEquals(1690848000000L, domain.date)
        assertEquals(88.4f, domain.weightKg ?: 0f, 0.01f)
        assertEquals(171.0f, domain.heightCm ?: 0f, 0.01f)
        assertEquals(91.0f, domain.waistCm ?: 0f, 0.01f)
        assertEquals(94.0f, domain.abdomenCm ?: 0f, 0.01f)
        assertEquals(102.0f, domain.chestCm ?: 0f, 0.01f)
        assertEquals(37.5f, domain.leftArmCm ?: 0f, 0.01f)
        assertEquals(38.0f, domain.rightArmCm ?: 0f, 0.01f)
        assertEquals(58.0f, domain.leftThighCm ?: 0f, 0.01f)
        assertEquals(58.5f, domain.rightThighCm ?: 0f, 0.01f)
        assertEquals(38.0f, domain.leftCalfCm ?: 0f, 0.01f)
        assertEquals(38.0f, domain.rightCalfCm ?: 0f, 0.01f)
        assertEquals(100.0f, domain.hipCm ?: 0f, 0.01f)
        assertEquals(18.5f, domain.bodyFatPercentage ?: 0f, 0.01f)
    }

    /**
     * Teste 2 — Cálculo corporal
     * Entradas: 90kg e 88kg
     * Esperado: peso inicial 90kg, peso atual 88kg, variação de -2,0kg
     */
    @Test
    fun testBodyEvolutionCalculation_WeightVariation() = runTest {
        val m1 = BodyMeasurement(
            id = 1L,
            date = 1000L,
            weightKg = 90.0f,
            heightCm = 175.0f,
            waistCm = null, abdomenCm = null, chestCm = null,
            leftArmCm = null, rightArmCm = null, leftThighCm = null,
            rightThighCm = null, leftCalfCm = null, rightCalfCm = null,
            hipCm = null, bodyFatPercentage = null
        )
        val m2 = BodyMeasurement(
            id = 2L,
            date = 2000L,
            weightKg = 88.0f,
            heightCm = 175.0f,
            waistCm = null, abdomenCm = null, chestCm = null,
            leftArmCm = null, rightArmCm = null, leftThighCm = null,
            rightThighCm = null, leftCalfCm = null, rightCalfCm = null,
            hipCm = null, bodyFatPercentage = null
        )

        val summary = BodyEvolutionCalculator.calculate(listOf(m1, m2))

        assertEquals(88.0f, summary.currentWeight ?: 0f, 0.01f)
        assertEquals(90.0f, summary.initialWeight ?: 0f, 0.01f)
        assertEquals(-2.0f, summary.weightVariation ?: 0f, 0.01f)

        // Verificando também via ViewModel
        val fakeRepo = BodyMeasurementRepository(createFakeDao(listOf(
            BodyMeasurementEntity(id = 1L, date = 1000L, weightKg = 90.0f),
            BodyMeasurementEntity(id = 2L, date = 2000L, weightKg = 88.0f)
        )))

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

        val measurement = BodyMeasurement(
            id = 1L,
            date = 1000L,
            weightKg = 88.4f,
            heightCm = 171.0f,
            waistCm = null, abdomenCm = null, chestCm = null,
            leftArmCm = null, rightArmCm = null, leftThighCm = null,
            rightThighCm = null, leftCalfCm = null, rightCalfCm = null,
            hipCm = null, bodyFatPercentage = null
        )

        val summary = BodyEvolutionCalculator.calculate(listOf(measurement))
        assertEquals(88.4f, summary.currentWeight ?: 0f, 0.01f)
        assertEquals(171.0f, summary.currentHeight ?: 0f, 0.01f)
        assertEquals(30.2f, summary.bmi ?: 0f, 0.05f)
        assertEquals(BMICategory.OBESITY, summary.bmiCategory)
    }

    /**
     * Teste 4 — Histórico
     * Entrada: 01/08 (90kg), 15/08 (89kg), 30/08 (88kg)
     * Esperado: 3 pontos cronológicos ordenados com variação de -2kg e tendência DOWN
     */
    @Test
    fun testWeightHistory() = runTest {
        val m1 = BodyMeasurement(id = 1L, date = 1000L, weightKg = 90.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        val m2 = BodyMeasurement(id = 2L, date = 2000L, weightKg = 89.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        val m3 = BodyMeasurement(id = 3L, date = 3000L, weightKg = 88.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)

        // Passando fora de ordem para validar ordenação
        val weightEvolution = WeightEvolutionCalculator.calculateFromMeasurements(listOf(m3, m1, m2))
        assertEquals(90.0f, weightEvolution.firstWeight ?: 0f, 0.01f)
        assertEquals(88.0f, weightEvolution.currentWeight ?: 0f, 0.01f)
        assertEquals(-2.0f, weightEvolution.variation ?: 0f, 0.01f)
        assertEquals(3, weightEvolution.measurementsCount)
        assertEquals(WeightTrend.DOWN, weightEvolution.trend)
    }

    /**
     * Teste 5 — Sem dados
     * Entrada: []
     * Esperado: Estado seguro, valores nulos, sem crash
     */
    @Test
    fun testEmptyDataSafety() = runTest {
        val summary = BodyEvolutionCalculator.calculate(emptyList())
        assertNull(summary.currentWeight)
        assertNull(summary.initialWeight)
        assertNull(summary.weightVariation)
        assertNull(summary.currentHeight)
        assertNull(summary.bmi)
        assertNull(summary.bmiCategory)

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
}
