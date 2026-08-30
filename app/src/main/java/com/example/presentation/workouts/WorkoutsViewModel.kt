package com.example.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WorkoutsViewModel(private val repository: WorkoutRepository) : ViewModel() {
    
    val programs: StateFlow<List<WorkoutProgramEntity>> = repository.allPrograms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val currentProgram: StateFlow<WorkoutProgramEntity?> = repository.currentProgram
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        
    @OptIn(ExperimentalCoroutinesApi::class)
    val templatesForCurrentProgram: StateFlow<List<WorkoutTemplateEntity>> = currentProgram
        .flatMapLatest { program ->
            if (program != null) repository.getTemplatesForProgram(program.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createProgram(name: String) {
        viewModelScope.launch { repository.addProgram(name) }
    }
    
    fun setCurrentProgram(id: Long) {
        viewModelScope.launch { repository.setCurrentProgram(id) }
    }
    
    fun createTemplate(name: String, shortId: String, dayOfWeek: String? = null) {
        val programId = currentProgram.value?.id ?: return
        val currentSize = templatesForCurrentProgram.value.size
        viewModelScope.launch { 
            repository.addTemplate(programId, name, shortId, currentSize, dayOfWeek) 
        }
    }

    fun deleteProgram(program: WorkoutProgramEntity) {
        viewModelScope.launch {
            repository.deleteProgram(program)
        }
    }
}
