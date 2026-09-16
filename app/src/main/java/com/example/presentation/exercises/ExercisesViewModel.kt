package com.example.presentation.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.CustomExerciseFields
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExercisesViewModel(private val repository: WorkoutRepository) : ViewModel() {
    
    val exercises: StateFlow<List<com.example.domain.model.ResolvedExercise>> = repository.activeResolvedExercises
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A mesma regra que o repositório aplica: só o nome é obrigatório. */
    fun canSave(name: String): Boolean = CustomExerciseFields.isValidName(name)

    /**
     * Cria um exercício `CUSTOM`. Uma chamada com nome em branco é ignorada aqui, e não só no
     * botão: a validação da UI e a do domínio são a mesma ([CustomExerciseFields]).
     */
    fun addExercise(name: String, muscle: String? = null, equipment: String? = null, description: String? = null) {
        if (!canSave(name)) return
        viewModelScope.launch {
            repository.addExercise(name, muscle, equipment, description)
        }
    }
}
