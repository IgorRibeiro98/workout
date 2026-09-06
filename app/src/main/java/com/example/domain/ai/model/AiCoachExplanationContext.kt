package com.example.domain.ai.model

import kotlinx.serialization.Serializable

/**
 * Um dado que o app usou para chegar à conclusão que está sendo explicada.
 *
 * Existe como par rotulado, e não como frase pronta, porque é a mesma lista que vai ao modelo e
 * que a UI mostra em "Dados considerados". O usuário lê exatamente o que foi enviado.
 */
@Serializable
data class AiExplanationFact(
    val label: String,
    val value: String
) {
    /** A linha como a UI a exibe. */
    val line: String
        get() = "$label: $value"
}

/**
 * Contexto de uma **explicação**. O menor contexto suficiente, nunca o maior possível.
 *
 * Cada origem preenche apenas os campos que lhe pertencem: uma explicação de adaptação leva o
 * exercício em foco e o histórico dele; uma explicação de treino gerado leva os exercícios
 * propostos e o pedido. Catálogo inteiro, perfil inteiro, conquistas, outros treinos e histórico
 * de outros exercícios **não entram** em nenhuma delas.
 *
 * As projeções são reaproveitadas de T14.1/T14.2/T14.3 ([AiExerciseHistoryContext],
 * [AiPlannedExerciseContext]): não existe uma quarta representação de exercício ou histórico.
 */
@Serializable
data class AiCoachExplanationContext(
    val origin: String,
    /** Identidade do objeto explicado. Nunca o texto visível. */
    val contextId: String,
    /** O que está sendo explicado, em uma frase montada pelo app. */
    val subject: String,
    /** Os dados que sustentam a conclusão. É isto que o usuário verá como evidência. */
    val facts: List<AiExplanationFact> = emptyList(),
    /** O exercício em foco, quando a explicação é sobre um. */
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    /** Valor atual e sugerido, quando o alvo é uma mudança proposta. */
    val currentValue: String? = null,
    val suggestedValue: String? = null,
    /** A razão e a evidência que o app já possui. O modelo reorganiza; não substitui. */
    val reason: String? = null,
    val evidence: String? = null,
    /** Histórico real do exercício em foco, e somente dele. */
    val exerciseHistory: AiExerciseHistoryContext? = null,
    /** Os exercícios do treino em foco, quando a explicação é sobre a estrutura dele. */
    val workoutExercises: List<AiPlannedExerciseContext> = emptyList(),
    /** Quanta evidência sustenta a conclusão de origem. O modelo não pode declarar mais. */
    val dataQuality: AiDataQualityLevel? = null,
    /** As limitações que o app já reconhece. O modelo não pode contradizê-las. */
    val knownLimitations: List<String> = emptyList()
) {
    /**
     * Todos os `exerciseId` que a explicação tem permissão de citar.
     *
     * Mesma regra da análise e da geração: id fora daqui é invenção e invalida a resposta.
     */
    val knownExerciseIds: Set<String>
        get() = buildSet {
            exerciseId?.let { add(it) }
            exerciseHistory?.let { add(it.exerciseId) }
            workoutExercises.forEach { add(it.exerciseId) }
        }
}

/**
 * Contrato de ida de uma explicação.
 *
 * Mesmo formato dos outros três requests — id, versão e tipo —, com o contexto da explicação.
 * [type] é sempre um dos `EXPLAIN_*`; o tipo diz o que está sendo explicado, e o schema de saída
 * é o mesmo para os quatro.
 */
@Serializable
data class AiCoachExplanationRequest(
    val requestId: String,
    val schemaVersion: Int,
    val type: AiCoachRequestType,
    val context: AiCoachExplanationContext
) {
    init {
        // O contrato read-only é estrutural: um request de explicação não consegue carregar
        // ANALYZE/GENERATE/ADAPT nem por engano de call site.
        require(type.isExplanation) { "AiCoachExplanationRequest exige um tipo EXPLAIN_*: $type" }
    }
}

/**
 * Resposta crua de uma explicação, já desserializada do structured output.
 *
 * Deliberadamente **não** há campo de evidência: evidência é fato do app, não texto do modelo. O
 * app monta a lista a partir de [AiCoachExplanationContext.facts], e o mesmo bloco aparece com ou
 * sem IA. [referencedExerciseIds] existe para a citação de exercício ser verificável.
 */
@Serializable
data class AiCoachExplanationResponse(
    val title: String = "",
    val explanation: String = "",
    /** O que a explicação não sustenta. Pode ser vazio. */
    val limitations: List<String> = emptyList(),
    /** Todo exerciseId citado no texto. Precisa existir no contexto. */
    val referencedExerciseIds: List<String> = emptyList()
)

/** Resultado bruto do provider para uma explicação, antes da validação semântica. */
sealed interface AiCoachExplanationGatewayResult {
    data class Success(
        val response: AiCoachExplanationResponse,
        val metadata: AiCoachCallMetadata = AiCoachCallMetadata.Unknown
    ) : AiCoachExplanationGatewayResult
    data class Error(
        val kind: AiCoachErrorKind,
        val detail: String? = null
    ) : AiCoachExplanationGatewayResult
}
