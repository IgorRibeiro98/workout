package com.example.presentation.coach

import com.example.domain.ai.model.AiRecommendationType

/**
 * Estado da tela do Coach IA.
 *
 * `Unavailable` é separado de `Error` de propósito: "o Coach precisa de internet/configuração"
 * é uma mensagem diferente de "a chamada falhou, tente de novo".
 */
sealed interface AiCoachUiState {

    data object Idle : AiCoachUiState

    data object Loading : AiCoachUiState

    data class Success(
        val summary: String,
        val recommendations: List<AiRecommendationUi>
    ) : AiCoachUiState

    /** O Coach não está disponível, mas o resto do Spark continua funcionando normalmente. */
    data class Unavailable(val message: String) : AiCoachUiState

    data class Error(val message: String, val canRetry: Boolean) : AiCoachUiState
}

/** Uma sugestão pronta para render. Continua sendo sugestão: a tela não aplica nada. */
data class AiRecommendationUi(
    val type: AiRecommendationType,
    val label: String,
    val exerciseName: String?,
    val reason: String,
    val confidencePercent: Int
)
