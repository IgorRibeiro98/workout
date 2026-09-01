package com.example.feature.evolution.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BodyMeasurementEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.domain.evolution.calculator.BodyMetricsCalculator
import com.example.domain.evolution.calculator.WeightEvolutionCalculator
import com.example.domain.evolution.model.EvolutionPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                .collect { measurements ->
                    processMeasurements(measurements)
                }
        }
    }

    fun setPeriod(period: EvolutionPeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
    }

    private fun processMeasurements(measurements: List<BodyMeasurementEntity>) {
        val sorted = measurements.sortedWith(compareBy({ it.date }, { it.createdAt }))
        val latest = sorted.lastOrNull()
        val latestWithWeight = sorted.lastOrNull { it.weightKg != null && it.weightKg > 0f }
        val firstWithWeight = sorted.firstOrNull { it.weightKg != null && it.weightKg > 0f }
        val latestWithHeight = sorted.lastOrNull { it.heightCm != null && it.heightCm > 0f }

        val currentWeight = latestWithWeight?.weightKg
        val initialWeight = firstWithWeight?.weightKg
        val weightVariation = if (currentWeight != null && initialWeight != null) {
            val rawDiff = currentWeight - initialWeight
            (rawDiff * 10f).roundToInt() / 10f
        } else null

        val currentHeight = latestWithHeight?.heightCm
        val bmiResult = if (currentWeight != null && currentHeight != null) {
            BodyMetricsCalculator.calculateBMI(currentWeight, currentHeight)
        } else null

        _uiState.update {
            it.copy(
                isLoading = false,
                measurements = measurements,
                currentWeight = currentWeight,
                initialWeight = initialWeight,
                weightVariation = weightVariation,
                currentHeight = currentHeight,
                bmi = bmiResult?.value,
                bmiCategory = bmiResult?.category,
                error = null
            )
        }
    }
}
