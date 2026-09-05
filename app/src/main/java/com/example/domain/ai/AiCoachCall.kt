package com.example.domain.ai

import com.example.domain.ai.model.AiCoachErrorKind
import com.example.domain.ai.model.AiCoachGatewayResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Política de tempo de uma chamada ao provider.
 *
 * Existe como função nomeada para que "chamada que não conclui vira
 * [AiCoachErrorKind.TIMEOUT]" seja verificável sem provider real. Nenhuma chamada do Coach pode
 * ficar pendurada: o teto é sempre finito e não há repetição automática.
 */
object AiCoachCall {

    suspend fun <T> withTimeout(
        timeoutMs: Long = AiModelConfig.REQUEST_TIMEOUT_MS,
        block: suspend () -> T
    ): Result<T> = try {
        Result.success(kotlinx.coroutines.withTimeout(timeoutMs) { block() })
    } catch (e: TimeoutCancellationException) {
        Result.failure(AiCoachTimeoutException(timeoutMs, e))
    }

    /** Converte um estouro de tempo em erro identificável para a UI. */
    fun timeoutError(error: AiCoachTimeoutException): AiCoachGatewayResult.Error =
        AiCoachGatewayResult.Error(
            kind = AiCoachErrorKind.TIMEOUT,
            detail = "sem resposta em ${error.timeoutMs} ms"
        )
}

/** Estouro do tempo máximo de uma chamada ao Coach. */
class AiCoachTimeoutException(
    val timeoutMs: Long,
    cause: Throwable? = null
) : Exception("Coach não respondeu em $timeoutMs ms", cause)
