package com.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.datastore.SettingsManager
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
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
    private val bodyMeasurementRepository: BodyMeasurementRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BodyEvolutionViewModel::class.java)) {
            val bodyRepo = bodyMeasurementRepository ?: BodyMeasurementRepository(repository.dao as? com.example.data.local.BodyMeasurementDao ?: throw IllegalStateException("BodyMeasurementDao not available"))
            @Suppress("UNCHECKED_CAST")
            return BodyEvolutionViewModel(bodyRepo) as T
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
