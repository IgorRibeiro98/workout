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

import com.example.domain.engine.ExerciseResolver
import kotlinx.coroutines.flow.combine

data class ResolvedTemplateExercise(
    val templateExercise: com.example.data.local.WorkoutTemplateExerciseEntity,
    val resolvedExercise: com.example.domain.model.ResolvedExercise
)

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
    val exercises: StateFlow<List<ResolvedTemplateExercise>> = _templateId
        .flatMapLatest { id ->
            if (id != -1L) {
                combine(
                    repository.getTemplateExercises(id),
                    repository.allOverridesFlow
                ) { templateExs, overrides ->
                    val overrideMap = overrides.associateBy { it.exerciseId }
                    templateExs.map { te ->
                        ResolvedTemplateExercise(
                            templateExercise = te.templateExercise,
                            resolvedExercise = ExerciseResolver.resolve(te.exercise, overrideMap[te.exercise.id])
                        )
                    }
                }
            } else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExercises = repository.activeResolvedExercises
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

    fun moveExercise(fromIndex: Int, toIndex: Int) {
        val currentList = exercises.value
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices || fromIndex == toIndex) return
        val mutable = currentList.map { it.templateExercise }.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        val updated = mutable.mapIndexed { index, entity ->
            entity.copy(sortOrder = index)
        }
        viewModelScope.launch {
            repository.updateTemplateExercises(updated)
        }
    }
}
