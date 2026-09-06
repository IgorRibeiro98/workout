package com.example.presentation.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.ai.model.AdaptWorkoutResult
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.ApplyWorkoutAdaptationResult
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.usecase.AdaptWorkoutUseCase
import com.example.domain.ai.usecase.ExplainCoachDecisionUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordena a adaptação de um treino para a tela.
 *
 * ```
 * AdaptWorkoutUseCase -> WorkoutAdaptationDraft -> seleção do usuário
 *   -> ApplyWorkoutAdaptationUseCase -> WorkoutTemplate
 * ```
 *
 * Nenhuma chamada acontece no `init`, ao abrir a tela ou em recomposição: só "Adaptar meu treino"
 * fala com o modelo, e enquanto uma chamada está em andamento as demais são ignoradas.
 *
 * Selecionar e desmarcar mudanças não custa nada — é estado local. A única operação que escreve
 * é [apply], e só depois do toque explícito.
 */
class AdaptWorkoutViewModel(
    private val adaptWorkout: AdaptWorkoutUseCase,
    /** A confirmação do usuário, ligada ao `ApplyWorkoutAdaptationUseCase` canônico. */
    private val applyAdaptation: suspend (WorkoutAdaptationDraft, Set<String>) -> ApplyWorkoutAdaptationResult,
    /** `null` quando o Coach contextual não está disponível neste build. */
    private val explainCoachDecision: ExplainCoachDecisionUseCase? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdaptWorkoutUiState())
    val uiState: StateFlow<AdaptWorkoutUiState> = _uiState.asStateFlow()

    private val explanations = coachExplanationController()
    val explanationState: StateFlow<CoachExplanationUiState> = explanations.state

    private var inFlight: Job? = null

    val canExplain: Boolean get() = explainCoachDecision != null

    /**
     * "Entender sugestão" sobre uma mudança específica.
     *
     * A mudança é resolvida por id contra a proposta atual — descartá-la ou aplicá-la faz o id
     * deixar de existir. O caso de uso ainda relê o treino e recusa explicar uma proposta montada
     * sobre um estado que já mudou.
     */
    fun explainChange(changeId: String) {
        val useCase = explainCoachDecision ?: return
        val draft = (_uiState.value.status as? AdaptWorkoutStatus.Draft)?.draft ?: return
        explanations.request { useCase.explainAdaptationChange(draft, changeId) }
    }

    fun dismissExplanation() = explanations.dismiss()

    /** Diz qual treino está aberto. Não chama o provider. */
    fun load(templateId: Long) {
        if (_uiState.value.templateId == templateId) return
        _uiState.value = AdaptWorkoutUiState(templateId = templateId)
    }

    /** Único gatilho de chamada ao provider. */
    fun adapt() {
        if (inFlight?.isActive == true) return
        val state = _uiState.value
        if (!state.canAdapt) return

        explanations.dismiss()
        _uiState.value = state.copy(
            status = AdaptWorkoutStatus.Generating,
            selectedChangeIds = emptySet()
        )
        inFlight = viewModelScope.launch {
            val result = adaptWorkout(state.templateId)
            _uiState.value = _uiState.value.copy(
                status = when (result) {
                    is AdaptWorkoutResult.Success -> AdaptWorkoutStatus.Draft(result.draft)
                    is AdaptWorkoutResult.NoChanges ->
                        AdaptWorkoutStatus.NoChanges(result.summary, result.dataQuality)

                    is AdaptWorkoutResult.Failure -> result.toStatus()
                }
            )
        }
    }

    /** Aceitar ou recusar uma mudança. Estado local: nada é escrito aqui. */
    fun toggleChange(changeId: String) {
        val state = _uiState.value
        if (state.isBusy) return
        val selected = state.selectedChangeIds
        _uiState.value = state.copy(
            selectedChangeIds = if (changeId in selected) selected - changeId else selected + changeId
        )
    }

    /** Descartar a proposta inteira: o rascunho some e o treino continua como estava. */
    fun discard() {
        if (_uiState.value.isBusy) return
        explanations.dismiss()
        _uiState.value = _uiState.value.copy(
            status = AdaptWorkoutStatus.Idle,
            selectedChangeIds = emptySet()
        )
    }

    /** A confirmação explícita. É o único caminho que altera o treino. */
    fun apply() {
        if (inFlight?.isActive == true) return
        val state = _uiState.value
        val status = state.status as? AdaptWorkoutStatus.Draft ?: return
        val selected = state.selectedChangeIds.filter { id -> status.draft.changes.any { it.id == id } }.toSet()
        if (selected.isEmpty()) return

        explanations.dismiss()
        _uiState.value = state.copy(status = AdaptWorkoutStatus.Applying(status.draft))
        inFlight = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                status = when (val result = applyAdaptation(status.draft, selected)) {
                    is ApplyWorkoutAdaptationResult.Applied ->
                        AdaptWorkoutStatus.Applied(result.appliedChanges)

                    ApplyWorkoutAdaptationResult.NothingSelected -> AdaptWorkoutStatus.Draft(status.draft)

                    ApplyWorkoutAdaptationResult.StaleDraft -> AdaptWorkoutStatus.Message(
                        text = "Este treino mudou depois que a sugestão foi gerada, então nada foi " +
                            "alterado. Peça uma nova adaptação para trabalhar sobre o treino atual.",
                        canRetry = true,
                        isWarning = true
                    )

                    is ApplyWorkoutAdaptationResult.Failure -> AdaptWorkoutStatus.Message(
                        text = "Nada foi alterado no seu treino. ${result.reason}",
                        canRetry = true
                    )
                }
            )
        }
    }

    /** Volta ao início depois de aplicar ou de um recado. */
    fun reset() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(
            status = AdaptWorkoutStatus.Idle,
            selectedChangeIds = emptySet()
        )
    }

    private fun AdaptWorkoutResult.Failure.toStatus(): AdaptWorkoutStatus = when (kind) {
        // Não é falha: é o Coach online pedindo conta.
        AiCoachErrorKind.AUTH_REQUIRED -> AdaptWorkoutStatus.AuthRequired

        AiCoachErrorKind.UNAVAILABLE -> AdaptWorkoutStatus.Message(
            text = if (!detail.isNullOrBlank()) {
                "O Coach IA não está disponível: $detail"
            } else {
                "O Coach IA ainda não está disponível neste aparelho. Editar o treino à mão " +
                    "continua funcionando normalmente."
            },
            isWarning = true
        )

        AiCoachErrorKind.NETWORK -> AdaptWorkoutStatus.Message(
            text = "Adaptar com IA precisa de internet. Editar o treino à mão continua " +
                "funcionando offline.",
            isWarning = true
        )

        AiCoachErrorKind.RATE_LIMITED -> AdaptWorkoutStatus.Message(
            text = "O Coach atingiu o limite de uso. Tente novamente mais tarde." +
                (detail?.let { " ($it)" } ?: ""),
            canRetry = false
        )

        AiCoachErrorKind.TIMEOUT -> AdaptWorkoutStatus.Message(
            text = "O Coach demorou demais para responder. Nada foi alterado.",
            canRetry = true
        )

        AiCoachErrorKind.INVALID_RESPONSE -> AdaptWorkoutStatus.Message(
            text = "A sugestão do Coach não passou na validação e foi descartada. Nada no seu " +
                "treino foi alterado." + (detail?.let { " Detalhes: $it" } ?: ""),
            canRetry = true
        )

        AiCoachErrorKind.PROVIDER -> AdaptWorkoutStatus.Message(
            text = "O Coach falhou ao responder." + (detail?.let { " ($it)" } ?: ""),
            canRetry = true
        )
    }
}
