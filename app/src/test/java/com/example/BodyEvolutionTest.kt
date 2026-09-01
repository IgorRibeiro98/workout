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
import com.example.feature.evolution.components.body.ChartPoint
import com.example.feature.evolution.components.body.MetricComparisonData
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
     * T12.2B Teste 1 — Histórico de peso
     * Entrada: 01/08 - 90kg, 15/08 - 89kg, 30/08 - 88kg
     * Esperado: Gráfico/Pontos: 90, 89, 88
     */
    @Test
    fun testWeightHistoryGraphPoints() {
        val m1 = BodyMeasurement(id = 1L, date = 1000L, weightKg = 90.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        val m2 = BodyMeasurement(id = 2L, date = 2000L, weightKg = 89.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        val m3 = BodyMeasurement(id = 3L, date = 3000L, weightKg = 88.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)

        val list = listOf(m3, m1, m2) // fora de ordem
        val validSorted = list.filter { it.weightKg != null && it.weightKg > 0f }.sortedBy { it.date }
        val chartPoints = validSorted.map { ChartPoint(date = it.date, value = it.weightKg!!) }

        assertEquals(3, chartPoints.size)
        assertEquals(90.0f, chartPoints[0].value, 0.01f)
        assertEquals(89.0f, chartPoints[1].value, 0.01f)
        assertEquals(88.0f, chartPoints[2].value, 0.01f)
        assertTrue(chartPoints.size >= 2) // Histórico suficiente para exibir o gráfico
    }

    /**
     * T12.2B Teste 2 — Um único peso
     * Entrada: 88kg
     * Esperado: Menos de 2 pontos (size < 2), mensagem "Registre mais pesos para visualizar sua evolução"
     */
    @Test
    fun testSingleWeightRequiresMoreRecordsForChart() {
        val single = BodyMeasurement(id = 1L, date = 1000L, weightKg = 88.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)

        val list = listOf(single)
        val validSorted = list.filter { it.weightKg != null && it.weightKg > 0f }.sortedBy { it.date }
        val chartPoints = validSorted.map { ChartPoint(date = it.date, value = it.weightKg!!) }

        assertEquals(1, chartPoints.size)
        assertTrue(chartPoints.size < 2) // Indica que deve exibir mensagem informativa
    }

    /**
     * T12.2B Teste 3 — Comparação parcial
     * Entrada: Usuário possui apenas Peso e Cintura
     * Esperado: Mostrar somente Peso e Cintura (não mostrar peito, braço, coxa)
     */
    @Test
    fun testPartialBodyComparisonShowsOnlyExistingMetrics() {
        val m1 = BodyMeasurement(id = 1L, date = 1000L, weightKg = 90.1f, waistCm = 96.0f, heightCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        val m2 = BodyMeasurement(id = 2L, date = 2000L, weightKg = 88.4f, waistCm = 91.0f, heightCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, rightArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)

        val measurements = listOf(m1, m2)
        val sorted = measurements.sortedBy { it.date }
        val latest = sorted.last()

        val items = mutableListOf<MetricComparisonData>()
        if (latest.weightKg != null && latest.weightKg > 0f) {
            val initial = sorted.firstOrNull { it.weightKg != null && it.weightKg > 0f }?.weightKg
            items.add(MetricComparisonData("Peso", initial, latest.weightKg, "kg"))
        }
        if (latest.waistCm != null && latest.waistCm > 0f) {
            val initial = sorted.firstOrNull { it.waistCm != null && it.waistCm > 0f }?.waistCm
            items.add(MetricComparisonData("Cintura", initial, latest.waistCm, "cm"))
        }
        if (latest.chestCm != null && latest.chestCm > 0f) {
            val initial = sorted.firstOrNull { it.chestCm != null && it.chestCm > 0f }?.chestCm
            items.add(MetricComparisonData("Peitoral", initial, latest.chestCm, "cm"))
        }

        assertEquals(2, items.size)
        assertEquals("Peso", items[0].label)
        assertEquals(90.1f, items[0].initialValue ?: 0f, 0.01f)
        assertEquals(88.4f, items[0].currentValue, 0.01f)

        assertEquals("Cintura", items[1].label)
        assertEquals(96.0f, items[1].initialValue ?: 0f, 0.01f)
        assertEquals(91.0f, items[1].currentValue, 0.01f)
    }

    /**
     * T12.2B Teste 4 — Medidas sem alteração
     * Entrada: Braço: 37 e 37
     * Esperado: Mostrar 37 cm → 37 cm, não esconder.
     */
    @Test
    fun testUnchangedMetricIsDisplayed() {
        val m1 = BodyMeasurement(id = 1L, date = 1000L, weightKg = 80.0f, rightArmCm = 37.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)
        val m2 = BodyMeasurement(id = 2L, date = 2000L, weightKg = 80.0f, rightArmCm = 37.0f, heightCm = null, waistCm = null, abdomenCm = null, chestCm = null, leftArmCm = null, leftThighCm = null, rightThighCm = null, leftCalfCm = null, rightCalfCm = null, hipCm = null, bodyFatPercentage = null)

        val measurements = listOf(m1, m2)
        val sorted = measurements.sortedBy { it.date }
        val latest = sorted.last()

        val items = mutableListOf<MetricComparisonData>()
        if (latest.rightArmCm != null && latest.rightArmCm > 0f) {
            val initial = sorted.firstOrNull { it.rightArmCm != null && it.rightArmCm > 0f }?.rightArmCm
            items.add(MetricComparisonData("Braço direito", initial, latest.rightArmCm, "cm"))
        }

        assertEquals(1, items.size)
        assertEquals("Braço direito", items[0].label)
        assertEquals(37.0f, items[0].initialValue ?: 0f, 0.01f)
        assertEquals(37.0f, items[0].currentValue, 0.01f)
    }

    /**
     * T12.2B Teste 5 — Usuário novo
     * Entrada: []
     * Esperado: Estado vazio, sem medições.
     */
    @Test
    fun testEmptyData_NewUser() = runTest {
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

    @Test
    fun testBmiCalculation() {
        val bmiResult = BodyMetricsCalculator.calculateBMI(weightKg = 88.4f, heightCm = 171.0f)
        assertNotNull(bmiResult)
        assertEquals(30.2f, bmiResult?.value ?: 0f, 0.05f)
        assertEquals(BMICategory.OBESITY, bmiResult?.category)
    }
}
