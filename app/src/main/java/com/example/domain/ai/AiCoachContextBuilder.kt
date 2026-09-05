package com.example.domain.ai

import com.example.domain.ai.model.AiCoachContext

/**
 * Monta o contexto do Coach a partir das autoridades canônicas do Spark.
 *
 * Ele projeta dados que já existem; não recalcula regra de negócio nem cria fonte de verdade
 * concorrente.
 */
interface AiCoachContextBuilder {
    suspend fun build(): AiCoachContext
}
