package com.example.domain.ai.model

/** Tipos permitidos nesta fase. Um valor fora daqui invalida a resposta inteira. */
enum class AiRecommendationType {
    GENERAL,
    KEEP_CURRENT_PLAN,
    REVIEW_LOAD,
    REVIEW_VOLUME
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
    val confidence: Double
)

/** O conselho completo já validado, pronto para a UI. */
data class AiCoachAdvice(
    val requestId: String,
    val summary: String,
    val recommendations: List<AiRecommendation>
)
