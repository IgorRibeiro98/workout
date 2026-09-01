package com.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.datastore.SettingsManager
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import com.example.feature.evolution.EvolutionViewModel
import com.example.presentation.body.BodyEvolutionViewModel
import com.example.presentation.exercises.ExercisesViewModel
import com.example.presentation.today.TodayViewModel
import com.example.presentation.workouts.WorkoutsViewModel
import com.example.presentation.execution.ExecutionViewModel
import com.example.presentation.history.HistoryViewModel
import com.example.service.WorkoutNotificationManager

class MainViewModelFactory(
    private val repository: WorkoutRepository,
    private val settingsManager: SettingsManager,
    private val workoutEngine: WorkoutEngine,
    private val notificationManager: WorkoutNotificationManager,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase? = null,
    private val evolutionRepository: EvolutionRepository? = null,
    private val performanceRepository: PerformanceRepository? = null,
    private val consistencyRepository: com.example.domain.evolution.repository.ConsistencyRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EvolutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val useCase = getEvolutionSummaryUseCase 
                ?: throw IllegalStateException("GetEvolutionSummaryUseCase not provided")
            val repository = evolutionRepository
                ?: throw IllegalStateException("EvolutionRepository not provided")
            return EvolutionViewModel(useCase, repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.consistency.ConsistencyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = consistencyRepository
                ?: throw IllegalStateException("ConsistencyRepository not provided")
            return com.example.feature.evolution.consistency.ConsistencyViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.performance.PerformanceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = performanceRepository
                ?: throw IllegalStateException("PerformanceRepository not provided")
            return com.example.feature.evolution.performance.PerformanceViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.performance.chart.PerformanceChartViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            val repository = performanceRepository
                ?: throw IllegalStateException("PerformanceRepository not provided")
            return com.example.feature.evolution.performance.chart.PerformanceChartViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(BodyEvolutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BodyEvolutionViewModel(bodyMeasurementRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.feature.evolution.body.BodyEvolutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.feature.evolution.body.BodyEvolutionViewModel(bodyMeasurementRepository) as T
        }
        if (modelClass.isAssignableFrom(ExercisesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExercisesViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(WorkoutsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(TodayViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TodayViewModel(repository, settingsManager, workoutEngine) as T
        }
        if (modelClass.isAssignableFrom(ExecutionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ExecutionViewModel(workoutEngine, notificationManager, settingsManager) as T
        }
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(workoutEngine) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.execution.SummaryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.execution.SummaryViewModel(workoutEngine) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.workouts.TemplateDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.workouts.TemplateDetailsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.exercises.ExerciseDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.exercises.ExerciseDetailsViewModel(workoutEngine, repository.dao, settingsManager) as T
        }
        if (modelClass.isAssignableFrom(com.example.presentation.workouts.ProgramDetailsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.example.presentation.workouts.ProgramDetailsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
