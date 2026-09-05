package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Resposta crua do modelo, já desserializada do structured output.
 *
 * Nada aqui é confiável ainda: `type` é `String` de propósito para que um valor fora do
 * conjunto permitido vire falha de validação determinística, e não exceção de desserialização.
 */
@Serializable
data class AiCoachResponse(
    val summary: String = "",
    val recommendations: List<AiCoachResponseRecommendation> = emptyList()
)

/** Uma recomendação como o modelo a escreveu. Ainda não é domínio. */
@Serializable
data class AiCoachResponseRecommendation(
    val type: String = "",
    val exerciseId: String? = null,
    val reason: String = "",
    val confidence: Double = -1.0
)
