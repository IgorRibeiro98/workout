package com.example.presentation.coach

import androidx.lifecycle.viewModelScope
import com.example.domain.ai.model.AiCoachExplanationResult
import com.example.domain.ai.model.AiCoachExplanationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * O que a folha de explicação está mostrando.
 *
 * `Hidden` é o estado normal da tela: a explicação só existe depois de um toque explícito, nunca
 * ao abrir e nunca em recomposição.
 */
sealed interface CoachExplanationUiState {

    data object Hidden : CoachExplanationUiState

    data object Loading : CoachExplanationUiState

    data class Ready(
        val title: String,
        val explanation: String,
        /** Os dados que o app realmente usou. Sempre montado pelo app. */
        val evidenceItems: List<String>,
        val limitations: List<String>,
        /** Se o texto foi escrito pelo Coach IA ou montado localmente pelo Spark. */
        val fromModel: Boolean
    ) : CoachExplanationUiState

    /** O alvo sumiu ou o contexto mudou. Não é erro de infraestrutura, é estado do app. */
    data class Message(val text: String) : CoachExplanationUiState
}

/**
 * O controle de uma explicação contextual, compartilhado pelos ViewModels que a oferecem.
 *
 * Existe para as quatro telas terem exatamente o mesmo comportamento de custo sem copiar código:
 *
 * - nenhuma chamada acontece sem [request];
 * - enquanto uma explicação está em andamento, novos toques são ignorados;
 * - fechar a folha não dispara nada.
 */
class CoachExplanationController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<CoachExplanationUiState>(CoachExplanationUiState.Hidden)
    val state: StateFlow<CoachExplanationUiState> = _state.asStateFlow()

    private var inFlight: Job? = null

    /**
     * Distingue "esta explicação ainda interessa" de "o usuário já fechou".
     *
     * Fechar a folha não cancela o trabalho em curso — a resposta ainda entra no cache e a
     * chamada já foi paga —, mas o resultado não pode reabrir uma folha que o usuário fechou.
     */
    private var generation: Int = 0

    /**
     * O único gatilho de explicação.
     *
     * O segundo toque durante uma explicação em andamento não vira uma segunda chamada: ele
     * simplesmente não faz nada.
     */
    fun request(block: suspend () -> AiCoachExplanationResult) {
        if (inFlight?.isActive == true) return

        val requested = ++generation
        _state.value = CoachExplanationUiState.Loading
        inFlight = scope.launch {
            val result = block().toUiState()
            if (generation == requested) {
                _state.value = result
            }
        }
    }

    /**
     * Fechar a folha.
     *
     * Nunca dispara chamada e nunca duplica uma: fechar durante o carregamento apenas descarta o
     * resultado que estava a caminho, e o guarda de requisição em curso continua valendo.
     */
    fun dismiss() {
        generation++
        _state.value = CoachExplanationUiState.Hidden
    }

    private fun AiCoachExplanationResult.toUiState(): CoachExplanationUiState = when (this) {
        is AiCoachExplanationResult.Success -> CoachExplanationUiState.Ready(
            title = explanation.title,
            explanation = explanation.explanation,
            evidenceItems = explanation.evidenceItems,
            limitations = explanation.limitations,
            fromModel = explanation.source == AiCoachExplanationSource.MODEL
        )

        AiCoachExplanationResult.TargetNotFound -> CoachExplanationUiState.Message(
            "Essa sugestão não está mais disponível. Peça uma nova para ver a explicação atual."
        )

        AiCoachExplanationResult.StaleContext -> CoachExplanationUiState.Message(
            "O treino mudou depois que essa sugestão foi gerada, então ela não descreve mais o " +
                "estado atual. Peça uma nova adaptação."
        )

        is AiCoachExplanationResult.Failure -> CoachExplanationUiState.Message(
            "Não foi possível montar a explicação agora." + (detail?.let { " ($it)" } ?: "")
        )
    }
}

/** Atalho para os ViewModels: o controlador vive no escopo do próprio ViewModel. */
internal fun androidx.lifecycle.ViewModel.coachExplanationController(): CoachExplanationController =
    CoachExplanationController(viewModelScope)
