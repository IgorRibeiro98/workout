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
)
