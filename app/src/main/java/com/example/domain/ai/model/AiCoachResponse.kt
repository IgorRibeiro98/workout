package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Resposta crua do modelo, já desserializada do structured output.
 *
 * Nada aqui é confiável ainda: `type` e `level` são `String` de propósito para que um valor fora
 * do conjunto permitido vire falha de validação determinística, e não exceção de
 * desserialização.
 */
@Serializable
data class AiCoachResponse(
    val summary: String = "",
    /** O que os dados mostram de bom. Observação, não conselho. */
    val positiveSignals: List<AiCoachResponseObservation> = emptyList(),
    /** O que merece olhar. Observação, não diagnóstico. */
    val attentionPoints: List<AiCoachResponseObservation> = emptyList(),
    val recommendations: List<AiCoachResponseRecommendation> = emptyList(),
    val dataQuality: AiCoachResponseDataQuality? = null
)

/** Um fato observado no contexto, como o modelo o escreveu. */
@Serializable
data class AiCoachResponseObservation(
    val exerciseId: String? = null,
    val title: String = "",
    val description: String = ""
)

/** Uma recomendação como o modelo a escreveu. Ainda não é domínio. */
@Serializable
data class AiCoachResponseRecommendation(
    val type: String = "",
    val exerciseId: String? = null,
    val reason: String = "",
    val confidence: Double = -1.0,
    /** De onde a recomendação saiu, em dados do contexto. */
    val evidence: String? = null
)

/** Quanta evidência o modelo diz ter tido. O app confere contra o que realmente enviou. */
@Serializable
data class AiCoachResponseDataQuality(
    val level: String = "",
    val description: String = ""
)
