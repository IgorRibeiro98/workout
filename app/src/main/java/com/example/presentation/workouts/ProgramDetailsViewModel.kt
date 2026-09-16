package com.example.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.SnapshotBuildResult
import com.example.data.repository.WorkoutRepository
import com.example.data.repository.WorkoutShareSnapshotBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ProgramDetailsViewModel(
    private val repository: WorkoutRepository,
    settingsManager: com.example.data.datastore.SettingsManager
) : ViewModel() {

    /**
     * Preferência de vibração dos gestos da lista.
     *
     * Ela vem por aqui porque a tela não pode ler o `SettingsManager` do `MainApplication` direto
     * (§3): a UI consome ViewModel, e não a camada de dados.
     */
    val hapticEnabled: StateFlow<Boolean> = settingsManager.hapticEnabledFlow
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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

    // ------------------------------------------------------------------ compartilhar (T19.3)

    private val _shareBuildResult = MutableStateFlow<SnapshotBuildResult?>(null)

    /**
     * O snapshot do programa para o diálogo de compartilhar, ou o motivo de ele estar bloqueado.
     * `null` é "diálogo fechado".
     */
    val shareBuildResult: StateFlow<SnapshotBuildResult?> = _shareBuildResult.asStateFlow()

    /**
     * Monta o snapshot **aqui**, onde as entidades de treino legitimamente vivem: o programa, seus
     * treinos em ordem e os exercícios de cada um, lidos do Room neste instante. O diálogo social
     * recebe só o resultado portável — e a política de exercício CUSTOM é aplicada fail-closed
     * antes de existir qualquer oferta.
     */
    fun prepareShare() {
        val id = _programId.value ?: return
        viewModelScope.launch {
            val program = repository.getProgram(id) ?: return@launch
            val templates = repository.dao.getTemplatesForProgramSync(id).map { template ->
                template to repository.getTemplateExercisesSync(template.id)
            }
            _shareBuildResult.value = WorkoutShareSnapshotBuilder().buildProgramSnapshot(program, templates)
        }
    }

    fun dismissShare() {
        _shareBuildResult.value = null
    }
}
