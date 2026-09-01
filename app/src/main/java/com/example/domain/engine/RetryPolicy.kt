package com.example.domain.engine

import android.util.Log
import com.example.data.remote.NetworkResult
import kotlinx.coroutines.delay

object RetryPolicy {

    suspend fun <T> executeWithRetry(
        maxAttempts: Int = 3,
        rateLimiter: ExerciseDbRateLimiter,
        exerciseName: String,
        onRetryAttempt: (attempt: Int, maxAttempts: Int, delaySeconds: Long, reason: String) -> Unit = { _, _, _, _ -> },
        block: suspend () -> NetworkResult<T>
    ): NetworkResult<T> {
        var attempt = 1
        var delayMs = 2000L

        while (attempt <= maxAttempts) {
            rateLimiter.acquire()
            Log.d("RetryPolicy", "[SYNC ATTEMPT] $exerciseName - Tentativa $attempt/$maxAttempts")
            
            val result = block()

            when (result) {
                is NetworkResult.Success -> {
                    return result
                }
                is NetworkResult.NotFound -> {
                    return result
                }
                is NetworkResult.Offline -> {
                    return result
                }
                is NetworkResult.HttpError -> {
                    val isRateLimit = result.code == 429
                    val isServerError = result.code in 500..504

                    if (isRateLimit) {
                        val cooldownSec = if (attempt == 1) 5L else 10L
                        Log.w(
                            "RetryPolicy",
                            "[SYNC RATE_LIMIT] Exercise: $exerciseName, Status: 429, Tentativa: $attempt/$maxAttempts, Aguardando: ${cooldownSec}s"
                        )
                        onRetryAttempt(attempt, maxAttempts, cooldownSec, "HTTP 429 Too Many Requests")
                        rateLimiter.cooldown(cooldownSec)
                    } else if (isServerError && attempt < maxAttempts) {
                        val waitSec = delayMs / 1000
                        Log.w(
                            "RetryPolicy",
                            "[SYNC SERVER_ERROR] Exercise: $exerciseName, Status: ${result.code}, Tentativa: $attempt/$maxAttempts, Aguardando: ${waitSec}s"
                        )
                        onRetryAttempt(attempt, maxAttempts, waitSec, "HTTP ${result.code}")
                        delay(delayMs)
                        delayMs *= 2
                    } else {
                        if (attempt == maxAttempts || (!isRateLimit && !isServerError)) {
                            Log.e("RetryPolicy", "[SYNC ERROR] Exercise: $exerciseName, Status: ${result.code}, Excedido tentativas")
                            return result
                        }
                    }
                }
                is NetworkResult.ParserError -> {
                    if (attempt < maxAttempts) {
                        val waitSec = delayMs / 1000
                        Log.w(
                            "RetryPolicy",
                            "[SYNC PARSER_ERROR] Exercise: $exerciseName, Erro: ${result.throwable.message}, Tentativa: $attempt/$maxAttempts, Aguardando: ${waitSec}s"
                        )
                        onRetryAttempt(attempt, maxAttempts, waitSec, result.throwable.message ?: "Erro de parsing")
                        delay(delayMs)
                        delayMs *= 2
                    } else {
                        Log.e("RetryPolicy", "[SYNC ERROR] Exercise: $exerciseName, Erro final: ${result.throwable.message}")
                        return result
                    }
                }
            }

            attempt++
        }

        return NetworkResult.ParserError(Exception("Excedido número máximo de tentativas ($maxAttempts)"))
    }
}
