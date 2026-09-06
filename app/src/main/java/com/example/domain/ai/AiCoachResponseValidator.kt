package com.example.domain.ai

import com.example.domain.ai.model.AiCoachAdvice
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachDataQuality
import com.example.domain.ai.model.AiCoachExplanationContext
import com.example.domain.ai.model.AiCoachExplanationResponse
import com.example.domain.ai.model.AiCoachObservation
import com.example.domain.ai.model.AiCoachResponse
import com.example.domain.ai.model.AiCoachResponseDataQuality
import com.example.domain.ai.model.AiCoachResponseObservation
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiPlannedExerciseContext
import com.example.domain.ai.model.AiGeneratedWorkoutResponse
import com.example.domain.ai.model.AiRecommendation
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.AiWorkoutAdaptationChangeResponse
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationResponse
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.WorkoutAdaptationChange
import com.example.domain.ai.model.WorkoutAdaptationDraft
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import com.example.domain.ai.model.GeneratedWorkoutDraft
import com.example.domain.ai.model.GeneratedWorkoutDraftExercise

/** O que a validação decidiu sobre a resposta do modelo. */
sealed interface AiCoachValidation {
    data class Valid(val advice: AiCoachAdvice) : AiCoachValidation
    data class Invalid(val reason: String) : AiCoachValidation
}

/** O que a validação decidiu sobre uma adaptação proposta pelo modelo. */
sealed interface AiWorkoutAdaptationValidation {
    data class Valid(val draft: WorkoutAdaptationDraft) : AiWorkoutAdaptationValidation
    data class Invalid(val reason: String) : AiWorkoutAdaptationValidation
}

/** O que a validação decidiu sobre uma explicação escrita pelo modelo. */
sealed interface AiCoachExplanationValidation {
    /** Só o que o modelo escreveu. A evidência continua sendo montada pelo app. */
    data class Valid(
        val title: String,
        val explanation: String,
        val limitations: List<String>
    ) : AiCoachExplanationValidation

