package com.example

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
     * T12.1 Validação 1: Usuário sem dados
     * Resultado esperado: Estado vazio (isEmpty = true, cards zerados ocultados)
     */
    @Test
    fun testEmptyState_UserWithoutData() = runTest {
        val emptySummary = EvolutionSummary(
            currentWeight = null,
            initialWeight = null,
            weightChange = null,
            totalWorkoutSessions = 0,
            trainingDays = 0,
            averageWorkoutsPerWeek = 0f,
            totalExercisesPerformed = 0,
            generatedAt = System.currentTimeMillis()
        )

        val emptyState = EvolutionUiState(
            isLoading = false,
            summary = emptySummary,
            performance = PerformanceEvolution(0, 0, 0, 0, 0f),
            consistency = ConsistencyMetrics(0, 0, 0, 0, 0f)
        )

        assertTrue(emptyState.isEmpty)
        assertFalse(emptyState.isLoading)
        assertNull(emptyState.summary?.currentWeight)
        assertEquals(0, emptyState.summary?.totalWorkoutSessions)
    }

    /**
     * T12.1 Validação 2: Usuário com peso
     * Entrada: 90kg, 88kg
     * Esperado: 88kg atual, -2kg variação
     */
    @Test
    fun testWeightCardData_WithWeightLoss() = runTest {
        val summaryWithWeight = EvolutionSummary(
            currentWeight = 88.0f,
            initialWeight = 90.0f,
            weightChange = -2.0f,
            totalWorkoutSessions = 5,
            trainingDays = 5,
            averageWorkoutsPerWeek = 2.5f,
            totalExercisesPerformed = 20,
            generatedAt = System.currentTimeMillis()
        )

        val state = EvolutionUiState(
            isLoading = false,
            summary = summaryWithWeight
        )

        assertFalse(state.isEmpty)
        assertEquals(88.0f, state.summary?.currentWeight ?: 0f, 0.01f)
        assertEquals(90.0f, state.summary?.initialWeight ?: 0f, 0.01f)
        assertEquals(-2.0f, state.summary?.weightChange ?: 0f, 0.01f)
    }

    /**
     * T12.1 Validação 3: Usuário com treinos
     * Entrada: 50 sessões
     * Esperado: 50 treinos concluídos
     */
    @Test
    fun testWorkoutsMetricData_With50Sessions() = runTest {
        val summaryWithWorkouts = EvolutionSummary(
            currentWeight = 75.0f,
            initialWeight = 75.0f,
            weightChange = 0.0f,
            totalWorkoutSessions = 50,
            trainingDays = 40,
            averageWorkoutsPerWeek = 4.2f,
            totalExercisesPerformed = 380,
            generatedAt = System.currentTimeMillis()
        )

        val state = EvolutionUiState(
            isLoading = false,
            summary = summaryWithWorkouts
        )

        assertFalse(state.isEmpty)
        assertEquals(50, state.summary?.totalWorkoutSessions)
        assertEquals(40, state.summary?.trainingDays)
        assertEquals(380, state.summary?.totalExercisesPerformed)
    }

    /**
     * T12.1 Validação 4: Usuário com consistência
     * Entrada: currentStreak = 10
     * Esperado: 🔥 10 dias de sequência ativa
     */
    @Test
    fun testConsistencyCardData_With10DaysStreak() = runTest {
        val consistencyMetrics = ConsistencyMetrics(
            trainingDays = 15,
            currentStreak = 10,
            longestStreak = 10,
            monthlySessions = 12,
            averageSessionsPerWeek = 4.5f
        )

        val summary = EvolutionSummary(
            currentWeight = 80f,
            initialWeight = 82f,
            weightChange = -2f,
            totalWorkoutSessions = 15,
            trainingDays = 15,
            averageWorkoutsPerWeek = 4.5f,
            totalExercisesPerformed = 60,
            generatedAt = System.currentTimeMillis()
        )

        val state = EvolutionUiState(
            isLoading = false,
            summary = summary,
            consistency = consistencyMetrics
        )

        assertFalse(state.isEmpty)
        assertEquals(10, state.consistency?.currentStreak)
        assertEquals(15, state.consistency?.trainingDays)
        assertEquals(4.5f, state.consistency?.averageSessionsPerWeek ?: 0f, 0.01f)
    }

    /**
     * T12.1 Validação 5: EvolutionViewModel orquestração
     */
    @Test
    fun testEvolutionViewModel_FlowObservation() = runTest {
        val fakeRepo = object : EvolutionRepository {
            override suspend fun getEvolutionSummary() = EvolutionSummary(
                currentWeight = 88.4f,
                initialWeight = 90.1f,
                weightChange = -1.7f,
                totalWorkoutSessions = 48,
                trainingDays = 42,
                averageWorkoutsPerWeek = 4.2f,
                totalExercisesPerformed = 380,
                generatedAt = 1000L
            )

            override suspend fun getWeightEvolution() = WeightEvolution(
                firstWeight = 90.1f,
                currentWeight = 88.4f,
                variation = -1.7f,
                measurementsCount = 4,
                trend = WeightTrend.DOWN
            )

            override suspend fun getPerformanceEvolution() = PerformanceEvolution(
                totalSessions = 48,
                totalExercises = 380,
                totalSets = 420,
                totalRepetitions = 4200,
                totalVolume = 125400f
            )

            override suspend fun getConsistencyMetrics() = ConsistencyMetrics(
                trainingDays = 42,
                currentStreak = 12,
                longestStreak = 14,
                monthlySessions = 18,
                averageSessionsPerWeek = 4.2f
            )

            override fun getEvolutionSummaryFlow() = kotlinx.coroutines.flow.flow { emit(getEvolutionSummary()) }
            override fun getWeightEvolutionFlow() = kotlinx.coroutines.flow.flow { emit(getWeightEvolution()) }
            override fun getPerformanceEvolutionFlow() = kotlinx.coroutines.flow.flow { emit(getPerformanceEvolution()) }
            override fun getConsistencyMetricsFlow() = kotlinx.coroutines.flow.flow { emit(getConsistencyMetrics()) }
        }

        val useCase = GetEvolutionSummaryUseCase(fakeRepo)
        val viewModel = EvolutionViewModel(useCase, fakeRepo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNotNull(state.summary)
        assertFalse(state.isEmpty)

        // Validate summary values
        assertEquals(88.4f, state.summary?.currentWeight ?: 0f, 0.01f)
        assertEquals(90.1f, state.summary?.initialWeight ?: 0f, 0.01f)
        assertEquals(-1.7f, state.summary?.weightChange ?: 0f, 0.01f)
        assertEquals(48, state.summary?.totalWorkoutSessions)
        assertEquals(42, state.summary?.trainingDays)
        assertEquals(380, state.summary?.totalExercisesPerformed)

        // Validate performance values
        assertEquals(125400f, state.performance?.totalVolume ?: 0f, 0.01f)
        assertEquals(420, state.performance?.totalSets)

        // Validate consistency values
        assertEquals(12, state.consistency?.currentStreak)
        assertEquals(4.2f, state.consistency?.averageSessionsPerWeek ?: 0f, 0.01f)
    }
}
