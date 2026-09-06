package com.example.data.remote.spark

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O transporte autenticado do Spark Backend.
 *
 * Nenhum teste aqui abre socket: um interceptor terminal responde no lugar da rede, o que permite
 * inspecionar exatamente a requisição que **teria** saído.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkAuthInterceptorTest {

    @Test
    fun `com conta conectada a requisicao sai com Authorization Bearer`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(TokenProvider(AuthTokenResult.Token("token-abc")), sent)

        val response = client.newCall(Request.Builder().url(URL).build()).execute()
        response.close()

        assertEquals(1, sent.size)
        assertEquals("Bearer token-abc", sent.single().header("Authorization"))
    }

    @Test
    fun `sem conta conectada a requisicao nao sai`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(TokenProvider(AuthTokenResult.SignedOut), sent)

        val error = runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute()
        }.exceptionOrNull()

        assertTrue(
            "sem token, nada pode ir para a rede",
            generateSequence(error) { it.cause }.any { it is MissingAuthTokenException }
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `falha ao obter token nao vira requisicao sem credencial`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(TokenProvider(AuthTokenResult.Failure(AuthError.NETWORK)), sent)

        runCatching { client.newCall(Request.Builder().url(URL).build()).execute() }

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `um token pedido por requisicao nao e guardado entre chamadas`() {
        val sent = mutableListOf<Request>()
        val provider = CountingTokenProvider()
        val client = clientWith(provider, sent)

        repeat(3) { client.newCall(Request.Builder().url(URL).build()).execute().close() }

        // Três requisições, três tokens pedidos: o interceptor não guarda cópia própria.
        assertEquals(3, provider.calls)
        assertEquals(listOf("Bearer token-1", "Bearer token-2", "Bearer token-3"), sent.map { it.header("Authorization") })
    }

    @Test
    fun `o token nunca aparece em toString nem na mensagem da excecao`() {
        val secret = "token-super-secreto-abc123"

        // `AuthTokenResult.Token` é a estrutura que mais circula com o token dentro. Um
        // `data class` imprimiria o valor no primeiro log ou mensagem de erro que a encostasse.
        val rendered = AuthTokenResult.Token(secret).toString()
        assertFalse(rendered.contains(secret))

        val exception = MissingAuthTokenException(AuthError.NETWORK)
        assertFalse(exception.message.orEmpty().contains(secret))
        assertEquals(AuthError.NETWORK, exception.error)
    }

    @Test
    fun `sem endereco configurado o cliente nao faz requisicao nenhuma`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(TokenProvider(AuthTokenResult.Token("token-abc")), sent)
        val backend = SparkBackendClient(baseUrl = "", tokens = TokenProvider(AuthTokenResult.Token("t")), httpClient = client)

        assertFalse(backend.isConfigured)
        val result = kotlinx.coroutines.runBlocking { backend.me() }

        assertEquals(SparkBackendResult.NotConfigured, result)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `o uid vem do corpo da resposta do servidor`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(
            TokenProvider(AuthTokenResult.Token("token-abc")),
            sent,
            body = """{"uid":"uid-do-servidor"}"""
        )
        val backend = SparkBackendClient(baseUrl = "http://localhost:8080", tokens = TokenProvider(AuthTokenResult.Token("token-abc")), httpClient = client)

        val result = kotlinx.coroutines.runBlocking { backend.me() }

        assertEquals(SparkBackendResult.Success("uid-do-servidor"), result)
        assertEquals("http://localhost:8080/v1/auth/me", sent.single().url.toString())
    }

    @Test
    fun `401 do servidor e Unauthenticated, 503 e Unavailable`() {
        val unauthorized = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(AuthTokenResult.Token("t")),
            httpClient = clientWith(TokenProvider(AuthTokenResult.Token("t")), mutableListOf(), status = 401, body = "{}")
        )
        val unavailable = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(AuthTokenResult.Token("t")),
            httpClient = clientWith(TokenProvider(AuthTokenResult.Token("t")), mutableListOf(), status = 503, body = "{}")
        )

        assertEquals(
            SparkBackendResult.Unauthenticated,
            kotlinx.coroutines.runBlocking { unauthorized.me() }
        )
        // Servidor indisponível não é sessão perdida: quem decide isso é o Firebase Auth local.
        assertEquals(
            SparkBackendResult.Unavailable,
            kotlinx.coroutines.runBlocking { unavailable.me() }
        )
    }

    @Test
    fun `sem conta o cliente responde Unauthenticated em vez de estourar`() {
        val backend = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(AuthTokenResult.SignedOut),
            httpClient = clientWith(TokenProvider(AuthTokenResult.SignedOut), mutableListOf())
        )

        assertEquals(
            SparkBackendResult.Unauthenticated,
            kotlinx.coroutines.runBlocking { backend.me() }
        )
    }

    @Test
    fun `o cliente do Spark Backend nao instala logging de requisicao`() {
        val source = java.io.File("src/main/java/com/example/data/remote/spark")
            .takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/example/data/remote/spark")
        val offenders = source.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("HttpLoggingInterceptor(") }
            .toList()

        // Um `HttpLoggingInterceptor` imprimiria o header `Authorization` no Logcat.
        assertTrue("logging de HTTP no cliente autenticado: ${offenders.map { it.name }}", offenders.isEmpty())
        assertNull(null)
    }

    private fun clientWith(
        tokens: AuthTokenProvider,
        sent: MutableList<Request>,
        status: Int = 200,
        body: String = "{}"
    ): OkHttpClient = OkHttpClient.Builder()
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

    private class TokenProvider(private val result: AuthTokenResult) : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult = result
    }

    private class CountingTokenProvider : AuthTokenProvider {
        var calls = 0
            private set

        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult {
            calls++
            return AuthTokenResult.Token("token-$calls")
        }
    }

    private companion object {
        const val BASE_URL = "http://localhost:8080"
        const val URL = "http://localhost:8080/v1/auth/me"
    }
}
