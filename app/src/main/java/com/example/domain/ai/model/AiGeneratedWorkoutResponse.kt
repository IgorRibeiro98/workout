package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Resposta crua da geração, já desserializada do structured output.
 *
 * Nada aqui é domínio: é uma **proposta**. Os números chegam com sentinelas inválidas
 * (`-1`) de propósito, para que um campo ausente vire falha determinística de validação em vez
 * de virar um treino com valor padrão silencioso.
 */
@Serializable
data class AiGeneratedWorkoutResponse(
    val name: String = "",
    val exercises: List<AiGeneratedWorkoutExerciseResponse> = emptyList(),
    /** Por que o treino foi montado assim. Serve à leitura do usuário; não vira dado persistido. */
    val explanation: String = "",
    /**
     * O modelo admitindo que os candidatos enviados não sustentam o pedido.
     *
     * É preferível a inventar um treino ruim: vira estado determinístico na UI, sem nova chamada.
     */
    val insufficientCandidates: Boolean = false
)

/** Um exercício da proposta, exatamente como o modelo escreveu. */
@Serializable
data class AiGeneratedWorkoutExerciseResponse(
    /** Deve ser exatamente um `exerciseId` enviado em `candidateExercises`. */
    val exerciseId: String = "",
    /** Posição do exercício no treino, começando em 1. */
    val order: Int = -1,
    val sets: Int = -1,
    val minReps: Int = -1,
    val maxReps: Int = -1,
    val restSeconds: Int = -1,
    /** Só pode existir quando o contexto enviou carga registrada para este exercício. */
    val weightKg: Double? = null,
    val reason: String = ""
)

/** Resultado bruto do provider para uma geração, antes da validação semântica. */
sealed interface AiWorkoutGenerationGatewayResult {
    data class Success(val response: AiGeneratedWorkoutResponse) : AiWorkoutGenerationGatewayResult
    data class Error(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AiWorkoutGenerationGatewayResult
}
