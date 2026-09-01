package com.example.feature.evolution.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.mapper.toDomain
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.evolution.calculator.BodyEvolutionCalculator
import com.example.domain.evolution.calculator.WeightEvolutionCalculator
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.EvolutionPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BodyEvolutionViewModel(
    private val repository: BodyMeasurementRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BodyEvolutionUiState(isLoading = true))
    val uiState: StateFlow<BodyEvolutionUiState> = _uiState.asStateFlow()

    init {
        loadBodyEvolution()
    }

    fun loadBodyEvolution() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.allMeasurements
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Erro ao carregar medidas corporais"
                        )
                    }
                }
                .collect { entities ->
                    val domainMeasurements = entities.toDomain()
                    processMeasurements(domainMeasurements)
                }
        }
    }

    fun setPeriod(period: EvolutionPeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
    }

    private fun processMeasurements(measurements: List<BodyMeasurement>) {
        val summary = BodyEvolutionCalculator.calculate(measurements)
        val weightEvolution = WeightEvolutionCalculator.calculateFromMeasurements(measurements)

        _uiState.update {
            it.copy(
                isLoading = false,
                summary = summary,
                measurements = measurements,
                weightEvolution = weightEvolution,
                currentWeight = summary.currentWeight,
                initialWeight = summary.initialWeight,
                weightVariation = summary.weightVariation,
                currentHeight = summary.currentHeight,
                bmi = summary.bmi,
                bmiCategory = summary.bmiCategory,
                error = null
            )
        }
    }
}
