package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * O que o app está pedindo ao Coach.
 *
 * Cada tipo tem o seu próprio contexto e o seu próprio schema de saída. Não existe uma resposta
 * única com dezenas de campos opcionais: análise e geração são conversas diferentes.
 */
enum class AiCoachRequestType {
    ANALYZE_WORKOUT,
    GENERATE_WORKOUT,
    ADAPT_WORKOUT,

    /** Por que esta recomendação da análise foi feita. */
    EXPLAIN_RECOMMENDATION,

    /** Por que o treino proposto foi montado assim. */
    EXPLAIN_WORKOUT,

    /** Por que esta mudança foi sugerida para o treino. */
    EXPLAIN_ADAPTATION,

    /** O que os números de progressão do Perfil querem dizer. */
    EXPLAIN_PROGRESS;

    /**
     * Se o request apenas explica algo que já existe.
     *
     * Todo `EXPLAIN_*` é READ-ONLY por contrato: ele não cria treino, não altera treino, não
     * altera sessão e não concede XP, PR ou conquista.
     */
    val isExplanation: Boolean
        get() = this == EXPLAIN_RECOMMENDATION || this == EXPLAIN_WORKOUT ||
            this == EXPLAIN_ADAPTATION || this == EXPLAIN_PROGRESS
}

/**
 * Contrato de ida de uma **análise** de treino.
 *
 * [schemaVersion] versiona a conversa entre app e modelo — não o schema do Room. A geração de
 * treino tem o seu próprio contrato em [AiWorkoutGenerationRequest].
 */
@Serializable
data class AiCoachRequest(
    val requestId: String,
    val schemaVersion: Int,
    val type: AiCoachRequestType,
    val context: AiCoachContext
)
