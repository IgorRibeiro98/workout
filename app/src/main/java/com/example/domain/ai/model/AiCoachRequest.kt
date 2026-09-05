package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/** O que o app está pedindo ao Coach. A T14.0 prova a infraestrutura com um único tipo. */
enum class AiCoachRequestType {
    ANALYZE_WORKOUT
}

/**
 * Contrato de ida do Spark para o Coach IA.
 *
 * [schemaVersion] versiona a conversa entre app e modelo — não o schema do Room.
 */
@Serializable
data class AiCoachRequest(
    val requestId: String,
    val schemaVersion: Int,
    val type: AiCoachRequestType,
    val context: AiCoachContext
)
