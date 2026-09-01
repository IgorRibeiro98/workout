package com.example.feature.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.calculator.BodyEvolutionCalculator
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import com.example.feature.evolution.state.EvolutionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class EvolutionViewModel(
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase,
    private val evolutionRepository: EvolutionRepository,
    private val performanceRepository: PerformanceRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(EvolutionUiState(isLoading = true))
    val uiState: StateFlow<EvolutionUiState> = _uiState.asStateFlow()

    init {
        loadEvolution()
    }

    fun loadEvolution() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val performanceSummaryFlow = performanceRepository?.getPerformanceSummaryFlow()
                    ?: flowOf(null)
                val exerciseEvolutionsFlow = performanceRepository?.getAllExercisesEvolutionFlow()
                    ?: flowOf(emptyList())
                val personalRecordsFlow = performanceRepository?.getPersonalRecordsFlow()
                    ?: flowOf(emptyList())
                val volumeHistoryFlow = performanceRepository?.getVolumeHistoryFlow()
                    ?: flowOf(emptyList())

                combine(
                    getEvolutionSummaryUseCase.asFlow(),
                    evolutionRepository.getPerformanceEvolutionFlow(),
                    evolutionRepository.getConsistencyMetricsFlow(),
                    evolutionRepository.getWeightEvolutionFlow(),
                    evolutionRepository.getBodyMeasurementsFlow(),
                    performanceSummaryFlow,
                    exerciseEvolutionsFlow,
                    personalRecordsFlow,
                    volumeHistoryFlow
                ) { args: Array<Any?> ->
                    val summary = args[0] as com.example.domain.evolution.model.EvolutionSummary
                    val performance = args[1] as com.example.domain.evolution.model.PerformanceEvolution
                    val consistency = args[2] as com.example.domain.evolution.model.ConsistencyMetrics
                    val weightEvolution = args[3] as com.example.domain.evolution.model.WeightEvolution
                    @Suppress("UNCHECKED_CAST")
                    val measurements = args[4] as List<com.example.domain.evolution.model.BodyMeasurement>
                    val performanceSummary = args[5] as? com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
                    @Suppress("UNCHECKED_CAST")
                    val exerciseEvolutions = args[6] as List<com.example.domain.evolution.model.performance.ExercisePerformanceEvolution>
                    @Suppress("UNCHECKED_CAST")
                    val personalRecords = args[7] as List<com.example.domain.evolution.model.performance.PersonalRecord>
                    @Suppress("UNCHECKED_CAST")
                    val volumeHistory = args[8] as List<com.example.domain.evolution.model.performance.VolumePoint>

                    val bodySummary = BodyEvolutionCalculator.calculate(measurements)
                    val currentWeight = bodySummary.currentWeight ?: summary.currentWeight ?: weightEvolution.currentWeight
                    val initialWeight = bodySummary.initialWeight ?: summary.initialWeight ?: weightEvolution.firstWeight
                    val weightVariation = bodySummary.weightVariation ?: summary.weightChange ?: weightEvolution.variation

                    EvolutionUiState(
                        isLoading = false,
                        summary = summary,
                        performance = performance,
                        consistency = consistency,
                        weightEvolution = weightEvolution,
                        measurements = measurements,
                        bodyEvolutionSummary = bodySummary,
                        currentWeight = currentWeight,
                        initialWeight = initialWeight,
                        weightVariation = weightVariation,
                        currentHeight = bodySummary.currentHeight,
                        bmi = bodySummary.bmi,
                        bmiCategory = bodySummary.bmiCategory,
                        performanceSummary = performanceSummary,
                        exerciseEvolutions = exerciseEvolutions,
                        personalRecords = personalRecords,
                        volumeHistory = volumeHistory,
                        error = null
                    )
                }.catch { e ->
                    _uiState.value = EvolutionUiState(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar sua evolução."
                    )
                }.collect { state ->
                    _uiState.value = state
                }
            } catch (e: Exception) {
                _uiState.value = EvolutionUiState(
                    isLoading = false,
                    error = e.message ?: "Não foi possível carregar sua evolução."
                )
            }
        }
    }
}
