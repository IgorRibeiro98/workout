package com.example.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cadeia de timeout do Coach (T18.3.1): provider do backend < HTTP do Coach no Android <
 * teto absoluto da operação no Android.
 *
 * Quebrar essa relação foi a causa raiz observada em produção: o Android desistia (transporte,
 * 20 s) antes de o backend legitimamente terminar de esperar o Gemini (30 s), e o socket caindo
 * virava "precisa de internet" com internet real. `AiModelConfig.HTTP_READ_TIMEOUT_SECONDS`
 * também existia sem ser lido em lugar nenhum — este teste, sozinho, não pegaria isso (é
 * [com.example.data.remote.spark.SparkBackendClientTest] quem prova que o valor é aplicado de
 * verdade); o que ele vigia é a relação numérica em si.
 *
 * O backend está em outra linguagem e não compartilha código com o Android: o espelho abaixo
 * (`BACKEND_AI_TIMEOUT_MS`) precisa ser mantido igual a `AI_TIMEOUT_MS` em
 * `backend/src/config/env.schema.ts` manualmente. `backend/test/ai-config.spec.ts` tem o teste
 * simétrico do lado do servidor.
 */
class AiModelConfigTest {

    @Test
    fun `os tetos declarados sao os da politica atual`() {
        assertEquals(75L, AiModelConfig.HTTP_READ_TIMEOUT_SECONDS)
        assertEquals(90_000L, AiModelConfig.REQUEST_TIMEOUT_MS)
    }

    @Test
    fun `provider do backend menor que HTTP do Coach menor que absoluto do Android`() {
        val providerMs = BACKEND_AI_TIMEOUT_MS
        val httpMs = AiModelConfig.HTTP_READ_TIMEOUT_SECONDS * 1_000
        val absoluteMs = AiModelConfig.REQUEST_TIMEOUT_MS

        assertTrue(
            "provider do backend ($providerMs ms) precisa ser menor que o HTTP do Coach ($httpMs ms)",
            providerMs < httpMs
        )
        assertTrue(
            "HTTP do Coach ($httpMs ms) precisa ser menor que o absoluto do Android ($absoluteMs ms)",
            httpMs < absoluteMs
        )
    }

    private companion object {
        /**
         * Espelha `AI_TIMEOUT_MS` (default **e** máximo aceito) em
         * `backend/src/config/env.schema.ts`. Sem automação compartilhando os três números entre
         * Kotlin e TypeScript — a invariante é vigiada por um teste local de cada lado.
         */
        const val BACKEND_AI_TIMEOUT_MS = 60_000L
    }
}
