package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Contexto que o Spark envia ao Coach para **adaptar um treino existente**.
 *
 * Reaproveita as projeções que já existem: o treino em [template] e o histórico em
 * [exerciseHistory] são os mesmos tipos da análise (T14.1), e [replacementCandidates] é o mesmo
 * tipo de candidato da geração (T14.2). Não há terceira representação de treino, exercício ou
 * histórico.
 *
 * O que o modelo pode citar é fechado por construção: só exercícios do treino em
 * [templateExerciseIds] e só substitutos em [allowedReplacementIds].
 */
@Serializable
data class AiWorkoutAdaptationContext(
    val templateName: String,
    /** A configuração atual do treino: o que existe hoje, campo a campo. */
    val template: AiWorkoutContext,
    /** Execuções concluídas dos exercícios deste treino, da mais recente para a mais antiga. */
    val exerciseHistory: List<AiExerciseHistoryContext> = emptyList(),
    val personalRecords: List<AiPersonalRecordContext> = emptyList(),
    /** Substitutos permitidos nesta requisição. Vazio = nenhuma substituição autorizada. */
    val replacementCandidates: List<AiCandidateExerciseContext> = emptyList(),
    /**
     * Tipos de mudança que o app aceita nesta requisição.
     *
     * Sem evidência de desempenho, os tipos de progressão simplesmente não são oferecidos — quem
     * decide isso é o app, a partir do histórico que conseguiu reunir.
     */
    val allowedChangeTypes: List<String> = emptyList(),
    val evidence: AiEvidenceContext = AiEvidenceContext()
) {
    /** Os únicos exercícios que uma mudança pode alterar. */
    val templateExerciseIds: Set<String>
        get() = template.exercises.mapTo(mutableSetOf()) { it.exerciseId }

    /** Os únicos ids aceitos como substituto. */
    val allowedReplacementIds: Set<String>
        get() = replacementCandidates.mapTo(mutableSetOf()) { it.exerciseId }

    /** O exercício do treino, como ele está persistido hoje. */
    fun plannedExercise(exerciseId: String): AiPlannedExerciseContext? =
        template.exercises.firstOrNull { it.exerciseId == exerciseId }
}

/**
 * Contrato de ida de uma adaptação de treino.
 *
 * Mesmo formato de [AiCoachRequest] e [AiWorkoutGenerationRequest] — id, versão e tipo —, com o
 * contexto específico da adaptação.
 */
@Serializable
data class AiWorkoutAdaptationRequest(
    val requestId: String,
    val schemaVersion: Int,
    val type: AiCoachRequestType = AiCoachRequestType.ADAPT_WORKOUT,
    val context: AiWorkoutAdaptationContext
)
