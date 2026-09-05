package com.example.domain.ai

import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiCoachObservation
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseObservation
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiGeneratedWorkoutResponse
import com.example.domain.ai.model.AiRecommendation
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.GeneratedWorkoutDraftExercise

/** O que a validação decidiu sobre a resposta do modelo. */
sealed interface AiCoachValidation {
    data class Valid(val advice: AiCoachAdvice) : AiCoachValidation
    data class Invalid(val reason: String) : AiCoachValidation
}

/** O que a validação decidiu sobre um treino proposto pelo modelo. */
sealed interface AiGeneratedWorkoutValidation {
    data class Valid(val draft: GeneratedWorkoutDraft) : AiGeneratedWorkoutValidation
    data class Invalid(val reason: String) : AiGeneratedWorkoutValidation

    /** O modelo admitiu que os candidatos enviados não sustentam o pedido. */
    data object InsufficientCandidates : AiGeneratedWorkoutValidation
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

    // ---------------------------------------------------------------------------------------
    // Geração de treino (T14.2)
    // ---------------------------------------------------------------------------------------

    /** Nome de treino é rótulo, não frase. */
    const val MAX_WORKOUT_NAME_LENGTH: Int = 60

    /** A explicação é curta e serve à leitura; ela nunca é persistida. */
    const val MAX_EXPLANATION_LENGTH: Int = 600

    /** A justificativa de um exercício é uma frase. */
    const val MAX_EXERCISE_REASON_LENGTH: Int = 240

    /** Um treino proposto precisa de pelo menos um exercício. */
    const val MIN_GENERATED_EXERCISES: Int = 1

    /**
     * Teto de exercícios propostos.
     *
     * Reaproveita o teto que o app já declara para um treino real
     * ([AiModelConfig.MAX_EXERCISES_IN_CONTEXT]); o editor manual não tem limite próprio e nenhum
     * limite novo foi inventado aqui.
     */
    const val MAX_GENERATED_EXERCISES: Int = AiModelConfig.MAX_EXERCISES_IN_CONTEXT

    /** Faixa de séries aceitável em uma proposta. O domínio não tem limite; o contrato tem. */
    const val MIN_SETS: Int = 1
    const val MAX_SETS: Int = 10

    /** Faixa de repetições do app, a mesma do seletor de repetições da execução (1..100). */
    const val MIN_REPS: Int = 1
    const val MAX_REPS: Int = 100

    /** Descanso: 0 é "sem descanso", já oferecido nas configurações; 600 s é o teto de sanidade. */
    const val MIN_REST_SECONDS: Int = 0
    const val MAX_REST_SECONDS: Int = 600

    /** Teto de carga do app, o mesmo do seletor de carga da execução. */
    const val MAX_WEIGHT_KG: Double = 500.0

