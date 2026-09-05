package com.example.domain.ai

import com.example.domain.ai.model.AiCoachErrorKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nenhuma chamada ao Coach pode ficar pendurada, e nenhuma pode se repetir sozinha.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachCallTest {

    @Test
    fun `chamada que nao conclui vira TIMEOUT`() = runTest {
        var attempts = 0

        val call = AiCoachCall.withTimeout(timeoutMs = 30_000L) {
            attempts++
            delay(Long.MAX_VALUE / 2)
            "nunca chega"
        }

        val error = call.exceptionOrNull() as AiCoachTimeoutException
        assertEquals(30_000L, error.timeoutMs)
        assertEquals(AiCoachErrorKind.TIMEOUT, AiCoachCall.timeoutError(error).kind)
        assertEquals("timeout não pode gerar nova tentativa automática", 1, attempts)
    }

    @Test
    fun `chamada que conclui dentro do tempo devolve o resultado`() = runTest {
        val call = AiCoachCall.withTimeout(timeoutMs = 30_000L) {
            delay(10)
            "ok"
        }

        assertEquals("ok", call.getOrNull())
    }

    @Test
    fun `o teto padrao e finito e positivo`() {
        assertTrue(AiModelConfig.REQUEST_TIMEOUT_MS in 1..120_000L)
    }
}
