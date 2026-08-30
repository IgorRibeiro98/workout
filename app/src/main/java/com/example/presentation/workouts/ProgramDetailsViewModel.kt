package com.example.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProgramDetailsViewModel(
    private val repository: WorkoutRepository
) : ViewModel() {

    private val _programId = MutableStateFlow<Long?>(null)

    fun loadProgram(id: Long) {
        _programId.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val program: StateFlow<WorkoutProgramEntity?> = _programId.flatMapLatest { id ->
        if (id != null) {
            repository.allPrograms.map { list -> list.find { it.id == id } }
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        
    @OptIn(ExperimentalCoroutinesApi::class)
    val templates: StateFlow<List<WorkoutTemplateEntity>> = _programId.flatMapLatest { id ->
        if (id != null) repository.getTemplatesForProgram(id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createTemplate(name: String, shortId: String, dayOfWeek: String? = null) {
        val id = _programId.value ?: return
        val currentSize = templates.value.size
        viewModelScope.launch {
            repository.addTemplate(id, name, shortId, currentSize, dayOfWeek)
        }
    }

    fun deleteTemplate(template: WorkoutTemplateEntity) {
        viewModelScope.launch {
            repository.deleteTemplate(template)
        }
    }
    
    fun setCurrentProgram() {
        val id = _programId.value ?: return
        viewModelScope.launch {
            repository.setCurrentProgram(id)
        }
    }
}
