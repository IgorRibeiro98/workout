package com.example.domain.ai.model

/**
 * Tipos permitidos nesta fase. Um valor fora daqui invalida a resposta inteira.
 *
 * Todos são de **revisão**: nenhum aplica alteração. Tipos de ação (aplicar carga, trocar
 * exercício, criar treino) não existem enquanto a IA não tiver autoridade para agir.
 */
enum class AiRecommendationType {
    GENERAL,
    KEEP_CURRENT_PLAN,
    REVIEW_LOAD,
    REVIEW_REPS,
    REVIEW_VOLUME,
    REVIEW_EXERCISE;

    /**
     * Se o tipo só faz sentido apontando para um exercício.
     *
     * "Revisar a carga" sem dizer de quê não é acionável; "manter o plano" e "observação geral"
     * podem valer para o treino inteiro, e volume também é legítimo no nível do treino.
     */
    val requiresExercise: Boolean
        get() = this == REVIEW_LOAD || this == REVIEW_REPS || this == REVIEW_EXERCISE
}

/**
 * Uma sugestão validada do Coach.
 *
 * Isto é **sugestão**, nunca alteração aplicada: nada aqui escreve em treino, histórico,
 * XP, streak, conquista ou PR. Quem decide continua sendo o domínio e o usuário.
 */
data class AiRecommendation(
    /**
     * Identidade estável dentro de uma análise, atribuída pelo validador.
     *
     * Existe para o usuário conseguir pedir "por quê?" sobre **esta** recomendação sem que o app
     * dependa do texto exibido para reencontrá-la.
     */
    val id: String,
    val type: AiRecommendationType,
    /** `null` quando a sugestão é geral. Quando presente, é um id que o app enviou no contexto. */
    val exerciseId: String? = null,
    val reason: String,
    val confidence: Double,
    /** O dado que sustenta a recomendação. Obrigatório quando ela aponta para um exercício. */
    val evidence: String? = null
)

/**
 * Um fato observado, separado da recomendação de propósito.
 *
 * A T14.1 exige distinguir "o que os dados mostram" de "o que a IA sugere por causa disso".
 */
data class AiCoachObservation(
    /** Identidade estável dentro de uma análise, atribuída pelo validador. */
    val id: String,
    val exerciseId: String? = null,
    val title: String,
    val description: String
)

/** Quanta evidência sustenta esta análise, já validado contra o que o app enviou. */
data class AiCoachDataQuality(
    val level: AiDataQualityLevel,
    val description: String
)

/** O conselho completo já validado, pronto para a UI. */
data class AiCoachAdvice(
    val requestId: String,
    val summary: String,
    val positiveSignals: List<AiCoachObservation> = emptyList(),
    val attentionPoints: List<AiCoachObservation> = emptyList(),
    val recommendations: List<AiRecommendation> = emptyList(),
    val dataQuality: AiCoachDataQuality,
    /** Quantas sessões concluídas o app usou. Fato do app, nunca número escrito pelo modelo. */
    val sessionsAnalyzed: Int
) {

    /** A recomendação ou observação com este id, ou `null` se ela não existe nesta análise. */
    fun explainableTarget(targetId: String): AiExplainableTarget? =
        recommendations.firstOrNull { it.id == targetId }?.let(AiExplainableTarget::Recommendation)
            ?: positiveSignals.firstOrNull { it.id == targetId }
                ?.let { AiExplainableTarget.Observation(it, isAttentionPoint = false) }
            ?: attentionPoints.firstOrNull { it.id == targetId }
                ?.let { AiExplainableTarget.Observation(it, isAttentionPoint = true) }

    companion object {
        const val RECOMMENDATION_ID_PREFIX: String = "REC"
        const val POSITIVE_SIGNAL_ID_PREFIX: String = "POSITIVE"
        const val ATTENTION_POINT_ID_PREFIX: String = "ATTENTION"
    }
}

/**
 * O que, dentro de uma análise, o usuário pode pedir para o Coach explicar.
 *
 * A resolução é sempre por id contra a análise **atual**: se a análise foi refeita ou descartada,
 * o id simplesmente não existe mais e nenhuma chamada acontece.
 */
sealed interface AiExplainableTarget {
    data class Recommendation(val recommendation: AiRecommendation) : AiExplainableTarget
    data class Observation(
        val observation: AiCoachObservation,
        val isAttentionPoint: Boolean
    ) : AiExplainableTarget
}
