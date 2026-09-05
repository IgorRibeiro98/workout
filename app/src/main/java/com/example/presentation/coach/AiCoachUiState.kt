package com.example.presentation.coach

import com.example.domain.ai.model.AiDataQualityLevel
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
        val positiveSignals: List<AiObservationUi>,
        val attentionPoints: List<AiObservationUi>,
        val recommendations: List<AiRecommendationUi>,
        val dataQuality: AiDataQualityUi
    ) : AiCoachUiState

    /** O Coach não está disponível, mas o resto do Spark continua funcionando normalmente. */
    data class Unavailable(val message: String) : AiCoachUiState

    data class Error(val message: String, val canRetry: Boolean) : AiCoachUiState
}

/** Um fato observado, pronto para render. */
data class AiObservationUi(
    val title: String,
    val description: String,
    val exerciseName: String?
)

/** Uma sugestão pronta para render. Continua sendo sugestão: a tela não aplica nada. */
data class AiRecommendationUi(
    val type: AiRecommendationType,
    val label: String,
    val exerciseName: String?,
    val reason: String,
    val evidence: String?,
    val confidencePercent: Int
)

/**
 * A base da análise, para o usuário saber o peso do que está lendo.
 *
 * [sessionsAnalyzed] é contagem do app, não número escrito pelo modelo.
 */
data class AiDataQualityUi(
    val level: AiDataQualityLevel,
    val label: String,
    val description: String,
    val sessionsAnalyzed: Int
)
