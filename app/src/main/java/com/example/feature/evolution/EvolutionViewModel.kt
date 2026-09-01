package com.example.feature.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import com.example.feature.evolution.state.EvolutionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

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
                getEvolutionSummaryUseCase.asFlow()
                    .catch { e ->
                        _uiState.value = EvolutionUiState(
                            isLoading = false,
                            error = e.message ?: "Não foi possível carregar sua evolução."
                        )
                    }
                    .collect { summary ->
                        _uiState.value = EvolutionUiState(
                            isLoading = false,
                            summary = summary,
                            error = null
                        )
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

