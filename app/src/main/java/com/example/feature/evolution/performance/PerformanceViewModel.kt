package com.example.feature.evolution.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                    performanceRepository.getPersonalRecordsFlow()
                ) { summary, allExercises, records ->
                    PerformanceUiState(
                        isLoading = false,
                        summary = summary,
                        topExercises = allExercises.take(5),
                        personalRecords = records.take(5),
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
}
