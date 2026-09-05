package com.example.domain.ai

import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiCoachObservation
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseObservation
import com.example.domain.ai.model.AiDataQualityLevel
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
 *
 * A T14.1 acrescenta três regras, todas da mesma família:
 *
 * - recomendação que depende de exercício precisa dizer **qual** exercício;
 * - recomendação que aponta para um exercício precisa dizer **de onde** saiu (`evidence`);
 * - a resposta não pode declarar mais evidência do que o app enviou (`dataQuality`).
 */
object AiCoachResponseValidator {

    /** Resumo longo demais é sinal de resposta fora do contrato, não de análise rica. */
    const val MAX_SUMMARY_LENGTH: Int = 800

    /** Uma justificativa é uma frase, não um ensaio. */
    const val MAX_REASON_LENGTH: Int = 400

    /** A evidência é o dado citado, não a análise inteira de novo. */
    const val MAX_EVIDENCE_LENGTH: Int = 240

    /** Título de observação é rótulo curto. */
    const val MAX_TITLE_LENGTH: Int = 80

    /** Descrição de observação é uma frase. */
    const val MAX_DESCRIPTION_LENGTH: Int = 400

    /** Teto de sugestões por resposta nesta fase. */
    const val MAX_RECOMMENDATIONS: Int = 5

    /** Teto de observações por seção. */
    const val MAX_OBSERVATIONS: Int = 5

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

        val positiveSignals = when (
            val result = validateObservations("positiveSignals", response.positiveSignals, knownExerciseIds)
        ) {
            is ObservationsResult.Invalid -> return AiCoachValidation.Invalid(result.reason)
            is ObservationsResult.Valid -> result.observations
        }

        val attentionPoints = when (
            val result = validateObservations("attentionPoints", response.attentionPoints, knownExerciseIds)
        ) {
            is ObservationsResult.Invalid -> return AiCoachValidation.Invalid(result.reason)
            is ObservationsResult.Valid -> result.observations
        }

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
            if (exerciseId == null && type.requiresExercise) {
                return AiCoachValidation.Invalid("${type.name} exige exerciseId em [$index]")
            }

            val evidence = raw.evidence?.trim()?.takeIf { it.isNotEmpty() }
            if (exerciseId != null && evidence == null) {
                return AiCoachValidation.Invalid("recomendação sobre exercício sem evidence em [$index]")
            }
            if (evidence != null && evidence.length > MAX_EVIDENCE_LENGTH) {
                return AiCoachValidation.Invalid("evidence excede $MAX_EVIDENCE_LENGTH caracteres em [$index]")
            }

            recommendations += AiRecommendation(
                type = type,
                exerciseId = exerciseId,
                reason = reason,
                confidence = confidence,
                evidence = evidence
            )
        }

        val dataQuality = when (val result = validateDataQuality(context, response)) {
            is DataQualityResult.Invalid -> return AiCoachValidation.Invalid(result.reason)
            is DataQualityResult.Valid -> result.dataQuality
        }

        return AiCoachValidation.Valid(
            AiCoachAdvice(
                requestId = requestId,
                summary = summary,
                positiveSignals = positiveSignals,
                attentionPoints = attentionPoints,
                recommendations = recommendations,
                dataQuality = dataQuality,
                sessionsAnalyzed = context.evidence.sessionsAnalyzed
            )
        )
    }

    private sealed interface ObservationsResult {
        data class Valid(val observations: List<AiCoachObservation>) : ObservationsResult
        data class Invalid(val reason: String) : ObservationsResult
    }

    private fun validateObservations(
        field: String,
        raw: List<AiCoachResponseObservation>,
        knownExerciseIds: Set<String>
    ): ObservationsResult {
        if (raw.size > MAX_OBSERVATIONS) {
            return ObservationsResult.Invalid("mais de $MAX_OBSERVATIONS itens em $field")
        }

        val observations = mutableListOf<AiCoachObservation>()
        raw.forEachIndexed { index, item ->
            val title = item.title.trim()
            if (title.isEmpty()) {
                return ObservationsResult.Invalid("title vazio em $field[$index]")
            }
            if (title.length > MAX_TITLE_LENGTH) {
                return ObservationsResult.Invalid("title excede $MAX_TITLE_LENGTH caracteres em $field[$index]")
            }

            val description = item.description.trim()
            if (description.isEmpty()) {
                return ObservationsResult.Invalid("description vazia em $field[$index]")
            }
            if (description.length > MAX_DESCRIPTION_LENGTH) {
                return ObservationsResult.Invalid(
                    "description excede $MAX_DESCRIPTION_LENGTH caracteres em $field[$index]"
                )
            }

            val exerciseId = item.exerciseId?.trim()?.takeIf { it.isNotEmpty() }
            if (exerciseId != null && exerciseId !in knownExerciseIds) {
                return ObservationsResult.Invalid("exerciseId fora do contexto em $field[$index]: '$exerciseId'")
            }

            observations += AiCoachObservation(
                exerciseId = exerciseId,
                title = title,
                description = description
            )
        }
        return ObservationsResult.Valid(observations)
    }

    private sealed interface DataQualityResult {
        data class Valid(val dataQuality: AiCoachDataQuality) : DataQualityResult
        data class Invalid(val reason: String) : DataQualityResult
    }

    private fun validateDataQuality(
        context: AiCoachContext,
        response: AiCoachResponse
    ): DataQualityResult {
        val raw = response.dataQuality
            ?: return DataQualityResult.Invalid("dataQuality ausente")

        val level = AiDataQualityLevel.entries.firstOrNull { it.name == raw.level.trim() }
            ?: return DataQualityResult.Invalid("dataQuality desconhecido: '${raw.level}'")

        // O teto foi calculado por AiDataQualityPolicy quando o contexto foi montado. O modelo
        // pode ser mais conservador do que o app; nunca mais confiante.
        val ceiling = context.evidence.maxDataQuality
        if (level.ordinal > ceiling.ordinal) {
            return DataQualityResult.Invalid(
                "dataQuality ${level.name} acima da evidência enviada (${ceiling.name})"
            )
        }

        val description = raw.description.trim()
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            return DataQualityResult.Invalid("dataQuality.description excede $MAX_DESCRIPTION_LENGTH caracteres")
        }

        return DataQualityResult.Valid(AiCoachDataQuality(level = level, description = description))
    }
}
