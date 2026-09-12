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

    // ------------------------------------------------------ token travado (auditoria 2026-09-12)

    @Test
    fun `um provedor de token que nunca responde nao prende a chamada para sempre`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(NeverAnsweringTokenProvider(), sent, tokenTimeoutMillis = 50L)

        // `runBlocking` aqui bloqueia uma thread de I/O do OkHttp **dentro** de `execute()`, e quem
        // chamou está segurando o `Mutex` do sync ou a `CloudOperationLock`. Sem teto, as duas
        // travas ficavam presas até o processo morrer.
        val error = runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute()
        }.exceptionOrNull()

        assertTrue(
            "a espera pelo token precisa terminar: $error",
            generateSequence(error) { it.cause }.any { it is AuthTokenTimeoutException }
        )
        assertTrue("sem token, nada pode ir para a rede", sent.isEmpty())
    }

    @Test
    fun `token travado vira indisponibilidade, nao pedido de login`() {
        val backend = SparkBackendClient(
            baseUrl = BASE_URL,
            tokens = TokenProvider(AuthTokenResult.Token("t")),
            httpClient = clientWith(NeverAnsweringTokenProvider(), mutableListOf(), tokenTimeoutMillis = 50L)
        )

        // "Não deu para perguntar agora" é indisponibilidade recuperável. Tratar como sessão
        // ausente faria a tela pedir login a quem está logado.
        assertEquals(
            SparkBackendResult.Unavailable,
            kotlinx.coroutines.runBlocking { backend.me() }
        )
    }

    // --------------------------------------------------------- 401 com renovação do token

    @Test
    fun `um 401 e repetido uma vez com o token renovado`() {
        val provider = RefreshingTokenProvider()
        val sent = mutableListOf<Request>()
        val client = clientAnsweringByToken(provider, sent, accepted = "fresh")

        val response = client.newCall(Request.Builder().url(URL).build()).execute()
        val code = response.code
        response.close()

        assertEquals("a renovação precisa salvar a requisição", 200, code)
        assertEquals(listOf(false, true), provider.forceRefreshCalls)
        assertEquals(
            listOf("Bearer stale", "Bearer fresh"),
            sent.map { it.header("Authorization") }
        )
    }

    @Test
    fun `um 401 que persiste depois da renovacao nao vira laco`() {
        val provider = RefreshingTokenProvider()
        val sent = mutableListOf<Request>()
        // Nenhum token é aceito: nem o do cache, nem o renovado.
        val client = clientAnsweringByToken(provider, sent, accepted = "nenhum")

        val response = client.newCall(Request.Builder().url(URL).build()).execute()
        val code = response.code
        response.close()

        // Duas tentativas, e a segunda resposta é a resposta. Um retry disparado pelo código da
        // resposta sem limite seria um laço contra um servidor que já disse não.
        assertEquals(401, code)
        assertEquals(2, sent.size)
        assertEquals(listOf(false, true), provider.forceRefreshCalls)
    }

    @Test
    fun `sem 401 o token nao e renovado`() {
        val provider = RefreshingTokenProvider()
        val sent = mutableListOf<Request>()
        val client = clientAnsweringByToken(provider, sent, accepted = "stale")

        client.newCall(Request.Builder().url(URL).build()).execute().close()

        // O caminho normal continua sendo **uma** chamada ao Firebase por requisição.
        assertEquals(listOf(false), provider.forceRefreshCalls)
        assertEquals(1, sent.size)
    }

    @Test
    fun `sem sessao o 401 nao dispara renovacao`() {
        val sent = mutableListOf<Request>()
        val client = clientWith(TokenProvider(AuthTokenResult.SignedOut), sent, status = 401)

        runCatching { client.newCall(Request.Builder().url(URL).build()).execute() }

        // A primeira tentativa nem sai: sem token não há requisição autenticada a fazer, e não há
        // o que renovar.
        assertTrue(sent.isEmpty())
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
        body: String = "{}",
        tokenTimeoutMillis: Long = SparkAuthInterceptor.TOKEN_TIMEOUT_MILLIS
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SparkAuthInterceptor(tokens, tokenTimeoutMillis))
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

    /**
     * Um servidor de teste que responde conforme o token recebido.
     *
     * É o que permite provar a renovação: o token velho leva 401, o renovado leva 200, e a
     * diferença entre "o app tentou de novo" e "o app tentou de novo **com outro token**" fica
     * visível.
     */
    private fun clientAnsweringByToken(
        tokens: AuthTokenProvider,
        sent: MutableList<Request>,
        accepted: String
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SparkAuthInterceptor(tokens))
        .addInterceptor(
            Interceptor { chain ->
                sent += chain.request()
                val authorized = chain.request().header("Authorization") == "Bearer $accepted"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (authorized) 200 else 401)
                    .message("stub")
                    .body("{}".toResponseBody(null))
                    .build()
            }
        )
        .build()

    private class TokenProvider(private val result: AuthTokenResult) : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult = result
    }

    /** Um provedor que nunca responde — o Firebase pendurado em DNS, sem timeout próprio. */
    private class NeverAnsweringTokenProvider : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult =
            kotlinx.coroutines.awaitCancellation()
    }

    /**
     * Um token em cache que o servidor recusa, e um renovado que ele aceita.
     *
     * É o cenário real: o token guardado foi revogado (ou o relógio do aparelho andou), e só uma
     * renovação forçada produz um que vale.
     */
    private class RefreshingTokenProvider : AuthTokenProvider {
        val forceRefreshCalls = mutableListOf<Boolean>()

        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult {
            forceRefreshCalls += forceRefresh
            return AuthTokenResult.Token(if (forceRefresh) "fresh" else "stale")
        }
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
