package com.example.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TemplateDetailsViewModel(
    private val repository: WorkoutRepository
) : ViewModel() {

    private val _templateId = MutableStateFlow<Long>(-1L)

    fun load(templateId: Long) {
        _templateId.value = templateId
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val template: StateFlow<WorkoutTemplateEntity?> = _templateId
        .flatMapLatest { id ->
            if (id != -1L) flowOf(repository.getTemplate(id)) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val exercises: StateFlow<List<TemplateExerciseWithDetails>> = _templateId
        .flatMapLatest { id ->
            if (id != -1L) repository.getTemplateExercises(id) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExercises = repository.activeExercises
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addExerciseToTemplate(exerciseId: Long) {
        addExercisesToTemplate(listOf(exerciseId))
    }

    fun addExercisesToTemplate(exerciseIds: List<Long>) {
        val id = _templateId.value
        if (id == -1L || exerciseIds.isEmpty()) return
        val currentSort = exercises.value.size
        viewModelScope.launch {
            exerciseIds.forEachIndexed { index, exerciseId ->
                repository.addExerciseToTemplate(id, exerciseId, currentSort + index)
            }
        }
    }

    fun updateExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        viewModelScope.launch {
            repository.updateTemplateExerciseFull(templateExercise)
        }
    }

    fun removeExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        viewModelScope.launch {
            repository.removeExerciseFromTemplate(templateExercise)
        }
    }
}
