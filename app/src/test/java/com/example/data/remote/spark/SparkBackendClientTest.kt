package com.example.data.remote.spark

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O teto de leitura por chamada (T18.3.1).
 *
 * O Coach precisa de uma política própria (`AiModelConfig.HTTP_READ_TIMEOUT_SECONDS`) sem alterar
 * o teto padrão que sync, backup, social, mídia e auth continuam usando — todos passam pelo
 * mesmo [SparkBackendClient]. Nenhum teste aqui abre socket: um interceptor terminal lê
 * [okhttp3.Interceptor.Chain.readTimeoutMillis], que reflete o timeout **efetivo** que o OkHttp
 * aplicaria àquela chamada específica.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkBackendClientTest {

    @Test
    fun `sem override o teto de leitura e o padrao do cliente compartilhado`() {
        val observed = mutableListOf<Int>()
        val backend = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(),
            httpClient = clientCapturingReadTimeout(observed)
        )

        kotlinx.coroutines.runBlocking { backend.postJson("v1/sync/push", "{}") }

        assertEquals(listOf(defaultReadTimeoutMillis()), observed)
    }

    @Test
    fun `com override o teto de leitura e o da chamada, nao o padrao compartilhado`() {
        val observed = mutableListOf<Int>()
        val backend = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(),
            httpClient = clientCapturingReadTimeout(observed)
        )

        kotlinx.coroutines.runBlocking {
            backend.postJson("v1/ai/coach", "{}", readTimeoutSeconds = COACH_READ_TIMEOUT_SECONDS)
        }

        assertEquals(listOf(TimeUnit.SECONDS.toMillis(COACH_READ_TIMEOUT_SECONDS).toInt()), observed)
    }

    @Test
    fun `o override do Coach nao vaza para a chamada comum seguinte`() {
        val observed = mutableListOf<Int>()
        val backend = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(),
            httpClient = clientCapturingReadTimeout(observed)
        )

        kotlinx.coroutines.runBlocking {
            backend.postJson("v1/ai/coach", "{}", readTimeoutSeconds = COACH_READ_TIMEOUT_SECONDS)
            backend.postJson("v1/social/friends", "{}")
            backend.getJson("v1/backups/latest")
        }

        assertEquals(
            listOf(
                TimeUnit.SECONDS.toMillis(COACH_READ_TIMEOUT_SECONDS).toInt(),
                defaultReadTimeoutMillis(),
                defaultReadTimeoutMillis()
            ),
            observed
        )
    }

    @Test
    fun `o teto do Coach e maior que o teto padrao das outras chamadas`() {
        // A regressão real (T18.3.1): o app declarava um teto do Coach maior no papel
        // (AiModelConfig) mas o transporte de verdade usava o padrão compartilhado, menor.
        assertEquals(
            true,
            COACH_READ_TIMEOUT_SECONDS * 1_000 > SparkBackendClient.READ_TIMEOUT_SECONDS * 1_000
        )
    }

    private fun defaultReadTimeoutMillis(): Int =
        TimeUnit.SECONDS.toMillis(SparkBackendClient.READ_TIMEOUT_SECONDS).toInt()

    private fun clientCapturingReadTimeout(observed: MutableList<Int>): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(SparkBackendClient.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(SparkAuthInterceptor(TokenProvider()))
            .addInterceptor(
                Interceptor { chain ->
                    observed += chain.readTimeoutMillis()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("stub")
                        .body("{}".toResponseBody(null))
                        .build()
                }
            )
            .build()

    private class TokenProvider : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult =
            AuthTokenResult.Token("token-abc")
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"

        /** Mesmo valor de `AiModelConfig.HTTP_READ_TIMEOUT_SECONDS` — ver `AiModelConfigTest`. */
        const val COACH_READ_TIMEOUT_SECONDS = 75L
    }
}
