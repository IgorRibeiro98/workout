package com.example.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import com.example.data.repository.SnapshotBuildResult
import com.example.data.repository.WorkoutRepository
import com.example.data.repository.WorkoutShareSnapshotBuilder
import com.example.data.repository.WorkoutTemplateFields
import java.time.DayOfWeek
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
        
    /** Os treinos do programa, na ordem do programa, cada um com os dias da semana dele (T19.8). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val templates: StateFlow<List<WorkoutTemplateWithSchedule>> = _programId.flatMapLatest { id ->
        if (id != null) repository.getTemplatesWithScheduleForProgram(id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ------------------------------------------------------------------ formulário (T19.8)

    private val _templateForm = MutableStateFlow<TemplateFormState?>(null)

    /**
     * O formulário de criar/editar treino, ou `null` quando fechado.
     *
     * É estado **temporário**: a autoridade sobre nome, sigla e dias é o Room, e o formulário só
     * vira dado ao salvar. A seleção de dias aqui é um conjunto — não há item "Nenhum": conjunto
     * vazio é "sem dia fixo".
     */
    val templateForm: StateFlow<TemplateFormState?> = _templateForm.asStateFlow()

    fun openCreateTemplateForm() {
        _templateForm.value = TemplateFormState(templateId = null)
    }

    /** Abre o formulário com o que está persistido **agora** para este treino. */
    fun openEditTemplateForm(template: WorkoutTemplateWithSchedule) {
        _templateForm.value = TemplateFormState(
            templateId = template.template.id,
            name = template.template.name,
            shortId = template.template.shortIdentifier.orEmpty(),
            scheduledDays = template.scheduledDays.toSet()
        )
    }

    fun dismissTemplateForm() {
        _templateForm.value = null
    }

    fun onTemplateNameChanged(name: String) {
        _templateForm.update { it?.copy(name = name) }
    }

    fun onTemplateShortIdChanged(shortId: String) {
        _templateForm.update { it?.copy(shortId = shortId) }
    }

    /** Liga/desliga um dia. Desligar o último deixa o conjunto vazio, que é um estado válido. */
    fun toggleTemplateDay(day: DayOfWeek) {
        _templateForm.update { form ->
            form?.copy(scheduledDays = if (day in form.scheduledDays) form.scheduledDays - day else form.scheduledDays + day)
        }
    }

    /**
     * Salva o formulário. A regra de obrigatoriedade é a do domínio ([WorkoutTemplateFields]): a
     * tela só marca o que o repositório recusaria. Fecha ao salvar; com erro, fica aberto mostrando
     * o campo que falta.
     */
    fun submitTemplateForm() {
        val form = _templateForm.value ?: return
        if (!form.isValid) {
            _templateForm.value = form.copy(showErrors = true)
            return
        }
        val programId = _programId.value ?: return
        val currentSize = templates.value.size
        _templateForm.value = null
        viewModelScope.launch {
            if (form.templateId == null) {
                repository.addTemplate(programId, form.name, form.shortId, currentSize, form.scheduledDays)
            } else {
                repository.updateTemplateHeader(form.templateId, form.name, form.shortId, form.scheduledDays)
            }
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
            val templates = repository.dao.getTemplatesWithScheduleForProgramSync(id).map { template ->
                template to repository.getTemplateExercisesSync(template.template.id)
            }
            _shareBuildResult.value = WorkoutShareSnapshotBuilder().buildProgramSnapshot(program, templates)
        }
    }

    fun dismissShare() {
        _shareBuildResult.value = null
    }
}

/**
 * O rascunho do formulário de treino (T19.8). `templateId == null` é criação.
 *
 * `showErrors` só liga depois de uma tentativa de salvar: o asterisco diz o que é obrigatório
 * desde o início, e o erro aparece quando o usuário tentou seguir sem preencher.
 */
data class TemplateFormState(
    val templateId: Long?,
    val name: String = "",
    val shortId: String = "",
    val scheduledDays: Set<DayOfWeek> = emptySet(),
    val showErrors: Boolean = false
) {
    val isEditing: Boolean get() = templateId != null

    /** A mesma regra do repositório: só o nome é obrigatório. */
    val isValid: Boolean get() = WorkoutTemplateFields.isValidName(name)

    val nameError: Boolean get() = showErrors && !WorkoutTemplateFields.isValidName(name)
}
