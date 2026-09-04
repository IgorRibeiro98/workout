package com.example

import com.example.domain.evolution.calculator.BodyMetricsCalculator
import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.calculator.PerformanceCalculator
import com.example.domain.evolution.calculator.WeightEvolutionCalculator
import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.WeightTrend
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class EvolutionEngineTest {

    /**
     * Teste 1 — Peso (Mandatory Test 1)
     * Entrada: 90.1, 89.7, 89.0, 88.4
     * Esperado:
     * - currentWeight: 88.4
     * - variation: -1.7
     * - trend: DOWN
     */
    @Test
    fun testMandatory1_WeightEvolution_DownTrend() {
        val weights = listOf(90.1f, 89.7f, 89.0f, 88.4f)
        val result = WeightEvolutionCalculator.calculate(weights)

        assertEquals(90.1f, result.firstWeight ?: 0f, 0.01f)
        assertEquals(88.4f, result.currentWeight ?: 0f, 0.01f)
        assertEquals(-1.7f, result.variation ?: 0f, 0.01f)
        assertEquals(4, result.measurementsCount)
        assertEquals(WeightTrend.DOWN, result.trend)
    }

    /**
     * Teste 2 — IMC (Mandatory Test 2)
     * Entrada: peso: 88.4, altura: 171
     * Esperado: IMC calculado (30.2, OBESITY)
     */
    @Test
    fun testMandatory2_BmiCalculation() {
        val result = BodyMetricsCalculator.calculateBMI(weightKg = 88.4f, heightCm = 171f)

        assertNotNull(result)
        assertEquals(30.2f, result?.value ?: 0f, 0.01f)
        assertEquals(BMICategory.OBESITY, result?.category)
    }

    /**
     * Teste 3 — Sem peso (Mandatory Test 3)
     * Entrada: sem registros corporais
     * Esperado: WeightTrend.UNKNOWN
     */
    @Test
    fun testMandatory3_NoWeights_UnknownTrend() {
        val emptyWeights = emptyList<Float>()
        val result = WeightEvolutionCalculator.calculate(emptyWeights)

        assertNull(result.firstWeight)
        assertNull(result.currentWeight)
        assertNull(result.variation)
        assertEquals(0, result.measurementsCount)
        assertEquals(WeightTrend.UNKNOWN, result.trend)

        val emptyMeasurementsResult = WeightEvolutionCalculator.calculateFromMeasurements(emptyList())
        assertEquals(WeightTrend.UNKNOWN, emptyMeasurementsResult.trend)
    }

    /**
     * Teste 4 — Volume (Mandatory Test 4)
     * Entrada: 70kg, 3 séries, 10 reps
     * Esperado: 2100 volume
     */
    @Test
    fun testMandatory4_PerformanceVolume() {
        val volume = PerformanceCalculator.calculateVolume(weightKg = 70f, sets = 3, reps = 10)
        assertEquals(2100f, volume, 0.01f)

        val beforeVolume = PerformanceCalculator.calculateVolume(weightKg = 50f, sets = 3, reps = 10)
        assertEquals(1500f, beforeVolume, 0.01f)
    }

    /**
     * Teste 5 — Consistência (Mandatory Test 5)
     * Entrada: 10 dias treinados
     * Esperado: trainingDays = 10
     */
    @Test
    fun testMandatory5_ConsistencyTrainingDays() {
        val dates = (1..10).map { day ->
            LocalDate.of(2026, 8, day)
        }
        val result = ConsistencyCalculator.calculateFromDates(dates, referenceDate = LocalDate.of(2026, 8, 10))

        assertEquals(10, result.trainingDays)
        assertEquals(10, result.monthlySessions)
    }

    @Test
    fun testWeightEvolution_UpAndStableTrends() {
        // UP trend
        val gainWeights = listOf(70.0f, 71.5f, 72.8f)
        val gainResult = WeightEvolutionCalculator.calculate(gainWeights)
        assertEquals(70.0f, gainResult.firstWeight ?: 0f, 0.01f)
        assertEquals(72.8f, gainResult.currentWeight ?: 0f, 0.01f)
        assertEquals(2.8f, gainResult.variation ?: 0f, 0.01f)
        assertEquals(WeightTrend.UP, gainResult.trend)

        // STABLE trend (1 measurement)
        val singleResult = WeightEvolutionCalculator.calculate(listOf(80.0f))
        assertEquals(WeightTrend.STABLE, singleResult.trend)
        assertEquals(0f, singleResult.variation ?: 0f, 0.01f)

        // STABLE trend (negligible diff)
        val stableResult = WeightEvolutionCalculator.calculate(listOf(80.0f, 80.02f))
        assertEquals(WeightTrend.STABLE, stableResult.trend)
    }

    @Test
    fun testBmiCategories() {
        // Underweight (< 18.5)
        val under = BodyMetricsCalculator.calculateBMI(50f, 175f)
        assertEquals(16.3f, under?.value ?: 0f, 0.1f)
        assertEquals(BMICategory.UNDERWEIGHT, under?.category)

        // Normal (18.5 - 24.9)
        val normal = BodyMetricsCalculator.calculateBMI(70f, 175f)
        assertEquals(22.9f, normal?.value ?: 0f, 0.1f)
        assertEquals(BMICategory.NORMAL, normal?.category)

        // Overweight (25 - 29.9)
        val over = BodyMetricsCalculator.calculateBMI(80f, 175f)
        assertEquals(26.1f, over?.value ?: 0f, 0.1f)
        assertEquals(BMICategory.OVERWEIGHT, over?.category)

        // Obesity (>= 30)
        val obese = BodyMetricsCalculator.calculateBMI(100f, 175f)
        assertEquals(32.7f, obese?.value ?: 0f, 0.1f)
        assertEquals(BMICategory.OBESITY, obese?.category)
    }

    @Test
    fun testConsistencyStreakCalculation() {
        // Dates: 01/08, 03/08, 05/08, 06/08, 07/08
        val dates = listOf(
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 8, 5),
            LocalDate.of(2026, 8, 6),
            LocalDate.of(2026, 8, 7)
        )
        val result = ConsistencyCalculator.calculateFromDates(dates, referenceDate = LocalDate.of(2026, 8, 7))

        assertEquals(5, result.trainingDays)
        assertEquals(5, result.monthlySessions)
    }

    @Test
    fun testGetEvolutionSummaryUseCase() = runBlocking {
        // Mock / In-memory EvolutionRepository implementation
        val fakeRepo = object : EvolutionRepository {
            override suspend fun getEvolutionSummary() = com.example.domain.evolution.model.EvolutionSummary(
                currentWeight = 88.4f,
                initialWeight = 90.1f,
                weightChange = -1.7f,
                totalWorkoutSessions = 15,
                trainingDays = 12,
                averageWorkoutsPerWeek = 3.5f,
                totalExercisesPerformed = 45,
                generatedAt = 1000L
            )

            override suspend fun getWeightEvolution() = com.example.domain.evolution.model.WeightEvolution(
                firstWeight = 90.1f,
                currentWeight = 88.4f,
                variation = -1.7f,
                measurementsCount = 4,
                trend = WeightTrend.DOWN
            )

            override suspend fun getPerformanceEvolution() = com.example.domain.evolution.model.PerformanceEvolution(
                totalSessions = 15,
                totalExercises = 45,
                totalSets = 135,
                totalRepetitions = 1350,
                totalVolume = 50000f
            )

            override suspend fun getConsistencyMetrics() = com.example.domain.evolution.model.ConsistencyMetrics(
                trainingDays = 12,
                currentStreak = 3,
                longestStreak = 5,
                monthlySessions = 10,
                averageSessionsPerWeek = 3.5f
            )

            override suspend fun getBodyMeasurements(): List<BodyMeasurement> = emptyList()

            override fun getEvolutionSummaryFlow() = kotlinx.coroutines.flow.flow { emit(getEvolutionSummary()) }
            override fun getWeightEvolutionFlow() = kotlinx.coroutines.flow.flow { emit(getWeightEvolution()) }
            override fun getPerformanceEvolutionFlow() = kotlinx.coroutines.flow.flow { emit(getPerformanceEvolution()) }
            override fun getConsistencyMetricsFlow() = kotlinx.coroutines.flow.flow { emit(getConsistencyMetrics()) }
            override fun getBodyMeasurementsFlow() = kotlinx.coroutines.flow.flow { emit(emptyList<BodyMeasurement>()) }
        }

        val useCase = GetEvolutionSummaryUseCase(fakeRepo)
        val summary = useCase()

        assertEquals(88.4f, summary.currentWeight ?: 0f, 0.01f)
        assertEquals(90.1f, summary.initialWeight ?: 0f, 0.01f)
        assertEquals(-1.7f, summary.weightChange ?: 0f, 0.01f)
        assertEquals(15, summary.totalWorkoutSessions)
        assertEquals(12, summary.trainingDays)
        assertEquals(3.5f, summary.averageWorkoutsPerWeek, 0.01f)
        assertEquals(45, summary.totalExercisesPerformed)
    }
}
