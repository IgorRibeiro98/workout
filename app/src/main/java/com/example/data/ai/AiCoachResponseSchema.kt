package com.example.data.ai

import com.google.firebase.ai.type.Schema
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiRecommendationType

/**
 * Schemas explícitos do structured output, um por tipo de request.
 *
 * Eles são o contrato de forma com o provider; a validação semântica continua sendo obrigatória
 * em [AiCoachResponseValidator], porque schema garante formato, não veracidade — nem que o
 * `exerciseId` exista, nem que ele estivesse entre os candidatos, nem que a evidência corresponda
 * ao que o app enviou.
 */
internal object AiCoachResponseSchema {

    /** O schema de saída do tipo de request pedido. */
    fun forType(type: AiCoachRequestType): Schema = when (type) {
        AiCoachRequestType.ANALYZE_WORKOUT -> schema
        AiCoachRequestType.GENERATE_WORKOUT -> generatedWorkoutSchema
    }

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

    /** Contrato de forma de um treino proposto. Espelha o que o domínio do Spark sabe guardar. */
    val generatedWorkoutSchema: Schema = Schema.obj(
        properties = mapOf(
            "name" to Schema.string(
                description = "Nome curto do treino, em português do Brasil, no máximo " +
                    "${AiCoachResponseValidator.MAX_WORKOUT_NAME_LENGTH} caracteres."
            ),
            "exercises" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "exerciseId" to Schema.string(
                            description = "exerciseId copiado exatamente de candidateExercises. " +
                                "Nenhum outro valor é aceito."
                        ),
                        "order" to Schema.integer(
                            description = "Posição do exercício no treino, de 1 até a quantidade " +
                                "de exercícios, sem repetir e sem pular."
                        ),
                        "sets" to Schema.integer(
                            description = "Número de séries, de ${AiCoachResponseValidator.MIN_SETS} " +
                                "a ${AiCoachResponseValidator.MAX_SETS}."
                        ),
                        "minReps" to Schema.integer(
                            description = "Menor repetição da faixa alvo, de " +
                                "${AiCoachResponseValidator.MIN_REPS} a ${AiCoachResponseValidator.MAX_REPS}."
                        ),
                        "maxReps" to Schema.integer(
                            description = "Maior repetição da faixa alvo, nunca menor que minReps."
                        ),
                        "restSeconds" to Schema.integer(
                            description = "Descanso entre séries em segundos, de " +
                                "${AiCoachResponseValidator.MIN_REST_SECONDS} a " +
                                "${AiCoachResponseValidator.MAX_REST_SECONDS}."
                        ),
                        "weightKg" to Schema.double(
                            description = "Carga sugerida em kg. Só preencha quando o exercício " +
                                "aparecer em loadEvidence com carga registrada; caso contrário, nulo.",
                            nullable = true
                        ),
                        "reason" to Schema.string(
                            description = "Em uma frase, por que este exercício está aqui."
                        )
                    ),
                    optionalProperties = listOf("weightKg")
                ),
                description = "Exercícios do treino proposto. Vazio somente quando " +
                    "insufficientCandidates for verdadeiro.",
                maxItems = AiCoachResponseValidator.MAX_GENERATED_EXERCISES
            ),
            "explanation" to Schema.string(
                description = "Em poucas frases, por que o treino foi montado assim."
            ),
            "insufficientCandidates" to Schema.boolean(
                description = "Verdadeiro quando os exercícios candidatos não sustentam o pedido. " +
                    "Nesse caso, exercises precisa estar vazio."
            )
        )
    )
}
