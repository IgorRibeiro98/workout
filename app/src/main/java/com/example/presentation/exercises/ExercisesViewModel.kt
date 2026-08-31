package com.example.presentation.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ExerciseEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExercisesViewModel(private val repository: WorkoutRepository) : ViewModel() {
    
    val exercises: StateFlow<List<com.example.domain.model.ResolvedExercise>> = repository.activeResolvedExercises
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    fun addExercise(name: String, muscle: String, equipment: String? = null) {
        viewModelScope.launch {
            repository.addExercise(name, muscle, equipment)
        }
    }

    fun deleteExercise(exercise: ExerciseEntity) {
        viewModelScope.launch {
            repository.deleteExercise(exercise)
        }
    }
}
