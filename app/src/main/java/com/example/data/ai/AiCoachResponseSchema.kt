package com.example.data.ai

import com.google.firebase.ai.type.Schema
import com.example.domain.ai.AiCoachResponseValidator
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiRecommendationType
import com.example.domain.ai.model.WorkoutAdaptationType

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
        AiCoachRequestType.ADAPT_WORKOUT -> workoutAdaptationSchema
        // Os quatro EXPLAIN_* compartilham o schema: o que muda entre eles é o contexto enviado,
        // não a forma da resposta.
        AiCoachRequestType.EXPLAIN_RECOMMENDATION,
        AiCoachRequestType.EXPLAIN_WORKOUT,
        AiCoachRequestType.EXPLAIN_ADAPTATION,
        AiCoachRequestType.EXPLAIN_PROGRESS -> explanationSchema
    }

    /**
     * Contrato de forma de uma explicação.
     *
     * Não existe campo de evidência de propósito: evidência é fato do aplicativo, montado a
     * partir de `facts` do contexto, e não texto do modelo. `referencedExerciseIds` existe para
     * a citação de exercício ser verificável pelo validador.
     */
    val explanationSchema: Schema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(
                description = "Título curto da explicação, em português do Brasil, no máximo " +
                    "${AiCoachResponseValidator.MAX_TITLE_LENGTH} caracteres."
            ),
            "explanation" to Schema.string(
                description = "A explicação em no máximo dois parágrafos curtos, usando somente " +
                    "os dados do contexto."
            ),
            "limitations" to Schema.array(
                items = Schema.string(
                    description = "Uma limitação desta explicação, em uma frase."
                ),
                description = "Repita as limitações recebidas em knownLimitations e acrescente " +
                    "outras somente se o contexto as sustentar. Pode ser vazia.",
                maxItems = AiCoachResponseValidator.MAX_LIMITATIONS
            ),
            "referencedExerciseIds" to Schema.array(
                items = Schema.string(
                    description = "exerciseId copiado exatamente do contexto."
                ),
                description = "Todo exerciseId citado na explicação. Vazio quando nenhum " +
                    "exercício for citado."
            )
        )
    )

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

    /**
     * Contrato de forma de uma adaptação.
     *
     * Os campos numéricos são específicos por tipo e opcionais: o validador exige que apenas os
     * campos do tipo declarado venham preenchidos, para não existir mudança ambígua.
     */
    val workoutAdaptationSchema: Schema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(
                description = "Resumo curto da adaptação, em português do Brasil. Explique aqui " +
                    "quando não houver nenhuma mudança a propor."
            ),
            "changes" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "type" to Schema.enumeration(
                            values = WorkoutAdaptationType.entries.map { it.name },
                            description = "Tipo da mudança. Use somente os tipos listados em " +
                                "allowedChangeTypes do contexto."
                        ),
                        "exerciseId" to Schema.string(
                            description = "exerciseId de um exercício presente em template.exercises, " +
                                "copiado exatamente."
                        ),
                        "currentWeightKg" to Schema.double(
                            description = "ADJUST_LOAD: carga planejada hoje, exatamente como está no " +
                                "contexto. Nulo quando o treino não tem carga planejada.",
                            nullable = true
                        ),
                        "suggestedWeightKg" to Schema.double(
                            description = "ADJUST_LOAD: nova carga em kg.",
                            nullable = true
                        ),
                        "currentSets" to Schema.integer(
                            description = "ADJUST_SETS: séries de hoje, exatamente como no contexto.",
                            nullable = true
                        ),
                        "suggestedSets" to Schema.integer(
                            description = "ADJUST_SETS: novo número de séries.",
                            nullable = true
                        ),
                        "currentMinReps" to Schema.integer(
                            description = "ADJUST_REPS: mínimo da faixa de hoje.",
                            nullable = true
                        ),
                        "currentMaxReps" to Schema.integer(
                            description = "ADJUST_REPS: máximo da faixa de hoje.",
                            nullable = true
                        ),
                        "suggestedMinReps" to Schema.integer(
                            description = "ADJUST_REPS: novo mínimo da faixa.",
                            nullable = true
                        ),
                        "suggestedMaxReps" to Schema.integer(
                            description = "ADJUST_REPS: novo máximo da faixa, nunca menor que o mínimo.",
                            nullable = true
                        ),
                        "currentRestSeconds" to Schema.integer(
                            description = "ADJUST_REST: descanso de hoje em segundos.",
                            nullable = true
                        ),
                        "suggestedRestSeconds" to Schema.integer(
                            description = "ADJUST_REST: novo descanso em segundos.",
                            nullable = true
                        ),
                        "replacementExerciseId" to Schema.string(
                            description = "REPLACE_EXERCISE: exerciseId do substituto, copiado de " +
                                "replacementCandidates.",
                            nullable = true
                        ),
                        "reason" to Schema.string(
                            description = "Em uma frase, por que esta mudança."
                        ),
                        "evidence" to Schema.string(
                            description = "O dado do contexto que sustenta a mudança. Obrigatório."
                        ),
                        "confidence" to Schema.double(
                            description = "Confiança entre 0.0 e 1.0.",
                            minimum = 0.0,
                            maximum = 1.0
                        )
                    ),
                    optionalProperties = listOf(
                        "currentWeightKg",
                        "suggestedWeightKg",
                        "currentSets",
                        "suggestedSets",
                        "currentMinReps",
                        "currentMaxReps",
                        "suggestedMinReps",
                        "suggestedMaxReps",
                        "currentRestSeconds",
                        "suggestedRestSeconds",
                        "replacementExerciseId"
                    )
                ),
                description = "Mudanças propostas. Pode ser vazia quando não há o que ajustar.",
                maxItems = AiCoachResponseValidator.MAX_ADAPTATION_CHANGES
            ),
            "dataQuality" to Schema.obj(
                properties = mapOf(
                    "level" to Schema.enumeration(
                        values = AiDataQualityLevel.entries.map { it.name },
                        description = "Nunca maior que evidence.maxDataQuality do contexto."
                    ),
                    "description" to Schema.string(
                        description = "Em uma frase, no que a adaptação se baseou."
                    )
                )
            )
        )
    )
}
