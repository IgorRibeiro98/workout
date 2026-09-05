package com.example.data.ai

import com.google.firebase.ai.type.Schema
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiRecommendationType

/**
 * Schema explícito do structured output.
 *
 * Ele é o contrato de forma com o provider; a validação semântica continua sendo obrigatória em
 * [AiCoachResponseValidator], porque schema garante formato, não veracidade — nem que o
 * `exerciseId` exista, nem que a evidência corresponda ao histórico enviado.
 */
internal object AiCoachResponseSchema {

    private fun observationSchema(description: String): Schema = Schema.array(
        items = Schema.obj(
            properties = mapOf(
                "exerciseId" to Schema.string(
                    description = "exerciseId exatamente como recebido no contexto, ou nulo " +
                        "quando a observação for do treino como um todo.",
                    nullable = true
                ),
                "title" to Schema.string(description = "Rótulo curto da observação."),
                "description" to Schema.string(
                    description = "O fato observado nos dados, sem interpretação."
                )
            ),
            optionalProperties = listOf("exerciseId")
        ),
        description = description,
        maxItems = AiCoachResponseValidator.MAX_OBSERVATIONS
    )

    val schema: Schema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(
                description = "Resumo curto da análise, em português do Brasil."
            ),
            "positiveSignals" to observationSchema("O que os dados mostram de positivo. Podem ser zero."),
            "attentionPoints" to observationSchema("O que merece atenção nos dados. Podem ser zero."),
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
                        ),
                        "evidence" to Schema.string(
                            description = "Dado do contexto que sustenta a recomendação. " +
                                "Obrigatório quando houver exerciseId.",
                            nullable = true
                        )
                    ),
                    optionalProperties = listOf("exerciseId", "evidence")
                ),
                description = "Sugestões do Coach. Podem ser zero.",
                maxItems = AiCoachResponseValidator.MAX_RECOMMENDATIONS
            ),
            "dataQuality" to Schema.obj(
                properties = mapOf(
                    "level" to Schema.enumeration(
                        values = AiDataQualityLevel.entries.map { it.name },
                        description = "Nunca maior que evidence.maxDataQuality do contexto."
                    ),
                    "description" to Schema.string(
                        description = "Em uma frase, no que a análise se baseou."
                    )
                )
            )
        )
    )
}
