package com.example.feature.evolution.performance.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.repository.PerformanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PerformanceChartViewModel(
    private val performanceRepository: PerformanceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceChartUiState(isLoading = true))
    val uiState: StateFlow<PerformanceChartUiState> = _uiState.asStateFlow()

    init {
        loadCharts()
    }

    fun loadCharts() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                combine(
                    performanceRepository.getVolumeHistoryFlow(),
                    performanceRepository.getAllExercisesEvolutionFlow()
                ) { volumeHistory, allExercises ->
                    val exercisesWithHistory = allExercises.filter { it.totalExecutions > 0 && it.bestWeight != null }
                    val currentSelected = _uiState.value.selectedExercise
                    val selectedExerciseId = when {
                        currentSelected != null && exercisesWithHistory.any { it.exerciseId == currentSelected } -> currentSelected
                        exercisesWithHistory.isNotEmpty() -> exercisesWithHistory.first().exerciseId
                        else -> null
                    }
                    val selectedExerciseName = exercisesWithHistory.find { it.exerciseId == selectedExerciseId }?.exerciseName

                    val strengthPoints = if (selectedExerciseId != null) {
                        performanceRepository.getExerciseStrengthHistory(selectedExerciseId)
                    } else {
                        emptyList()
                    }

                    PerformanceChartUiState(
                        isLoading = false,
                        volumeHistory = volumeHistory,
                        availableExercises = exercisesWithHistory,
                        selectedExercise = selectedExerciseId,
                        selectedExerciseName = selectedExerciseName,
                        strengthHistory = strengthPoints,
                        error = null
                    )
                }.catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar os gráficos de performance."
                    )
                }.collect { state ->
                    _uiState.value = state
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Não foi possível carregar os gráficos de performance."
                )
            }
        }
    }

    fun selectExercise(exerciseId: String) {
        val exercise = _uiState.value.availableExercises.find { it.exerciseId == exerciseId }
        val exerciseName = exercise?.exerciseName ?: exerciseId
        viewModelScope.launch {
            try {
                val strengthPoints = performanceRepository.getExerciseStrengthHistory(exerciseId)
                _uiState.value = _uiState.value.copy(
                    selectedExercise = exerciseId,
                    selectedExerciseName = exerciseName,
                    strengthHistory = strengthPoints
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    selectedExercise = exerciseId,
                    selectedExerciseName = exerciseName,
                    error = e.message
                )
            }
        }
    }
}
