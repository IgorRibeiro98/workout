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
    ADAPT_WORKOUT
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
