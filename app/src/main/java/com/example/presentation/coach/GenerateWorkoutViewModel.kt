package com.example.presentation.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.GenerateWorkoutResult
import com.example.domain.ai.model.SaveGeneratedWorkoutResult
import com.example.domain.ai.model.WorkoutGenerationPreferences
import com.example.domain.ai.model.WorkoutGoal
import com.example.domain.ai.usecase.GenerateWorkoutUseCase
import com.example.domain.ai.usecase.SaveGeneratedWorkoutUseCase
import com.example.domain.engine.MuscleGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordena a geração de treino para a tela.
 *
 * ```
 * preferências -> GenerateWorkoutUseCase -> GeneratedWorkoutDraft -> preview
 *   -> confirmação -> SaveGeneratedWorkoutUseCase -> WorkoutTemplate
 * ```
 *
 * Nenhuma chamada ao provider acontece no `init`, em recomposição ou ao mudar preferência:
 * só "Gerar treino" e "Gerar novamente" falam com o modelo, e enquanto uma chamada está em
 * andamento as demais são ignoradas — o provider é chamado uma vez por pedido.
 *
 * Mudar preferência recalcula candidatos **localmente**, lendo o catálogo do Room.
 */
class GenerateWorkoutViewModel(
    private val generateWorkout: GenerateWorkoutUseCase,
    /** A confirmação do usuário, ligada ao [SaveGeneratedWorkoutUseCase] canônico. */
    private val saveGeneratedWorkout: suspend (GeneratedWorkoutDraft) -> SaveGeneratedWorkoutResult,
    /** Recorte determinístico do catálogo para as preferências atuais. Não chama o provider. */
    private val listCandidates: suspend (WorkoutGenerationPreferences) -> List<AiCandidateExerciseContext>
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenerateWorkoutUiState())
    val uiState: StateFlow<GenerateWorkoutUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null
    private var candidatesJob: Job? = null

    fun setGoal(goal: WorkoutGoal) {
        _uiState.value = _uiState.value.copy(goal = goal)
    }

    fun setDuration(minutes: Int) {
        _uiState.value = _uiState.value.copy(
            durationMinutes = minutes.coerceIn(
                WorkoutGenerationPreferences.MIN_DURATION_MINUTES,
                WorkoutGenerationPreferences.MAX_DURATION_MINUTES
            )
        )
    }

    /** Foco é seleção estruturada e limitada: acima de alguns grupos deixa de ser foco. */
    fun toggleFocus(group: MuscleGroup) {
        val current = _uiState.value.focusMuscleGroups
        val updated = when {
            group in current -> current - group
            current.size >= AiModelConfig.MAX_FOCUS_MUSCLE_GROUPS -> current
            else -> current + group
        }
        if (updated == current) return
        _uiState.value = _uiState.value.copy(focusMuscleGroups = updated)
        refreshCandidates()
    }

    /** "Academia completa" é exclusivo: ou não há restrição, ou há uma lista de equipamentos. */
    fun toggleEquipment(equipment: EquipmentAvailability) {
        val current = _uiState.value.availableEquipment
        val updated = when {
            equipment == EquipmentAvailability.FULL_GYM -> setOf(EquipmentAvailability.FULL_GYM)
            equipment in current -> (current - equipment).ifEmpty { setOf(EquipmentAvailability.FULL_GYM) }
            else -> current - EquipmentAvailability.FULL_GYM + equipment
        }
        if (updated == current) return
        _uiState.value = _uiState.value.copy(availableEquipment = updated)
        refreshCandidates()
    }

    /** Exclusões sempre por `exerciseId`; o nome nunca é identidade. */
    fun toggleExclusion(exerciseId: String) {
        val current = _uiState.value.excludedExerciseIds
        _uiState.value = _uiState.value.copy(
            excludedExerciseIds = if (exerciseId in current) current - exerciseId else current + exerciseId
        )
    }

    fun setNotes(notes: String) {
        _uiState.value = _uiState.value.copy(
            notes = notes.take(WorkoutGenerationPreferences.MAX_NOTES_LENGTH)
        )
    }

    /** Carrega os candidatos da configuração atual. Leitura local; nenhuma chamada ao modelo. */
    fun refreshCandidates() {
        candidatesJob?.cancel()
        candidatesJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.focusMuscleGroups.isEmpty()) {
                _uiState.value = _uiState.value.copy(candidates = emptyList())
                return@launch
            }
            // Sem exclusões: a tela precisa listar também o que está excluído para poder reverter.
            val candidates = listCandidates(state.preferences().copy(excludedExerciseIds = emptySet()))
            _uiState.value = _uiState.value.copy(candidates = candidates)
        }
    }

    /** Único gatilho de chamada ao provider, junto com "Gerar novamente". */
    fun generate() {
        if (inFlight?.isActive == true) return
        val state = _uiState.value
        if (!state.canGenerate) return

        _uiState.value = state.copy(status = GenerateWorkoutStatus.Generating)
        inFlight = viewModelScope.launch {
            val result = generateWorkout(state.preferences())
            _uiState.value = _uiState.value.copy(
                status = when (result) {
                    is GenerateWorkoutResult.Success -> GenerateWorkoutStatus.Draft(result.draft)

                    GenerateWorkoutResult.InsufficientCandidates -> GenerateWorkoutStatus.Message(
                        text = "Os exercícios disponíveis para esse foco e esses equipamentos não " +
                            "dão para montar um treino. Amplie o foco, libere mais equipamentos ou " +
                            "remova exclusões.",
                        isWarning = true
                    )

                    is GenerateWorkoutResult.Failure -> result.toStatus()
                }
            )
        }
    }

    /** O usuário descarta a proposta: o rascunho deixa de existir e nada foi persistido. */
    fun discardDraft() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(status = GenerateWorkoutStatus.Idle)
    }

    /**
     * Remove um exercício do rascunho, sem tocar em nada persistido.
     *
     * A ordem é recompactada porque o rascunho segue o mesmo contrato do editor: posições
     * contíguas a partir de zero.
     */
    fun removeExerciseFromDraft(exerciseId: String) {
        val status = _uiState.value.status as? GenerateWorkoutStatus.Draft ?: return
        val remaining = status.draft.exercises.filterNot { it.exerciseId == exerciseId }
        if (remaining.size == status.draft.exercises.size) return
        if (remaining.isEmpty()) {
            _uiState.value = _uiState.value.copy(status = GenerateWorkoutStatus.Idle)
            return
        }
        _uiState.value = _uiState.value.copy(
            status = GenerateWorkoutStatus.Draft(
                status.draft.copy(
                    exercises = remaining.mapIndexed { index, exercise -> exercise.copy(sortOrder = index) }
                )
            )
        )
    }

    /** A confirmação explícita. É o único caminho que escreve alguma coisa. */
    fun save() {
        if (inFlight?.isActive == true) return
        val status = _uiState.value.status as? GenerateWorkoutStatus.Draft ?: return

        _uiState.value = _uiState.value.copy(status = GenerateWorkoutStatus.Saving(status.draft))
        inFlight = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                status = when (val result = saveGeneratedWorkout(status.draft)) {
                    is SaveGeneratedWorkoutResult.Saved ->
                        GenerateWorkoutStatus.Saved(result.templateId, result.name)

                    is SaveGeneratedWorkoutResult.Failure -> GenerateWorkoutStatus.Message(
                        text = result.reason,
                        canRetry = false
                    )
                }
            )
        }
    }

    /** Volta ao formulário depois de salvar ou de um recado. */
    fun reset() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(status = GenerateWorkoutStatus.Idle)
    }

    private fun GenerateWorkoutResult.Failure.toStatus(): GenerateWorkoutStatus.Message = when (kind) {
        AiCoachErrorKind.UNAVAILABLE -> GenerateWorkoutStatus.Message(
            text = if (!detail.isNullOrBlank()) {
                "O Coach IA não está disponível: $detail"
            } else {
                "O Coach IA ainda não está disponível neste aparelho. Você continua criando " +
                    "treinos manualmente normalmente."
            },
            isWarning = true
        )

        AiCoachErrorKind.NETWORK -> GenerateWorkoutStatus.Message(
            text = "Gerar treino com IA precisa de internet. Criar treino manualmente continua " +
                "funcionando offline.",
            isWarning = true
        )

        AiCoachErrorKind.RATE_LIMITED -> GenerateWorkoutStatus.Message(
            text = "O Coach atingiu o limite de uso. Tente novamente mais tarde." +
                (detail?.let { " ($it)" } ?: ""),
            canRetry = false
        )

        AiCoachErrorKind.TIMEOUT -> GenerateWorkoutStatus.Message(
            text = "O Coach demorou demais para responder. Nada foi criado.",
            canRetry = true
        )

        AiCoachErrorKind.INVALID_RESPONSE -> GenerateWorkoutStatus.Message(
            text = "A proposta do Coach não passou na validação e foi descartada. Nenhum treino " +
                "foi criado." + (detail?.let { " Detalhes: $it" } ?: ""),
            canRetry = true
        )

        AiCoachErrorKind.PROVIDER -> GenerateWorkoutStatus.Message(
            text = "O Coach falhou ao responder." + (detail?.let { " ($it)" } ?: ""),
            canRetry = true
        )
    }
}
