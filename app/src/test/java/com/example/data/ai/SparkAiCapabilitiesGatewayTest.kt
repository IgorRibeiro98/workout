package com.example.data.ai

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.ai.model.AiCapabilitiesErrorKind
import com.example.domain.ai.model.AiCapabilitiesGatewayResult
import com.example.domain.ai.model.AiCapability
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fronteira com `GET /v1/account/capabilities` (T19.0).
 *
 * Mesma técnica de [SparkBackendAiCoachGatewayTest]: um interceptor terminal no lugar da rede.
 * Além do mapeamento de status, esta suíte prova o requisito §17/§18 do lado do parser — uma
 * capability que este build não reconhece é ignorada, nunca tratada como liberada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkAiCapabilitiesGatewayTest {

    // ---------------------------------------------------------------------------- sem conta

    @Test
    fun `sem conta conectada nao faz requisicao nenhuma`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val result = gateway.fetch() as AiCapabilitiesGatewayResult.Error

        assertEquals(AiCapabilitiesErrorKind.AUTH_REQUIRED, result.kind)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `sem endereco de backend fica indisponivel, sem rede`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, baseUrl = "")

        val result = gateway.fetch() as AiCapabilitiesGatewayResult.Error

        assertEquals(AiCapabilitiesErrorKind.UNAVAILABLE, result.kind)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `sem cliente configurado fica indisponivel`() = runBlocking {
        val result = SparkAiCapabilitiesGateway(client = null).fetch()

        assertEquals(
            AiCapabilitiesErrorKind.UNAVAILABLE,
            (result as AiCapabilitiesGatewayResult.Error).kind
        )
    }

    // -------------------------------------------------------------------------- com conta

    @Test
    fun `com conta a requisicao sai autenticada, GET no caminho certo`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = envelope("AI_ANALYZE_WORKOUT" to true))

        gateway.fetch()

        val request = sent.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/v1/account/capabilities", request.url.toString())
        assertEquals("Bearer token-abc", request.header("Authorization"))
    }

    @Test
    fun `reflete exatamente o que o servidor liberou`() = runBlocking {
        val gateway = gatewayWith(
            mutableListOf(),
            body = envelope(
                "AI_ANALYZE_WORKOUT" to true,
                "AI_GENERATE_WORKOUT" to false,
                "AI_ADAPT_WORKOUT" to true,
                "AI_EXPLAIN" to false
            )
        )

        val result = gateway.fetch() as AiCapabilitiesGatewayResult.Success

        assertEquals(setOf(AiCapability.AI_ANALYZE_WORKOUT, AiCapability.AI_ADAPT_WORKOUT), result.allowed)
    }

    @Test
    fun `capability desconhecida e ignorada, sem crash e sem virar liberada`() = runBlocking {
        val gateway = gatewayWith(
            mutableListOf(),
            body = """{"capabilities":[
                {"capability":"AI_ANALYZE_WORKOUT","allowed":true},
                {"capability":"AI_FUTURE_CAPABILITY","allowed":true}
            ]}"""
        )

        val result = gateway.fetch() as AiCapabilitiesGatewayResult.Success

        assertEquals(setOf(AiCapability.AI_ANALYZE_WORKOUT), result.allowed)
    }

    // ------------------------------------------------------------------- mapeamento de erro

    @Test
    fun `401 vira pedido de conta`() = runBlocking {
        val gateway = gatewayWith(mutableListOf(), status = 401, body = "{}")

        val result = gateway.fetch() as AiCapabilitiesGatewayResult.Error

        assertEquals(AiCapabilitiesErrorKind.AUTH_REQUIRED, result.kind)
    }

    @Test
    fun `503 e 500 viram indisponivel — o servidor nao conseguiu decidir (T19_0)`() = runBlocking {
        for (status in listOf(500, 503)) {
            val gateway = gatewayWith(mutableListOf(), status = status, body = "{}")
            val result = gateway.fetch() as AiCapabilitiesGatewayResult.Error
            assertEquals(AiCapabilitiesErrorKind.UNAVAILABLE, result.kind)
        }
    }

    @Test
    fun `resposta fora do contrato nunca e interpretada como permissao`() = runBlocking {
        val cases = listOf(
            "não é json",
            """{"nada":"a ver"}""",
            """{"capabilities": "não é uma lista"}"""
        )
        for (body in cases) {
            val gateway = gatewayWith(mutableListOf(), body = body)
            val result = gateway.fetch() as AiCapabilitiesGatewayResult.Error
            assertEquals(AiCapabilitiesErrorKind.INVALID_RESPONSE, result.kind)
        }
    }

    @Test
    fun `backend fora do ar vira erro de rede`() = runBlocking {
        val tokens = TokenProvider(AuthTokenResult.Token("token-abc"))
        val gateway = SparkAiCapabilitiesGateway(
            SparkBackendClient(
                baseUrl = BASE_URL,
                tokens = tokens,
                httpClient = OkHttpClient.Builder()
                    .addInterceptor(SparkAuthInterceptor(tokens))
                    .addInterceptor(Interceptor { throw IOException("sem rota para o host") })
                    .build()
            )
        )

        val result = gateway.fetch() as AiCapabilitiesGatewayResult.Error

        assertEquals(AiCapabilitiesErrorKind.NETWORK, result.kind)
    }

    // ---------------------------------------------------------------------------- apoio

    private fun gatewayWith(
        sent: MutableList<Request>,
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-abc"),
        baseUrl: String = BASE_URL
    ): SparkAiCapabilitiesGateway {
        val tokens = TokenProvider(token)
        val http = OkHttpClient.Builder()
            .addInterceptor(SparkAuthInterceptor(tokens))
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("stub")
                        .body(body.toResponseBody(null))
                        .build()
                }
            )
            .build()

        return SparkAiCapabilitiesGateway(
            SparkBackendClient(baseUrl = baseUrl, tokens = tokens, httpClient = http)
        )
    }

    private fun envelope(vararg entries: Pair<String, Boolean>): String {
        val items = entries.joinToString(",") { (capability, allowed) ->
            "{\"capability\":\"$capability\",\"allowed\":$allowed}"
        }
        return "{\"capabilities\":[$items]}"
    }

    private class TokenProvider(private val result: AuthTokenResult) : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult = result
    }

    private companion object {
        const val BASE_URL = "https://spark.exemplo"
    }
}
