package com.example.feature.evolution.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.repository.PerformanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PerformanceViewModel(
    private val performanceRepository: PerformanceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceUiState(isLoading = true))
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    init {
        loadPerformance()
    }

    fun loadPerformance() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                combine(
                    performanceRepository.getPerformanceSummaryFlow(),
                    performanceRepository.getAllExercisesEvolutionFlow(),
                    performanceRepository.getPersonalRecordsFlow(),
                    performanceRepository.getVolumeHistoryFlow()
                ) { summary, allExercises, records, volumeHistory ->
                    val exercisesWithHistory = allExercises
                        .filter { it.totalExecutions > 0 && it.bestWeight != null }
                        .sortedByDescending { it.totalExecutions }
                    val currentSelected = _uiState.value.selectedExerciseId
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

                    PerformanceUiState(
                        isLoading = false,
                        summary = summary,
                        topExercises = allExercises.take(5),
                        allExercises = exercisesWithHistory,
                        personalRecords = records.take(5),
                        volumeHistory = volumeHistory,
                        selectedExerciseId = selectedExerciseId,
                        selectedExerciseName = selectedExerciseName,
                        strengthHistory = strengthPoints,
                        error = null
                    )
                }.catch { e ->
                    _uiState.value = PerformanceUiState(
                        isLoading = false,
                        error = e.message ?: "Não foi possível carregar sua performance."
                    )
                }.collect { state ->
                    _uiState.value = state
                }
            } catch (e: Exception) {
                _uiState.value = PerformanceUiState(
                    isLoading = false,
                    error = e.message ?: "Não foi possível carregar sua performance."
                )
            }
        }
    }

    fun selectExercise(exerciseId: String) {
        val exercise = _uiState.value.allExercises.find { it.exerciseId == exerciseId }
        val exerciseName = exercise?.exerciseName ?: exerciseId
        viewModelScope.launch {
            try {
                val strengthPoints = performanceRepository.getExerciseStrengthHistory(exerciseId)
                _uiState.value = _uiState.value.copy(
                    selectedExerciseId = exerciseId,
                    selectedExerciseName = exerciseName,
                    strengthHistory = strengthPoints
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    selectedExerciseId = exerciseId,
                    selectedExerciseName = exerciseName,
                    error = e.message
                )
            }
        }
    }
}
