package com.example.domain.ai

import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiRecommendation
import com.example.domain.ai.model.AiRecommendationType

/** O que a validação decidiu sobre a resposta do modelo. */
sealed interface AiCoachValidation {
    data class Valid(val advice: AiCoachAdvice) : AiCoachValidation
    data class Invalid(val reason: String) : AiCoachValidation
}

/**
 * Structured output também é entrada não confiável.
 *
 * Política desta fase: **uma violação invalida a resposta inteira**. Nada é adivinhado, nada é
 * corrigido por aproximação e nenhum `exerciseId` desconhecido é resolvido por nome — um id que
 * o app não enviou é invenção do modelo, e invenção não vira sugestão.
 */
object AiCoachResponseValidator {

    /** Resumo longo demais é sinal de resposta fora do contrato, não de análise rica. */
    const val MAX_SUMMARY_LENGTH: Int = 800

    /** Uma justificativa é uma frase, não um ensaio. */
    const val MAX_REASON_LENGTH: Int = 400

    /** Teto de sugestões por resposta nesta fase. */
    const val MAX_RECOMMENDATIONS: Int = 5

    fun validate(
        requestId: String,
        context: AiCoachContext,
        response: AiCoachResponse
    ): AiCoachValidation {
        val summary = response.summary.trim()
        if (summary.isEmpty()) {
            return AiCoachValidation.Invalid("summary vazio")
        }
        if (summary.length > MAX_SUMMARY_LENGTH) {
            return AiCoachValidation.Invalid("summary excede $MAX_SUMMARY_LENGTH caracteres")
        }
        if (response.recommendations.size > MAX_RECOMMENDATIONS) {
            return AiCoachValidation.Invalid("mais de $MAX_RECOMMENDATIONS recomendações")
        }

        val knownExerciseIds = context.knownExerciseIds
        val recommendations = mutableListOf<AiRecommendation>()

        response.recommendations.forEachIndexed { index, raw ->
            val type = AiRecommendationType.entries.firstOrNull { it.name == raw.type.trim() }
                ?: return AiCoachValidation.Invalid("tipo desconhecido em [$index]: '${raw.type}'")

            val reason = raw.reason.trim()
            if (reason.isEmpty()) {
                return AiCoachValidation.Invalid("reason vazio em [$index]")
            }
            if (reason.length > MAX_REASON_LENGTH) {
                return AiCoachValidation.Invalid("reason excede $MAX_REASON_LENGTH caracteres em [$index]")
            }

            val confidence = raw.confidence
            if (confidence.isNaN() || confidence.isInfinite()) {
                return AiCoachValidation.Invalid("confidence não numérico em [$index]")
            }
            if (confidence < 0.0 || confidence > 1.0) {
                return AiCoachValidation.Invalid("confidence fora de 0..1 em [$index]: $confidence")
            }

            val exerciseId = raw.exerciseId?.trim()?.takeIf { it.isNotEmpty() }
            if (exerciseId != null && exerciseId !in knownExerciseIds) {
                return AiCoachValidation.Invalid("exerciseId fora do contexto em [$index]: '$exerciseId'")
            }

            recommendations += AiRecommendation(
                type = type,
                exerciseId = exerciseId,
                reason = reason,
                confidence = confidence
            )
        }

        return AiCoachValidation.Valid(
            AiCoachAdvice(
                requestId = requestId,
                summary = summary,
                recommendations = recommendations
            )
        )
    }
}
