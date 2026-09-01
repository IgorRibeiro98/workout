package com.example.feature.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.calculator.BodyMetricsCalculator
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import com.example.feature.evolution.state.EvolutionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class EvolutionViewModel(
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase,
    private val evolutionRepository: EvolutionRepository
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
                combine(
                    getEvolutionSummaryUseCase.asFlow(),
                    evolutionRepository.getPerformanceEvolutionFlow(),
                    evolutionRepository.getConsistencyMetricsFlow(),
                    evolutionRepository.getWeightEvolutionFlow(),
                    evolutionRepository.getBodyMeasurementsFlow()
                ) { summary, performance, consistency, weightEvolution, measurements ->
                    val sorted = measurements.sortedWith(compareBy({ it.date }, { it.createdAt }))
                    val latestWithWeight = sorted.lastOrNull { it.weightKg != null && it.weightKg > 0f }
                    val firstWithWeight = sorted.firstOrNull { it.weightKg != null && it.weightKg > 0f }
                    val latestWithHeight = sorted.lastOrNull { it.heightCm != null && it.heightCm > 0f }

                    val currentWeight = latestWithWeight?.weightKg ?: summary.currentWeight ?: weightEvolution.currentWeight
                    val initialWeight = firstWithWeight?.weightKg ?: summary.initialWeight ?: weightEvolution.firstWeight
                    val weightVariation = if (currentWeight != null && initialWeight != null) {
                        val rawDiff = currentWeight - initialWeight
                        (rawDiff * 10f).roundToInt() / 10f
                    } else summary.weightChange ?: weightEvolution.variation

                    val currentHeight = latestWithHeight?.heightCm
                    val bmiResult = if (currentWeight != null && currentHeight != null) {
                        BodyMetricsCalculator.calculateBMI(currentWeight, currentHeight)
                    } else null

                    EvolutionUiState(
                        isLoading = false,
                        summary = summary,
                        performance = performance,
                        consistency = consistency,
                        weightEvolution = weightEvolution,
                        measurements = measurements,
                        currentWeight = currentWeight,
                        initialWeight = initialWeight,
                        weightVariation = weightVariation,
                        currentHeight = currentHeight,
                        bmi = bmiResult?.value,
                        bmiCategory = bmiResult?.category,
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

