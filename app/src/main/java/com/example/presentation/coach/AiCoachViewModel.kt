package com.example.presentation.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachResult
import com.example.domain.ai.model.AiRecommendation
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.usecase.AnalyzeWorkoutUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordena o Coach IA para a tela.
 *
 * ```
 * AnalyzeWorkoutUseCase -> AiCoachViewModel -> AiCoachUiState -> AiCoachScreen
 * ```
 *
 * Nenhuma chamada acontece no `init`: o Coach só fala quando o usuário pede. Enquanto uma
 * análise está em andamento, novos toques (ou uma recomposição que reemita o evento) são
 * ignorados — o provider é chamado uma vez por pedido.
 *
 * O ViewModel não conhece Firebase, não lê DAO e não interpreta JSON.
 */
class AiCoachViewModel(
    private val analyzeWorkout: AnalyzeWorkoutUseCase,
    /** Resolve o nome de exibição de um `exerciseId`; a identidade continua sendo o id. */
    private val exerciseNameResolver: suspend (String) -> String? = { null }
) : ViewModel() {

    private val _uiState = MutableStateFlow<AiCoachUiState>(AiCoachUiState.Idle)
    val uiState: StateFlow<AiCoachUiState> = _uiState.asStateFlow()

    private var inFlight: Job? = null

    /** Único gatilho de chamada ao provider: o toque explícito em "Analisar meu treino". */
    fun analyze() {
        if (inFlight?.isActive == true) return

        _uiState.value = AiCoachUiState.Loading
        inFlight = viewModelScope.launch {
            _uiState.value = when (val result = analyzeWorkout()) {
                is AiCoachResult.Success -> AiCoachUiState.Success(
                    summary = result.advice.summary,
                    recommendations = result.advice.recommendations.map { it.toUi() }
                )

                is AiCoachResult.Failure -> result.toUiState()
            }
        }
    }

    private suspend fun AiRecommendation.toUi(): AiRecommendationUi = AiRecommendationUi(
        type = type,
        label = type.label(),
        exerciseName = exerciseId?.let { exerciseNameResolver(it) },
        reason = reason,
        confidencePercent = (confidence * 100).toInt().coerceIn(0, 100)
    )

    private fun AiCoachResult.Failure.toUiState(): AiCoachUiState = when (kind) {
        AiCoachErrorKind.UNAVAILABLE -> AiCoachUiState.Unavailable(
            "O Coach IA ainda não está disponível neste aparelho. O restante do Spark continua " +
                "funcionando normalmente."
        )

        AiCoachErrorKind.NETWORK -> AiCoachUiState.Unavailable(
            "O Coach IA precisa de internet. Seus treinos, histórico e execução continuam " +
                "funcionando offline."
        )

        AiCoachErrorKind.RATE_LIMITED -> AiCoachUiState.Error(
            message = "O Coach atingiu o limite de uso. Tente novamente mais tarde.",
            canRetry = false
        )

        AiCoachErrorKind.TIMEOUT -> AiCoachUiState.Error(
            message = "O Coach demorou demais para responder.",
            canRetry = true
        )

        AiCoachErrorKind.INVALID_RESPONSE -> AiCoachUiState.Error(
            message = "A resposta do Coach não passou na validação e foi descartada. " +
                "Nada no seu treino foi alterado.",
            canRetry = true
        )

        AiCoachErrorKind.PROVIDER -> AiCoachUiState.Error(
            message = "O Coach falhou ao responder.",
            canRetry = true
        )
    }

    private fun AiRecommendationType.label(): String = when (this) {
        AiRecommendationType.GENERAL -> "Observação geral"
        AiRecommendationType.KEEP_CURRENT_PLAN -> "Manter o plano atual"
        AiRecommendationType.REVIEW_LOAD -> "Revisar carga"
        AiRecommendationType.REVIEW_VOLUME -> "Revisar volume"
    }
}
