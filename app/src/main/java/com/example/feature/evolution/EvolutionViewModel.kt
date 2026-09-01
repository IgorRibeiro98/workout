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
    private val performanceRepository: PerformanceRepository? = null,
    private val consistencyRepository: com.example.domain.evolution.repository.ConsistencyRepository? = null
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
                val consistencySummaryFlow = consistencyRepository?.getConsistencySummaryFlow()
                    ?: flowOf(null)
                val frequencyHistoryFlow = consistencyRepository?.getFrequencyHistoryFlow()
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
                    volumeHistoryFlow,
                    consistencySummaryFlow,
                    frequencyHistoryFlow
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
                    val consistencySummary = args[9] as? com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
                    @Suppress("UNCHECKED_CAST")
                    val frequencyHistory = args[10] as List<com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint>

                    val workoutTimestamps = volumeHistory.map { it.date }

                    val bodySummary = BodyEvolutionCalculator.calculate(measurements)
                    val currentWeight = bodySummary.currentWeight ?: summary.currentWeight ?: weightEvolution.currentWeight
                    val initialWeight = bodySummary.initialWeight ?: summary.initialWeight ?: weightEvolution.firstWeight
                    val weightVariation = bodySummary.weightVariation ?: summary.weightChange ?: weightEvolution.variation

                    val exercisesWithHistory = exerciseEvolutions
                        .filter { it.totalExecutions > 0 && it.bestWeight != null }
                        .sortedByDescending { it.totalExecutions }
                    val currentSelected = _uiState.value.selectedExerciseId
                    val selectedExerciseId = when {
                        currentSelected != null && exercisesWithHistory.any { it.exerciseId == currentSelected } -> currentSelected
                        exercisesWithHistory.isNotEmpty() -> exercisesWithHistory.first().exerciseId
                        else -> null
                    }
                    val selectedExerciseName = exercisesWithHistory.find { it.exerciseId == selectedExerciseId }?.exerciseName
                    val strengthHistory = if (selectedExerciseId != null && performanceRepository != null) {
                        performanceRepository.getExerciseStrengthHistory(selectedExerciseId)
                    } else {
                        emptyList()
                    }

                    EvolutionUiState(
                        isLoading = false,
                        summary = summary,
                        performance = performance,
                        consistency = consistency,
                        consistencySummary = consistencySummary,
                        frequencyHistory = frequencyHistory,
                        workoutTimestamps = workoutTimestamps,
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
                        selectedExerciseId = selectedExerciseId,
                        selectedExerciseName = selectedExerciseName,
                        strengthHistory = strengthHistory,
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

    fun selectExercise(exerciseId: String) {
        val exercise = _uiState.value.exerciseEvolutions.find { it.exerciseId == exerciseId }
        val exerciseName = exercise?.exerciseName ?: exerciseId
        viewModelScope.launch {
            try {
                val strengthPoints = performanceRepository?.getExerciseStrengthHistory(exerciseId) ?: emptyList()
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
