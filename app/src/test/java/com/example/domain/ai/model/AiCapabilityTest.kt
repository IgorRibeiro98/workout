package com.example.domain.ai.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapeamento operação do Coach → capability (T19.0 §6.5). Lógica pura, sem Robolectric.
 */
class AiCapabilityTest {

    @Test
    fun `ANALYZE_WORKOUT exige AI_ANALYZE_WORKOUT`() {
        assertEquals(AiCapability.AI_ANALYZE_WORKOUT, AiCoachRequestType.ANALYZE_WORKOUT.requiredCapability)
    }

    @Test
    fun `GENERATE_WORKOUT exige AI_GENERATE_WORKOUT`() {
        assertEquals(AiCapability.AI_GENERATE_WORKOUT, AiCoachRequestType.GENERATE_WORKOUT.requiredCapability)
    }

    @Test
    fun `ADAPT_WORKOUT exige AI_ADAPT_WORKOUT`() {
        assertEquals(AiCapability.AI_ADAPT_WORKOUT, AiCoachRequestType.ADAPT_WORKOUT.requiredCapability)
    }

    @Test
    fun `os quatro EXPLAIN_* exigem a mesma capability AI_EXPLAIN`() {
        val explainTypes = listOf(
            AiCoachRequestType.EXPLAIN_RECOMMENDATION,
            AiCoachRequestType.EXPLAIN_WORKOUT,
            AiCoachRequestType.EXPLAIN_ADAPTATION,
            AiCoachRequestType.EXPLAIN_PROGRESS
        )
        explainTypes.forEach { type ->
            assertEquals(AiCapability.AI_EXPLAIN, type.requiredCapability)
        }
    }

    @Test
    fun `toda operacao do contrato tem uma capability mapeada`() {
        // when exaustivo: este teste falha ao compilar, não em runtime, se uma operação nova
        // ficar sem entrada em `requiredCapability` — a asserção é só para o teste existir.
        AiCoachRequestType.entries.forEach { type ->
            assertEquals(true, AiCapability.entries.contains(type.requiredCapability))
        }
    }
}
