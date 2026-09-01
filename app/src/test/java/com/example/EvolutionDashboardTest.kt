package com.example

import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.WeightEvolution
import com.example.domain.evolution.model.WeightTrend
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import com.example.feature.evolution.EvolutionViewModel
import com.example.feature.evolution.state.EvolutionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
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
class EvolutionDashboardTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * T12.1A Teste 1 — Repository obrigatório
     * ViewModel é instanciado com useCase e repository obrigatórios, sem fallback nulo.
     */
    @Test
    fun testEvolutionRepositoryMandatory() = runTest {
        val fakeRepo = createFakeRepo(
            summary = EvolutionSummary(
                currentWeight = 85f,
                initialWeight = 85f,
                weightChange = 0f,
                totalWorkoutSessions = 10,
                trainingDays = 8,
                averageWorkoutsPerWeek = 3.0f,
                totalExercisesPerformed = 50,
                generatedAt = 1000L
            )
        )
        val useCase = GetEvolutionSummaryUseCase(fakeRepo)
        val viewModel = EvolutionViewModel(useCase, fakeRepo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.summary)
        assertNotNull(state.performance)
        assertNotNull(state.consistency)
        assertNotNull(state.weightEvolution)
    }

    /**
     * T12.1A Teste 2 — Streak e Dias Ativos separados
     * Dados: 30 dias treinados, 0 dias consecutivos
     * Esperado: 30 dias ativos, 🔥 0 dias sequência (sem fallback de streak para dias ativos)
     */
    @Test
    fun testStreakAndActiveDaysSeparation() = runTest {
        val consistencyMetrics = ConsistencyMetrics(
            trainingDays = 30,
            currentStreak = 0,
            longestStreak = 12,
            monthlySessions = 20,
            averageSessionsPerWeek = 4.0f
        )

        val summary = EvolutionSummary(
            currentWeight = 80f,
            initialWeight = 80f,
            weightChange = 0f,
            totalWorkoutSessions = 30,
            trainingDays = 30,
            averageWorkoutsPerWeek = 4.0f,
            totalExercisesPerformed = 200,
            generatedAt = 1000L
        )

        val state = EvolutionUiState(
            isLoading = false,
            summary = summary,
            consistency = consistencyMetrics
        )

        assertFalse(state.isEmpty)
        // Streak deve ser 0 e NÃO 30
        assertEquals(0, state.consistency?.currentStreak)
        // Dias ativos deve ser 30
        assertEquals(30, state.consistency?.trainingDays)
    }

    /**
     * T12.1A Teste 3 — Evolução completa
     * Entrada: Peso 90 -> 88, Treinos 40, Volume 100000
     * Esperado: Dashboard carregado
     */
    @Test
    fun testCompleteEvolutionData() = runTest {
        val fakeRepo = createFakeRepo(
            summary = EvolutionSummary(
                currentWeight = 88.0f,
                initialWeight = 90.0f,
                weightChange = -2.0f,
                totalWorkoutSessions = 40,
                trainingDays = 35,
                averageWorkoutsPerWeek = 4.0f,
                totalExercisesPerformed = 300,
                generatedAt = 1000L
            ),
            weight = WeightEvolution(
                firstWeight = 90.0f,
                currentWeight = 88.0f,
                variation = -2.0f,
                measurementsCount = 5,
                trend = WeightTrend.DOWN
            ),
            performance = PerformanceEvolution(
                totalSessions = 40,
                totalExercises = 300,
                totalSets = 350,
                totalRepetitions = 3500,
                totalVolume = 100000f
            ),
            consistency = ConsistencyMetrics(
                trainingDays = 35,
                currentStreak = 5,
                longestStreak = 10,
                monthlySessions = 16,
                averageSessionsPerWeek = 4.0f
            )
        )

        val useCase = GetEvolutionSummaryUseCase(fakeRepo)
        val viewModel = EvolutionViewModel(useCase, fakeRepo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertNull(state.error)

        assertEquals(88.0f, state.summary?.currentWeight ?: 0f, 0.01f)
        assertEquals(90.0f, state.summary?.initialWeight ?: 0f, 0.01f)
        assertEquals(-2.0f, state.summary?.weightChange ?: 0f, 0.01f)
        assertEquals(40, state.summary?.totalWorkoutSessions)
        assertEquals(100000f, state.performance?.totalVolume ?: 0f, 0.01f)
        assertEquals(5, state.consistency?.currentStreak)
        assertEquals(35, state.consistency?.trainingDays)
    }

    /**
     * T12.1A Teste 4 — Usuário novo (Sem dados)
     * Esperado: Estado vazio (isEmpty = true)
     */
    @Test
    fun testEmptyState_NewUser() = runTest {
        val emptySummary = EvolutionSummary(
            currentWeight = null,
            initialWeight = null,
            weightChange = null,
            totalWorkoutSessions = 0,
            trainingDays = 0,
            averageWorkoutsPerWeek = 0f,
            totalExercisesPerformed = 0,
            generatedAt = 1000L
        )

        val emptyState = EvolutionUiState(
            isLoading = false,
            summary = emptySummary,
            performance = PerformanceEvolution(0, 0, 0, 0, 0f),
            consistency = ConsistencyMetrics(0, 0, 0, 0, 0f),
            weightEvolution = WeightEvolution(null, null, 0f, 0, WeightTrend.STABLE)
        )

        assertTrue(emptyState.isEmpty)
        assertFalse(emptyState.isLoading)
        assertNull(emptyState.summary?.currentWeight)
        assertEquals(0, emptyState.summary?.totalWorkoutSessions)
    }

    private fun createFakeRepo(
        summary: EvolutionSummary = EvolutionSummary(null, null, null, 0, 0, 0f, 0, 0L),
        weight: WeightEvolution = WeightEvolution(null, null, 0f, 0, WeightTrend.STABLE),
        performance: PerformanceEvolution = PerformanceEvolution(0, 0, 0, 0, 0f),
        consistency: ConsistencyMetrics = ConsistencyMetrics(0, 0, 0, 0, 0f)
    ): EvolutionRepository {
        return object : EvolutionRepository {
            override suspend fun getEvolutionSummary() = summary
            override suspend fun getWeightEvolution() = weight
            override suspend fun getPerformanceEvolution() = performance
            override suspend fun getConsistencyMetrics() = consistency
            override suspend fun getBodyMeasurements(): List<BodyMeasurement> = emptyList()

            override fun getEvolutionSummaryFlow() = flow { emit(summary) }
            override fun getWeightEvolutionFlow() = flow { emit(weight) }
            override fun getPerformanceEvolutionFlow() = flow { emit(performance) }
            override fun getConsistencyMetricsFlow() = flow { emit(consistency) }
            override fun getBodyMeasurementsFlow() = flow { emit(emptyList<BodyMeasurement>()) }
        }
    }
}