    /**
     * Structured output de um treino também é entrada não confiável.
     *
     * Mesma política da análise: **uma violação invalida a proposta inteira**. Nada é removido em
     * silêncio, nada é corrigido por aproximação e nenhum `exerciseId` desconhecido é resolvido
     * por nome. As regras próprias da geração:
     *
     * - todo `exerciseId` precisa estar entre os candidatos daquela requisição;
     * - nenhum exercício pode se repetir;
     * - a ordem precisa ser exatamente 1..n, sem repetição e sem buraco;
     * - carga só pode existir onde o app enviou carga registrada.
     */
    fun validateGeneratedWorkout(
        requestId: String,
        context: AiWorkoutGenerationContext,
        response: AiGeneratedWorkoutResponse
    ): AiGeneratedWorkoutValidation {
        if (response.insufficientCandidates && response.exercises.isEmpty()) {
            return AiGeneratedWorkoutValidation.InsufficientCandidates
        }
        if (response.insufficientCandidates) {
            return AiGeneratedWorkoutValidation.Invalid(
                "insufficientCandidates com ${response.exercises.size} exercícios propostos"
            )
        }

        val name = response.name.trim()
        if (name.isEmpty()) return AiGeneratedWorkoutValidation.Invalid("name vazio")
        if (name.length > MAX_WORKOUT_NAME_LENGTH) {
            return AiGeneratedWorkoutValidation.Invalid("name excede $MAX_WORKOUT_NAME_LENGTH caracteres")
        }

        val explanation = response.explanation.trim()
        if (explanation.length > MAX_EXPLANATION_LENGTH) {
            return AiGeneratedWorkoutValidation.Invalid(
                "explanation excede $MAX_EXPLANATION_LENGTH caracteres"
            )
        }

        val exercises = response.exercises
        if (exercises.size < MIN_GENERATED_EXERCISES) {
            return AiGeneratedWorkoutValidation.Invalid("treino sem exercícios")
        }
        if (exercises.size > MAX_GENERATED_EXERCISES) {
            return AiGeneratedWorkoutValidation.Invalid("mais de $MAX_GENERATED_EXERCISES exercícios")
        }

        val candidatesById = context.candidateExercises.associateBy { it.exerciseId }
        val idsWithLoadEvidence = context.exerciseIdsWithLoadEvidence
        val seenIds = mutableSetOf<String>()
        val seenOrders = mutableSetOf<Int>()
        val draftExercises = mutableListOf<GeneratedWorkoutDraftExercise>()

        exercises.forEachIndexed { index, raw ->
            val exerciseId = raw.exerciseId.trim()
            if (exerciseId.isEmpty()) {
                return AiGeneratedWorkoutValidation.Invalid("exerciseId vazio em [$index]")
            }
            // Id que o app não ofereceu é invenção — inclusive um id real do catálogo que não
            // entrou nos candidatos desta requisição.
            val candidate = candidatesById[exerciseId]
                ?: return AiGeneratedWorkoutValidation.Invalid(
                    "exerciseId fora dos candidatos em [$index]: '$exerciseId'"
                )
            if (!seenIds.add(exerciseId)) {
                return AiGeneratedWorkoutValidation.Invalid("exercício repetido em [$index]: '$exerciseId'")
            }

            if (raw.order < 1 || raw.order > exercises.size) {
                return AiGeneratedWorkoutValidation.Invalid("order fora de 1..${exercises.size} em [$index]: ${raw.order}")
            }
            if (!seenOrders.add(raw.order)) {
                return AiGeneratedWorkoutValidation.Invalid("order repetida em [$index]: ${raw.order}")
            }

            if (raw.sets < MIN_SETS || raw.sets > MAX_SETS) {
                return AiGeneratedWorkoutValidation.Invalid("sets fora de $MIN_SETS..$MAX_SETS em [$index]: ${raw.sets}")
            }
            if (raw.minReps < MIN_REPS || raw.minReps > MAX_REPS) {
                return AiGeneratedWorkoutValidation.Invalid("minReps fora de $MIN_REPS..$MAX_REPS em [$index]: ${raw.minReps}")
            }
            if (raw.maxReps < raw.minReps || raw.maxReps > MAX_REPS) {
                return AiGeneratedWorkoutValidation.Invalid("maxReps inválido em [$index]: ${raw.maxReps}")
            }
            if (raw.restSeconds < MIN_REST_SECONDS || raw.restSeconds > MAX_REST_SECONDS) {
                return AiGeneratedWorkoutValidation.Invalid(
                    "restSeconds fora de $MIN_REST_SECONDS..$MAX_REST_SECONDS em [$index]: ${raw.restSeconds}"
                )
            }

            val weight = raw.weightKg
            if (weight != null) {
                if (weight.isNaN() || weight.isInfinite()) {
                    return AiGeneratedWorkoutValidation.Invalid("weightKg não numérico em [$index]")
                }
                if (weight <= 0.0 || weight > MAX_WEIGHT_KG) {
                    return AiGeneratedWorkoutValidation.Invalid("weightKg fora de 0..$MAX_WEIGHT_KG em [$index]: $weight")
                }
                // Sem carga registrada no contexto, propor um número é invenção.
                if (exerciseId !in idsWithLoadEvidence) {
                    return AiGeneratedWorkoutValidation.Invalid(
                        "weightKg sem carga registrada em [$index]: '$exerciseId'"
                    )
                }
            }

            val reason = raw.reason.trim()
            if (reason.length > MAX_EXERCISE_REASON_LENGTH) {
                return AiGeneratedWorkoutValidation.Invalid(
                    "reason excede $MAX_EXERCISE_REASON_LENGTH caracteres em [$index]"
                )
            }

            draftExercises += GeneratedWorkoutDraftExercise(
                exerciseId = exerciseId,
                // O nome vem do catálogo do app, nunca do texto do modelo.
                name = candidate.name,
                sortOrder = raw.order - 1,
                sets = raw.sets,
                minReps = raw.minReps,
                maxReps = raw.maxReps,
                restSeconds = raw.restSeconds,
                weightKg = weight?.toFloat(),
                reason = reason
            )
        }

        return AiGeneratedWorkoutValidation.Valid(
            GeneratedWorkoutDraft(
                requestId = requestId,
                name = name,
                explanation = explanation,
                exercises = draftExercises.sortedBy { it.sortOrder }
            )
        )
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
