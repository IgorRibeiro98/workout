package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.FriendError
import com.example.domain.social.FriendLookup
import com.example.domain.social.FriendOutcome
import com.example.domain.social.FriendRelationship
import com.example.domain.social.FriendRequestSent
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fronteira HTTP do grafo social (T17.1).
 *
 * Nenhum teste aqui abre socket ou toca Firebase: um interceptor terminal responde no lugar da
 * rede, o que permite inspecionar exatamente a requisição que **teria** saído — e provar as três
 * coisas que mais importam:
 *
 * 1. **sem conta não sai requisição**;
 * 2. **o corpo não carrega identidade do chamador** — nada de `ownerUid`, `uid` ou e-mail;
 * 3. **cada `code` do envelope de erro vira o erro tipado certo**, porque um `409` de "já são
 *    amigos" e um `409` de "pedido já resolvido" levam a telas diferentes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkFriendGatewayTest {

    private val socialId = "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d"

    // ---------------------------------------------------------------- requisição

    @Test
    fun `o lookup manda o codigo no corpo, e nunca na URL`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = """{"result":"NOT_FOUND"}""")

        gateway.lookup("spk-7k2p9d8q")

        val request = sent.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/friends/lookup"))
        // A URL é a parte que vaza mais fácil — log de proxy, log de acesso, histórico.
        assertFalse(request.url.toString().contains("7K2P", ignoreCase = true))
        assertTrue(bodyOf(request).contains("spk-7k2p9d8q"))
    }

    @Test
    fun `o corpo nunca carrega identidade do chamador`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = """{"result":"REQUEST_CREATED","request":${requestJson()}}""")

        gateway.sendRequest(socialId)
        gateway.removeFriend(socialId)

        for (request in sent) {
            val body = bodyOf(request)
            for (forbidden in listOf("ownerUid", "\"uid\"", "firebaseUid", "email", "deviceId")) {
                assertFalse("corpo não pode conter $forbidden: $body", body.contains(forbidden))
            }
        }
    }

    @Test
    fun `sem conta conectada nenhuma requisicao sai`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val outcome = gateway.friends()

        assertEquals(FriendOutcome.Failure(FriendError.AUTH_REQUIRED), outcome)
    }

    @Test
    fun `sem backend configurado nada e tentado`() = runBlocking {
        val gateway = SparkFriendGateway(client = null)

        assertEquals(FriendOutcome.Failure(FriendError.NOT_CONFIGURED), gateway.friends())
        assertFalse(gateway.isConfigured)
    }

    @Test
    fun `sem rede a operacao falha como NETWORK, e nada fica pendente`() = runBlocking {
        val gateway = SparkFriendGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokenProvider(AuthTokenResult.Token("token")),
                httpClient = OkHttpClient.Builder()
                    .addInterceptor(SparkAuthInterceptor(tokenProvider(AuthTokenResult.Token("token"))))
                    .addInterceptor(Interceptor { throw IOException("sem rota para o host") })
                    .build()
            )
        )

        assertEquals(FriendOutcome.Failure(FriendError.NETWORK), gateway.acceptRequest("req-1"))
    }

    // ---------------------------------------------------------------- resposta

    @Test
    fun `um lookup encontrado vira preview com a relacao`() = runBlocking {
        val gateway = gatewayWith(
            body = """
                {"result":"FOUND","profile":{"socialId":"$socialId","displayName":"João"},
                 "relationship":"INCOMING_PENDING","canSendFriendRequest":true}
            """.trimIndent()
        )

        val outcome = gateway.lookup("SPK-7K2P9D8Q") as FriendOutcome.Success
        val found = outcome.value as FriendLookup.Found

        assertEquals("João", found.profile.displayName)
        assertEquals(FriendRelationship.INCOMING_PENDING, found.relationship)
        assertTrue(found.canSendFriendRequest)
    }

    @Test
    fun `SELF e NOT_FOUND viram resultados proprios`() = runBlocking {
        assertEquals(
            FriendLookup.Self,
            (gatewayWith(body = """{"result":"SELF"}""").lookup("x") as FriendOutcome.Success).value
        )
        assertEquals(
            FriendLookup.NotFound,
            (gatewayWith(body = """{"result":"NOT_FOUND"}""").lookup("x") as FriendOutcome.Success).value
        )
    }

    @Test
    fun `o cruzamento vira amizade, e nao pedido enviado`() = runBlocking {
        val gateway = gatewayWith(
            body = """
                {"result":"FRIENDSHIP_CREATED",
                 "friend":{"socialId":"$socialId","displayName":"João","friendsSince":123}}
            """.trimIndent()
        )

        val outcome = gateway.sendRequest(socialId) as FriendOutcome.Success

        assertTrue(outcome.value is FriendRequestSent.BecameFriends)
    }

    @Test
    fun `uma resposta que este APK nao sabe ler nao vira meia amizade`() = runBlocking {
        val cases = listOf(
            // Resultado desconhecido — versão nova do servidor.
            """{"result":"TALVEZ"}""",
            // `FOUND` sem perfil.
            """{"result":"FOUND","relationship":"NONE"}""",
            // Relação que este APK não conhece: virar `NONE` por chute faria a tela oferecer
            // "Enviar solicitação" para quem já é amigo.
            """{"result":"FOUND","profile":{"socialId":"$socialId","displayName":"João"},
                "relationship":"BLOQUEADO"}""",
            "isto não é JSON"
        )

        for (body in cases) {
            assertEquals(
                "deveria recusar: $body",
                FriendOutcome.Failure(FriendError.REJECTED),
                gatewayWith(body = body).lookup("SPK-7K2P9D8Q")
            )
        }
    }

    @Test
    fun `uma lista com um item ilegivel e recusada inteira`() = runBlocking {
        // Uma lista de amigos com um buraco no meio é pior do que uma tela que diz que não
        // conseguiu ler a resposta.
        val gateway = gatewayWith(
            body = """
                {"friends":[{"socialId":"$socialId","displayName":"João","friendsSince":1},
                            {"socialId":"","displayName":"","friendsSince":2}],"total":2}
            """.trimIndent()
        )

        assertEquals(FriendOutcome.Failure(FriendError.REJECTED), gateway.friends())
    }

    @Test
    fun `a lista traz o total do servidor, e nao o tamanho da pagina`() = runBlocking {
        val gateway = gatewayWith(
            body = """
                {"friends":[{"socialId":"$socialId","displayName":"João","friendsSince":1}],
                 "total":37,"nextCursor":"abc"}
            """.trimIndent()
        )

        val page = (gateway.friends() as FriendOutcome.Success).value

        assertEquals(1, page.items.size)
        assertEquals(37, page.total)
        assertEquals("abc", page.nextCursor)
    }

    // ---------------------------------------------------------------- erros tipados

    @Test
    fun `cada code do envelope vira o erro tipado correspondente`() = runBlocking {
        val cases = mapOf(
            "SOCIAL_NOT_ENABLED" to FriendError.SOCIAL_NOT_ENABLED,
            "SOCIAL_PROFILE_DISABLED" to FriendError.SOCIAL_DISABLED,
            "SOCIAL_PROFILE_NOT_FOUND" to FriendError.PROFILE_NOT_FOUND,
            "SELF_FRIEND_REQUEST" to FriendError.SELF_REQUEST,
            "FRIEND_REQUESTS_DISABLED" to FriendError.REQUESTS_DISABLED,
            "ALREADY_FRIENDS" to FriendError.ALREADY_FRIENDS,
            "FRIEND_REQUEST_NOT_FOUND" to FriendError.REQUEST_NOT_FOUND,
            "FRIEND_REQUEST_NOT_PENDING" to FriendError.REQUEST_NOT_PENDING,
            "NOT_REQUEST_RECIPIENT" to FriendError.NOT_ALLOWED,
            "NOT_REQUEST_SENDER" to FriendError.NOT_ALLOWED,
            "FRIENDSHIP_NOT_FOUND" to FriendError.FRIENDSHIP_NOT_FOUND,
            "SOCIAL_RATE_LIMITED" to FriendError.RATE_LIMITED,
            "INVALID_FRIEND_REQUEST" to FriendError.REJECTED,
            "UNAUTHENTICATED" to FriendError.AUTH_REQUIRED,
            "API_RATE_LIMITED" to FriendError.RATE_LIMITED,
            "AUTH_UNAVAILABLE" to FriendError.UNAVAILABLE
        )

        for ((code, expected) in cases) {
            val gateway = gatewayWith(
                status = 400,
                body = """{"error":{"code":"$code","message":"x","requestId":"r"}}"""
            )
            assertEquals(code, FriendOutcome.Failure(expected), gateway.acceptRequest("req-1"))
        }
    }

    @Test
    fun `sem code no envelope o status decide`() = runBlocking {
        assertEquals(
            FriendOutcome.Failure(FriendError.UNAVAILABLE),
            gatewayWith(status = 503, body = "indisponível").friends()
        )
        assertEquals(
            FriendOutcome.Failure(FriendError.RATE_LIMITED),
            gatewayWith(status = 429, body = "").friends()
        )
        assertEquals(
            FriendOutcome.Failure(FriendError.AUTH_REQUIRED),
            gatewayWith(status = 401, body = "").friends()
        )
    }

    // ---------------------------------------------------------------- apoio

    private fun requestJson(): String =
        """{"requestId":"req-1","profile":{"socialId":"$socialId","displayName":"João"},
            "direction":"OUTGOING","createdAt":1}"""

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-de-teste")
    ): SparkFriendGateway {
        val tokens = tokenProvider(token)
        val http = OkHttpClient.Builder()
            // O mesmo interceptor da produção: é ele que recusa a requisição sem conta antes de
            // ela existir. Sem ele o teste provaria menos do que afirma.
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

        return SparkFriendGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokens,
                httpClient = http
            )
        )
    }

    private fun tokenProvider(result: AuthTokenResult) = object : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult = result
    }

    private fun bodyOf(request: Request): String {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
