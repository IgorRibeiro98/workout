package com.example.domain.ai.eval

import com.example.domain.ai.model.AiCoachRequestType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A suíte de avaliação do Coach IA.
 *
 * ```bash
 * ./gradlew :app:testDebugUnitTest --tests "com.example.domain.ai.eval.*"
 * ```
 *
 * Ela roda **offline**, com [com.example.domain.ai.FakeAiCoachGateway] e fixtures conhecidas:
 * não chama Gemini, não precisa de Firebase, não usa chave de API e não consome cota — nem aqui,
 * nem no build, nem em CI. Avaliação com provider real é um caminho separado e explícito
 * (`app/src/androidTest/.../RealProviderEvaluationTest`), que nunca roda sozinho.
 *
 * O que ela protege: mudou prompt, schema, context builder, validador ou configuração de modelo,
 * e um comportamento do Coach regrediu — a suíte falha dizendo qual cenário e qual propriedade.
 */
class AiCoachEvaluationSuiteTest {

    @Test
    fun `todos os cenarios deterministicos passam`() = runTest {
        val failures = mutableListOf<String>()

        for (scenario in AiCoachEvaluationScenarios.SCENARIOS) {
            try {
                scenario.check()
            } catch (e: Throwable) {
                failures += "${scenario.id} [${scenario.requestType}]: ${e.message}"
            }
        }

        assertTrue(
            "cenários da suíte de avaliação falharam:\n" + failures.joinToString("\n") { " - $it" },
            failures.isEmpty()
        )
    }

    @Test
    fun `a suite cobre todos os tipos de request implementados`() {
        val covered = AiCoachEvaluationScenarios.SCENARIOS.map { it.requestType }.toSet()
        val missing = AiCoachRequestType.entries.filterNot { it in covered }

        assertTrue("tipos de request sem cenário: $missing", missing.isEmpty())
    }

    @Test
    fun `a suite cobre todas as dimensoes declaradas`() {
        val covered = AiCoachEvaluationScenarios.SCENARIOS.flatMap { it.dimensions }.toSet()
        val missing = AiCoachEvaluationDimension.entries.filterNot { it in covered }

        assertTrue("dimensões sem cenário: $missing", missing.isEmpty())
    }

    @Test
    fun `cada cenario tem identificador unico`() {
        val ids = AiCoachEvaluationScenarios.SCENARIOS.map { it.id }

        assertEquals("há cenários com o mesmo id", ids.size, ids.distinct().size)
    }
}
