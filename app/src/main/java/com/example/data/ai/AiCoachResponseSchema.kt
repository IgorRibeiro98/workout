package com.example.data.ai

import com.google.firebase.ai.type.Schema
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.model.AiRecommendationType

/**
 * Schema explícito do structured output.
 *
 * Ele é o contrato de forma com o provider; a validação semântica continua sendo obrigatória em
 * [AiCoachResponseValidator], porque schema garante formato, não veracidade.
 */
internal object AiCoachResponseSchema {

    val schema: Schema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(
                description = "Resumo curto da análise, em português do Brasil."
            ),
            "recommendations" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "type" to Schema.enumeration(
                            values = AiRecommendationType.entries.map { it.name },
                            description = "Tipo da recomendação."
                        ),
                        "exerciseId" to Schema.string(
                            description = "exerciseId exatamente como recebido no contexto, ou nulo " +
                                "quando a recomendação for geral.",
                            nullable = true
                        ),
                        "reason" to Schema.string(
                            description = "Justificativa curta baseada apenas nos dados fornecidos."
                        ),
                        "confidence" to Schema.double(
                            description = "Confiança entre 0.0 e 1.0.",
                            minimum = 0.0,
                            maximum = 1.0
                        )
                    ),
                    optionalProperties = listOf("exerciseId")
                ),
                description = "Sugestões do Coach. Podem ser zero.",
                maxItems = AiCoachResponseValidator.MAX_RECOMMENDATIONS
            )
        )
    )
}
