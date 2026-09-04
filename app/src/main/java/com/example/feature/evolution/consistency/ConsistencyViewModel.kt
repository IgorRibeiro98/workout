package com.example.feature.evolution.consistency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ConsistencyViewModel(
    private val consistencyRepository: ConsistencyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConsistencyUiState(isLoading = true))
    val uiState: StateFlow<ConsistencyUiState> = _uiState.asStateFlow()

    init {
        loadConsistency()
    }

    fun loadConsistency() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            combine(
                consistencyRepository.getConsistencySummaryFlow(),
                consistencyRepository.getConsistencyProgressFlow(),
                consistencyRepository.getWeeklyConsistenciesFlow(),
                consistencyRepository.getFrequencyHistoryFlow(),
                consistencyRepository.getWorkoutTimestampsFlow()
            ) { summary, progress, consistencies, frequencyHistory, timestamps ->
                ConsistencyUiState(
                    isLoading = false,
                    summary = summary,
                    consistencyProgress = progress,
                    weeklyConsistencies = consistencies,
                    frequencyHistory = frequencyHistory,
                    workoutTimestamps = timestamps,
                    error = null
                )
            }.catch { e ->
                _uiState.value = ConsistencyUiState(
                    isLoading = false,
                    error = e.localizedMessage ?: "Erro ao carregar dados de consistência"
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}