    data class Invalid(val reason: String) : AiCoachExplanationValidation
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
            val result = validateObservations(
                "positiveSignals",
                AiCoachAdvice.POSITIVE_SIGNAL_ID_PREFIX,
                response.positiveSignals,
                knownExerciseIds
            )
        ) {
            is ObservationsResult.Invalid -> return AiCoachValidation.Invalid(result.reason)
            is ObservationsResult.Valid -> result.observations
        }

        val attentionPoints = when (
            val result = validateObservations(
                "attentionPoints",
                AiCoachAdvice.ATTENTION_POINT_ID_PREFIX,
                response.attentionPoints,
                knownExerciseIds
            )
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
                id = "${AiCoachAdvice.RECOMMENDATION_ID_PREFIX}:$index",
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
        idPrefix: String,
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
                id = "$idPrefix:$index",
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
    // Adaptação de treino (T14.3)
    // ---------------------------------------------------------------------------------------

    /**
     * Teto de mudanças por resposta.
     *
     * Reaproveita o teto de exercícios de um treino real
     * ([AiModelConfig.MAX_EXERCISES_IN_CONTEXT]): mais de uma dúzia de alterações de uma vez não é
     * adaptação, é outro treino.
     */
    const val MAX_ADAPTATION_CHANGES: Int = AiModelConfig.MAX_EXERCISES_IN_CONTEXT

    /** Tolerância ao comparar carga: `Float` não fecha em igualdade exata. */
    const val WEIGHT_TOLERANCE_KG: Double = 0.001

    /**
     * Uma proposta de adaptação também é entrada não confiável.
     *
     * Mesma política das outras duas: **uma violação invalida a proposta inteira**. As regras
     * próprias da adaptação:
     *
     * - o exercício alterado precisa estar no treino;
     * - o tipo precisa estar entre os autorizados nesta requisição (sem evidência de desempenho,
     *   os tipos de progressão nem são oferecidos);
     * - o **valor atual** declarado precisa bater com o que o template tem hoje — se não bate, o
     *   modelo raciocinou sobre outro treino;
     * - o valor sugerido precisa ser diferente do atual e válido no domínio;
     * - substituto só entre os candidatos enviados;
     * - toda mudança precisa de razão e evidência.
     */
    fun validateWorkoutAdaptation(
        requestId: String,
        templateId: Long,
        sourceRevision: String,
        context: AiWorkoutAdaptationContext,
        response: AiWorkoutAdaptationResponse
    ): AiWorkoutAdaptationValidation {
        val summary = response.summary.trim()
        if (summary.isEmpty()) {
            return AiWorkoutAdaptationValidation.Invalid("summary vazio")
        }
        if (summary.length > MAX_SUMMARY_LENGTH) {
            return AiWorkoutAdaptationValidation.Invalid("summary excede $MAX_SUMMARY_LENGTH caracteres")
        }
        if (response.changes.size > MAX_ADAPTATION_CHANGES) {
            return AiWorkoutAdaptationValidation.Invalid("mais de $MAX_ADAPTATION_CHANGES mudanças")
        }

        val dataQuality = when (
            val result = validateDataQuality(context.evidence.maxDataQuality, response.dataQuality)
        ) {
            is DataQualityResult.Invalid -> return AiWorkoutAdaptationValidation.Invalid(result.reason)
            is DataQualityResult.Valid -> result.dataQuality
        }

        val allowedTypes = context.allowedChangeTypes.toSet()
        val seenIds = mutableSetOf<String>()
        val seenReplacements = mutableSetOf<String>()
        val changes = mutableListOf<WorkoutAdaptationChange>()

        response.changes.forEachIndexed { index, raw ->
            val type = WorkoutAdaptationType.entries.firstOrNull { it.name == raw.type.trim() }
                ?: return AiWorkoutAdaptationValidation.Invalid("tipo desconhecido em [$index]: '${raw.type}'")
            if (type.name !in allowedTypes) {
                return AiWorkoutAdaptationValidation.Invalid("tipo não autorizado nesta adaptação em [$index]: ${type.name}")
            }

            val exerciseId = raw.exerciseId.trim()
            if (exerciseId.isEmpty()) {
                return AiWorkoutAdaptationValidation.Invalid("exerciseId vazio em [$index]")
            }
            val planned = context.plannedExercise(exerciseId)
                ?: return AiWorkoutAdaptationValidation.Invalid(
                    "exerciseId fora do treino em [$index]: '$exerciseId'"
                )

            val changeId = WorkoutAdaptationChange.idOf(type, exerciseId)
            if (!seenIds.add(changeId)) {
                return AiWorkoutAdaptationValidation.Invalid("mudança repetida em [$index]: $changeId")
            }

            extraneousField(type, raw)?.let { field ->
                return AiWorkoutAdaptationValidation.Invalid(
                    "campo '$field' não pertence a ${type.name} em [$index]"
                )
            }

            val reason = raw.reason.trim()
            if (reason.isEmpty()) {
                return AiWorkoutAdaptationValidation.Invalid("reason vazio em [$index]")
            }
            if (reason.length > MAX_REASON_LENGTH) {
                return AiWorkoutAdaptationValidation.Invalid("reason excede $MAX_REASON_LENGTH caracteres em [$index]")
            }

            // Evidência é obrigatória em toda mudança: uma alteração de treino sem o dado que a
            // sustenta é opinião, e opinião não altera plano.
            val evidence = raw.evidence.trim()
            if (evidence.isEmpty()) {
                return AiWorkoutAdaptationValidation.Invalid("evidence vazia em [$index]")
            }
            if (evidence.length > MAX_EVIDENCE_LENGTH) {
                return AiWorkoutAdaptationValidation.Invalid("evidence excede $MAX_EVIDENCE_LENGTH caracteres em [$index]")
            }

            val confidence = raw.confidence
            if (confidence.isNaN() || confidence.isInfinite()) {
                return AiWorkoutAdaptationValidation.Invalid("confidence não numérico em [$index]")
            }
            if (confidence < 0.0 || confidence > 1.0) {
                return AiWorkoutAdaptationValidation.Invalid("confidence fora de 0..1 em [$index]: $confidence")
            }

            val values = when (type) {
                WorkoutAdaptationType.ADJUST_LOAD -> validateLoadChange(index, planned, raw)
                WorkoutAdaptationType.ADJUST_SETS -> validateSetsChange(index, planned, raw)
                WorkoutAdaptationType.ADJUST_REPS -> validateRepsChange(index, planned, raw)
                WorkoutAdaptationType.ADJUST_REST -> validateRestChange(index, planned, raw)
                WorkoutAdaptationType.REPLACE_EXERCISE ->
                    validateReplacement(index, planned, raw, context, seenReplacements)
            }
            val (currentValue, suggestedValue) = when (values) {
                is ChangeValues.Invalid -> return AiWorkoutAdaptationValidation.Invalid(values.reason)
                is ChangeValues.Valid -> values.current to values.suggested
            }

            changes += WorkoutAdaptationChange(
                id = changeId,
                type = type,
                exerciseId = exerciseId,
                // O nome vem do catálogo do app, nunca do texto do modelo.
                exerciseName = planned.name,
                currentValue = currentValue,
                suggestedValue = suggestedValue,
                reason = reason,
                evidence = evidence,
                confidence = confidence
            )
        }

        return AiWorkoutAdaptationValidation.Valid(
            WorkoutAdaptationDraft(
                requestId = requestId,
                templateId = templateId,
                templateName = context.templateName,
                sourceRevision = sourceRevision,
                summary = summary,
                dataQuality = dataQuality,
                changes = changes
            )
        )
    }

    private sealed interface ChangeValues {
        data class Valid(
            val current: WorkoutAdaptationValue,
            val suggested: WorkoutAdaptationValue
        ) : ChangeValues

        data class Invalid(val reason: String) : ChangeValues
    }

    /**
     * O primeiro campo preenchido que não pertence ao tipo declarado, ou `null`.
     *
     * Uma mudança que traz carga **e** repetições é ambígua: o app não adivinha qual delas o
     * usuário estaria aceitando.
     */
    private fun extraneousField(
        type: WorkoutAdaptationType,
        raw: AiWorkoutAdaptationChangeResponse
    ): String? {
        val hasLoad = raw.currentWeightKg != null || raw.suggestedWeightKg != null
        val hasSets = raw.currentSets != null || raw.suggestedSets != null
        val hasReps = raw.currentMinReps != null || raw.currentMaxReps != null ||
            raw.suggestedMinReps != null || raw.suggestedMaxReps != null
        val hasRest = raw.currentRestSeconds != null || raw.suggestedRestSeconds != null
        val hasReplacement = raw.replacementExerciseId != null

        return when (type) {
            WorkoutAdaptationType.ADJUST_LOAD -> when {
                hasSets -> "sets"
                hasReps -> "reps"
                hasRest -> "restSeconds"
                hasReplacement -> "replacementExerciseId"
                else -> null
            }

            WorkoutAdaptationType.ADJUST_SETS -> when {
                hasLoad -> "weightKg"
                hasReps -> "reps"
                hasRest -> "restSeconds"
                hasReplacement -> "replacementExerciseId"
                else -> null
            }

            WorkoutAdaptationType.ADJUST_REPS -> when {
                hasLoad -> "weightKg"
                hasSets -> "sets"
                hasRest -> "restSeconds"
                hasReplacement -> "replacementExerciseId"
                else -> null
            }

            WorkoutAdaptationType.ADJUST_REST -> when {
                hasLoad -> "weightKg"
                hasSets -> "sets"
                hasReps -> "reps"
                hasReplacement -> "replacementExerciseId"
                else -> null
            }

            WorkoutAdaptationType.REPLACE_EXERCISE -> when {
                hasLoad -> "weightKg"
                hasSets -> "sets"
                hasReps -> "reps"
                hasRest -> "restSeconds"
                else -> null
            }
        }
    }

    private fun validateLoadChange(
        index: Int,
        planned: AiPlannedExerciseContext,
        raw: AiWorkoutAdaptationChangeResponse
    ): ChangeValues {
        val currentInTemplate = planned.plannedWeightKg
        val declaredCurrent = raw.currentWeightKg
        if (declaredCurrent != null && (declaredCurrent.isNaN() || declaredCurrent.isInfinite())) {
            return ChangeValues.Invalid("currentWeightKg não numérico em [$index]")
        }
        if (!sameWeight(declaredCurrent, currentInTemplate)) {
            return ChangeValues.Invalid(
                "currentWeightKg não corresponde ao treino em [$index]: " +
                    "modelo=${declaredCurrent ?: "null"}, treino=${currentInTemplate ?: "null"}"
            )
        }

        val suggested = raw.suggestedWeightKg
            ?: return ChangeValues.Invalid("suggestedWeightKg ausente em [$index]")
        if (suggested.isNaN() || suggested.isInfinite()) {
            return ChangeValues.Invalid("suggestedWeightKg não numérico em [$index]")
        }
        if (suggested <= 0.0 || suggested > MAX_WEIGHT_KG) {
            return ChangeValues.Invalid("suggestedWeightKg fora de 0..$MAX_WEIGHT_KG em [$index]: $suggested")
        }
        if (sameWeight(suggested, currentInTemplate)) {
            return ChangeValues.Invalid("suggestedWeightKg igual ao atual em [$index]")
        }

        return ChangeValues.Valid(
            current = WorkoutAdaptationValue.Load(currentInTemplate),
            suggested = WorkoutAdaptationValue.Load(suggested.toFloat())
        )
    }

    private fun validateSetsChange(
        index: Int,
        planned: AiPlannedExerciseContext,
        raw: AiWorkoutAdaptationChangeResponse
    ): ChangeValues {
        val current = planned.targetSets
            ?: return ChangeValues.Invalid("treino sem séries configuradas em [$index]")
        if (raw.currentSets != current) {
            return ChangeValues.Invalid(
                "currentSets não corresponde ao treino em [$index]: modelo=${raw.currentSets}, treino=$current"
            )
        }

        val suggested = raw.suggestedSets
            ?: return ChangeValues.Invalid("suggestedSets ausente em [$index]")
        if (suggested < MIN_SETS || suggested > MAX_SETS) {
            return ChangeValues.Invalid("suggestedSets fora de $MIN_SETS..$MAX_SETS em [$index]: $suggested")
        }
        if (suggested == current) {
            return ChangeValues.Invalid("suggestedSets igual ao atual em [$index]")
        }

        return ChangeValues.Valid(
            current = WorkoutAdaptationValue.Sets(current),
            suggested = WorkoutAdaptationValue.Sets(suggested)
        )
    }

    private fun validateRepsChange(
        index: Int,
        planned: AiPlannedExerciseContext,
        raw: AiWorkoutAdaptationChangeResponse
    ): ChangeValues {
        val currentMin = planned.minReps
        val currentMax = planned.maxReps
        if (currentMin == null || currentMax == null) {
            return ChangeValues.Invalid("treino sem faixa de repetições em [$index]")
        }
        if (raw.currentMinReps != currentMin || raw.currentMaxReps != currentMax) {
            return ChangeValues.Invalid(
                "repetições atuais não correspondem ao treino em [$index]: " +
                    "modelo=${raw.currentMinReps}-${raw.currentMaxReps}, treino=$currentMin-$currentMax"
            )
        }

        val suggestedMin = raw.suggestedMinReps
            ?: return ChangeValues.Invalid("suggestedMinReps ausente em [$index]")
        val suggestedMax = raw.suggestedMaxReps
            ?: return ChangeValues.Invalid("suggestedMaxReps ausente em [$index]")
        if (suggestedMin < MIN_REPS || suggestedMin > MAX_REPS) {
            return ChangeValues.Invalid("suggestedMinReps fora de $MIN_REPS..$MAX_REPS em [$index]: $suggestedMin")
        }
        if (suggestedMax < suggestedMin || suggestedMax > MAX_REPS) {
            return ChangeValues.Invalid("suggestedMaxReps inválido em [$index]: $suggestedMax")
        }
        if (suggestedMin == currentMin && suggestedMax == currentMax) {
            return ChangeValues.Invalid("repetições sugeridas iguais às atuais em [$index]")
        }

        return ChangeValues.Valid(
            current = WorkoutAdaptationValue.Reps(currentMin, currentMax),
            suggested = WorkoutAdaptationValue.Reps(suggestedMin, suggestedMax)
        )
    }

    private fun validateRestChange(
        index: Int,
        planned: AiPlannedExerciseContext,
        raw: AiWorkoutAdaptationChangeResponse
    ): ChangeValues {
        val current = planned.restSeconds
            ?: return ChangeValues.Invalid("treino sem descanso configurado em [$index]")
        if (raw.currentRestSeconds != current) {
            return ChangeValues.Invalid(
                "currentRestSeconds não corresponde ao treino em [$index]: " +
                    "modelo=${raw.currentRestSeconds}, treino=$current"
            )
        }

        val suggested = raw.suggestedRestSeconds
            ?: return ChangeValues.Invalid("suggestedRestSeconds ausente em [$index]")
        if (suggested < MIN_REST_SECONDS || suggested > MAX_REST_SECONDS) {
            return ChangeValues.Invalid(
                "suggestedRestSeconds fora de $MIN_REST_SECONDS..$MAX_REST_SECONDS em [$index]: $suggested"
            )
        }
        if (suggested == current) {
            return ChangeValues.Invalid("suggestedRestSeconds igual ao atual em [$index]")
        }

        return ChangeValues.Valid(
            current = WorkoutAdaptationValue.Rest(current),
            suggested = WorkoutAdaptationValue.Rest(suggested)
        )
    }

    private fun validateReplacement(
        index: Int,
        planned: AiPlannedExerciseContext,
        raw: AiWorkoutAdaptationChangeResponse,
        context: AiWorkoutAdaptationContext,
        seenReplacements: MutableSet<String>
    ): ChangeValues {
        val replacementId = raw.replacementExerciseId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ChangeValues.Invalid("replacementExerciseId ausente em [$index]")
        if (replacementId == planned.exerciseId) {
            return ChangeValues.Invalid("replacementExerciseId igual ao exercício atual em [$index]")
        }
        // Id que o app não ofereceu é invenção — inclusive um id real do catálogo que não entrou
        // nos candidatos desta requisição.
        val candidate = context.replacementCandidates.firstOrNull { it.exerciseId == replacementId }
            ?: return ChangeValues.Invalid(
                "replacementExerciseId fora dos candidatos em [$index]: '$replacementId'"
            )
        if (replacementId in context.templateExerciseIds) {
            return ChangeValues.Invalid("replacementExerciseId já está no treino em [$index]")
        }
        if (!seenReplacements.add(replacementId)) {
            return ChangeValues.Invalid("mesmo substituto usado duas vezes em [$index]: '$replacementId'")
        }

        return ChangeValues.Valid(
            current = WorkoutAdaptationValue.Exercise(planned.exerciseId, planned.name),
            suggested = WorkoutAdaptationValue.Exercise(candidate.exerciseId, candidate.name)
        )
    }

    /** Comparação de carga tolerante a `Float`, tratando ausência como valor. */
    private fun sameWeight(declared: Double?, inTemplate: Float?): Boolean = when {
        declared == null && inTemplate == null -> true
        declared == null || inTemplate == null -> false
        else -> kotlin.math.abs(declared - inTemplate.toDouble()) <= WEIGHT_TOLERANCE_KG
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

    // ---------------------------------------------------------------------------------------
    // Explicação contextual (T14.4)
    // ---------------------------------------------------------------------------------------

    /** Uma explicação é um parágrafo curto, não um artigo. */
    const val MAX_EXPLANATION_TEXT_LENGTH: Int = 900

    /** Teto de limitações declaradas pelo modelo. Acima disso é ruído, não transparência. */
    const val MAX_LIMITATIONS: Int = 4

    /**
     * Uma explicação também é entrada não confiável.
     *
     * Mesma política das outras três: **uma violação invalida a resposta inteira**. As regras
     * próprias da explicação:
     *
     * - título e texto precisam existir e caber no contrato;
     * - todo `exerciseId` citado precisa estar no contexto enviado — inclusive um id real do
     *   catálogo que não entrou nesta explicação;
     * - limitação vazia não é limitação.
     *
     * Evidência não é validada aqui porque o modelo não a escreve: ela é montada pelo app a
     * partir de [AiCoachExplanationContext.facts].
     */
    fun validateExplanation(
        context: AiCoachExplanationContext,
        response: AiCoachExplanationResponse
    ): AiCoachExplanationValidation {
        val title = response.title.trim()
        if (title.isEmpty()) {
            return AiCoachExplanationValidation.Invalid("title vazio")
        }
        if (title.length > MAX_TITLE_LENGTH) {
            return AiCoachExplanationValidation.Invalid("title excede $MAX_TITLE_LENGTH caracteres")
        }

        val explanation = response.explanation.trim()
        if (explanation.isEmpty()) {
            return AiCoachExplanationValidation.Invalid("explanation vazia")
        }
        if (explanation.length > MAX_EXPLANATION_TEXT_LENGTH) {
            return AiCoachExplanationValidation.Invalid(
                "explanation excede $MAX_EXPLANATION_TEXT_LENGTH caracteres"
            )
        }

        if (response.limitations.size > MAX_LIMITATIONS) {
            return AiCoachExplanationValidation.Invalid("mais de $MAX_LIMITATIONS limitações")
        }
        val limitations = mutableListOf<String>()
        response.limitations.forEachIndexed { index, raw ->
            val limitation = raw.trim()
            if (limitation.isEmpty()) {
                return AiCoachExplanationValidation.Invalid("limitação vazia em [$index]")
            }
            if (limitation.length > MAX_DESCRIPTION_LENGTH) {
                return AiCoachExplanationValidation.Invalid(
                    "limitação excede $MAX_DESCRIPTION_LENGTH caracteres em [$index]"
                )
            }
            limitations += limitation
        }

        val knownExerciseIds = context.knownExerciseIds
        response.referencedExerciseIds.forEachIndexed { index, raw ->
            val exerciseId = raw.trim()
            if (exerciseId.isEmpty()) {
                return AiCoachExplanationValidation.Invalid("exerciseId vazio em [$index]")
            }
            if (exerciseId !in knownExerciseIds) {
                return AiCoachExplanationValidation.Invalid(
                    "exerciseId fora do contexto em [$index]: '$exerciseId'"
                )
            }
        }

        return AiCoachExplanationValidation.Valid(
            title = title,
            explanation = explanation,
            limitations = limitations.distinct()
        )
    }

    private fun validateDataQuality(
        context: AiCoachContext,
        response: AiCoachResponse
    ): DataQualityResult = validateDataQuality(context.evidence.maxDataQuality, response.dataQuality)

    /**
     * O nível declarado pelo modelo, conferido contra o teto que o app calculou.
     *
     * Uma implementação só: análise e adaptação usam a mesma regra — o modelo pode ser mais
     * conservador que o app, nunca mais confiante.
     */
    private fun validateDataQuality(
        ceiling: AiDataQualityLevel,
        raw: AiCoachResponseDataQuality?
    ): DataQualityResult {
        if (raw == null) return DataQualityResult.Invalid("dataQuality ausente")

        val level = AiDataQualityLevel.entries.firstOrNull { it.name == raw.level.trim() }
            ?: return DataQualityResult.Invalid("dataQuality desconhecido: '${raw.level}'")

        // O teto foi calculado por AiDataQualityPolicy quando o contexto foi montado.
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
