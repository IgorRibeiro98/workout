package com.example.domain.ai.eval

import com.example.domain.ai.AiCoachContextBuilder
import com.example.domain.ai.AiModelConfig
import com.example.domain.ai.AiWorkoutAdaptationContextBuilder
import com.example.domain.ai.AiWorkoutAdaptationSource
import com.example.domain.ai.AiWorkoutGenerationContextBuilder
import com.example.domain.ai.model.AiCandidateExerciseContext
import com.example.domain.ai.model.AiCoachContext
import com.example.domain.ai.model.AiCoachRequestType
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutGenerationContext
import com.example.domain.ai.model.WorkoutGenerationPreferences

/**
 * As dimensões que a suíte de avaliação cobre.
 *
 * Elas existem para que a pergunta "o que esta suíte garante?" tenha resposta enumerável, e para
 * que um cenário novo declare o que está protegendo em vez de virar mais um teste solto.
 */
enum class AiCoachEvaluationDimension {
    /** Dado e identificador não são inventados. */
    GROUNDING,

    /** O contrato estruturado é respeitado. */
    SCHEMA,

    /** JSON válido não contorna as regras do domínio. */
    SEMANTIC,

    /** O Coach não assume papel clínico. */
    SAFETY,

    /** A IA não escreve onde não deve. */
    SIDE_EFFECTS,

    /** Nenhuma chamada automática, nenhum retry, nenhum laço. */
    COST,

    /** Só dado relevante, dentro dos limites declarados. */
    CONTEXT,

    /** Timeout, limite de uso, provider fora do ar e offline têm resultado controlado. */
    RESILIENCE,

    /** Modelo, prompt e schema são rastreáveis. */
    VERSIONING,

    /** Log não carrega payload sensível. */
    PRIVACY,

    /** O core funciona sem IA. */
    LOCAL_FIRST
}

/**
 * Um cenário determinístico da suíte.
 *
 * [check] roda o fluxo real do Coach contra o [com.example.domain.ai.FakeAiCoachGateway] e faz as
 * asserções do cenário. Nenhuma delas compara texto literal do modelo: o que se verifica são
 * propriedades — identificador conhecido, identificador autorizado, faixa numérica, campo
 * obrigatório, contagem de chamadas e ausência de efeito colateral.
 */
class AiCoachEvaluationScenario(
    val id: String,
    val requestType: AiCoachRequestType,
    val dimensions: Set<AiCoachEvaluationDimension>,
    val check: suspend () -> Unit
)

/**
 * Asserções reutilizadas pelos cenários.
 *
 * Existem para não repetir a mesma verificação em vinte lugares — e para que "id autorizado" e
 * "faixa válida" signifiquem exatamente a mesma coisa em todos os fluxos.
 */
object AiCoachEvaluationAssertions {

    /** Todo id citado precisa existir no contexto que o app enviou. */
    fun assertKnownExerciseIds(cited: Collection<String?>, known: Set<String>, where: String) {
        val invented = cited.filterNotNull().filter { it !in known }
        assertTrue(invented.isEmpty(), "$where citou exerciseId fora do contexto: $invented")
    }

    /** Nos fluxos com candidate set, id existente não basta: ele precisava estar autorizado. */
    fun assertAuthorizedExerciseIds(
        cited: Collection<String?>,
        authorized: Set<String>,
        where: String
    ) {
        val unauthorized = cited.filterNotNull().filter { it !in authorized }
        assertTrue(
            unauthorized.isEmpty(),
            "$where usou exerciseId que não estava autorizado nesta requisição: $unauthorized"
        )
    }

    /** Número que chega ao domínio precisa ser finito e estar na faixa do app. */
    fun assertFiniteInRange(value: Double, min: Double, max: Double, where: String) {
        assertFalse(value.isNaN(), "$where não é numérico")
        assertFalse(value.isInfinite(), "$where é infinito")
        assertTrue(value in min..max, "$where fora de $min..$max: $value")
    }

    /** Campo obrigatório presente e não vazio. */
    fun assertPresent(value: String?, where: String) {
        assertTrue(!value.isNullOrBlank(), "$where ausente")
    }

    /**
     * O contexto serializado não pode carregar dado que aquele request não precisa.
     *
     * A verificação é sobre os **campos** do JSON, não sobre o texto inteiro: um valor legítimo
     * que por acaso contenha a palavra não é vazamento, um campo com aquele nome é.
     */
    fun assertContextOmits(serializedContext: String, forbidden: List<String>, where: String) {
        val fields = JSON_FIELD.findAll(serializedContext).map { it.groupValues[1] }.toList()
        val leaked = fields.filter { field ->
            forbidden.any { field.contains(it, ignoreCase = true) }
        }.distinct()
        assertTrue(leaked.isEmpty(), "$where enviou campo irrelevante: $leaked")
    }

    private val JSON_FIELD = Regex("\"([A-Za-z0-9_]+)\"\\s*:")

    /** Uma ação explícita produz no máximo uma chamada ao provider. */
    fun assertCallCount(expected: Int, actual: Int, where: String) {
        assertEquals(expected, actual, "$where fez $actual chamadas ao provider (esperado $expected)")
    }

    /** Campos que nenhum contexto do Coach precisa: gamificação nunca vai para o modelo. */
    val GAMIFICATION_FIELDS: List<String> = listOf(
        "xp", "level", "streak", "achievement", "conquista", "mission", "missao", "missão"
    )
}

/** Contexto de análise entregue pronto; o IO real já tem cobertura própria. */
class EvaluationAnalysisContextBuilder(private val context: AiCoachContext) : AiCoachContextBuilder {
    override suspend fun build(): AiCoachContext = context
}

/** Contexto de geração entregue pronto, respeitando os tetos declarados em [AiModelConfig]. */
class EvaluationGenerationContextBuilder(
    private val context: AiWorkoutGenerationContext
) : AiWorkoutGenerationContextBuilder {
    override suspend fun candidates(
        preferences: WorkoutGenerationPreferences
    ): List<AiCandidateExerciseContext> = context.candidateExercises

    override suspend fun build(preferences: WorkoutGenerationPreferences): AiWorkoutGenerationContext =
        context
}

/** Contexto de adaptação entregue pronto, com a revisão do treino daquele instante. */
class EvaluationAdaptationContextBuilder(
    private val templateId: Long,
    private val revision: String,
    private val context: AiWorkoutAdaptationContext?
) : AiWorkoutAdaptationContextBuilder {
    override suspend fun build(templateId: Long): AiWorkoutAdaptationSource? =
        context?.let { AiWorkoutAdaptationSource(this.templateId, revision, it) }
}

// ------------------------------------------------------------------------------------------
// Asserções com mensagem por último.
//
// JUnit espera a mensagem como primeiro argumento; os cenários ficam mais legíveis com ela no
// fim, junto do porquê. Estas funções são só a ponte — a falha continua sendo do JUnit.
// ------------------------------------------------------------------------------------------

internal fun assertTrue(condition: Boolean, message: String) =
    org.junit.Assert.assertTrue(message, condition)

internal fun assertFalse(condition: Boolean, message: String) =
    org.junit.Assert.assertFalse(message, condition)

internal fun assertEquals(expected: Any?, actual: Any?) =
    org.junit.Assert.assertEquals(expected, actual)

internal fun assertEquals(expected: Any?, actual: Any?, message: String) =
    org.junit.Assert.assertEquals(message, expected, actual)
