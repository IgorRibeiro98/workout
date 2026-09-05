package com.example.domain.ai

import com.example.domain.ai.model.AiWorkoutAdaptationContext

/**
 * O treino, o histórico dele e a revisão do template no instante em que a proposta foi montada.
 *
 * A revisão sai junto do contexto de propósito: ela precisa descrever exatamente o estado que o
 * modelo enxergou, para a aplicação depois conseguir dizer se aquele estado ainda é o atual.
 */
data class AiWorkoutAdaptationSource(
    val templateId: Long,
    val revision: String,
    val context: AiWorkoutAdaptationContext
)

/**
 * Monta o contexto de uma adaptação a partir das autoridades canônicas do Spark.
 *
 * Mesmo papel de [AiCoachContextBuilder] e [AiWorkoutGenerationContextBuilder], para o terceiro
 * tipo de request: projeta dados que já existem e não cria fonte de verdade concorrente.
 */
interface AiWorkoutAdaptationContextBuilder {

    /** `null` quando o treino não existe ou não tem exercícios — não há o que adaptar. */
    suspend fun build(templateId: Long): AiWorkoutAdaptationSource?
}
