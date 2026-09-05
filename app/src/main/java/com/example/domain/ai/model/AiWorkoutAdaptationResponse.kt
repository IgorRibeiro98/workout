package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Resposta crua de uma adaptação, já desserializada do structured output.
 *
 * Isto é **proposta**, não domínio. `type` é `String` de propósito, para que um valor fora do
 * conjunto permitido vire falha determinística de validação e não exceção de desserialização.
 */
@Serializable
data class AiWorkoutAdaptationResponse(
    val summary: String = "",
    /** Pode ser vazia: "não há o que mudar" é uma resposta legítima. */
    val changes: List<AiWorkoutAdaptationChangeResponse> = emptyList(),
    val dataQuality: AiCoachResponseDataQuality? = null
)

/**
 * Uma mudança proposta, exatamente como o modelo a escreveu.
 *
 * Os campos são específicos por tipo e todos anuláveis: o validador exige que **apenas** os
 * campos do tipo declarado venham preenchidos, para não existir proposta ambígua.
 */
@Serializable
data class AiWorkoutAdaptationChangeResponse(
    val type: String = "",
    /** exerciseId de um exercício que já está no treino. */
    val exerciseId: String = "",

    val currentWeightKg: Double? = null,
    val suggestedWeightKg: Double? = null,

    val currentSets: Int? = null,
    val suggestedSets: Int? = null,

    val currentMinReps: Int? = null,
    val currentMaxReps: Int? = null,
    val suggestedMinReps: Int? = null,
    val suggestedMaxReps: Int? = null,

    val currentRestSeconds: Int? = null,
    val suggestedRestSeconds: Int? = null,

    /** Só para REPLACE_EXERCISE, e só entre os candidatos enviados. */
    val replacementExerciseId: String? = null,

    val reason: String = "",
    val evidence: String = "",
    val confidence: Double = -1.0
)

/** Resultado bruto do provider para uma adaptação, antes da validação semântica. */
sealed interface AiWorkoutAdaptationGatewayResult {
    data class Success(val response: AiWorkoutAdaptationResponse) : AiWorkoutAdaptationGatewayResult
    data class Error(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AiWorkoutAdaptationGatewayResult
}
