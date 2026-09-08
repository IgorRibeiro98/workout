package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialProfileError
import com.example.domain.social.SocialProfileOutcome
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fronteira HTTP do perfil social enriquecido (T17.2).
 *
 * Nenhum teste aqui abre socket ou toca Firebase: um interceptor terminal responde no lugar da
 * rede, o que permite inspecionar exatamente a requisição que **teria** saído — e provar as quatro
 * coisas que mais importam:
 *
 * 1. **sem conta não sai requisição**;
 * 2. **o corpo nunca carrega progresso.** Nem `level`, nem `streak`, nem `weeklyWorkoutCount`: o
 *    servidor não confia no aparelho sobre progresso, e o aparelho não tem como propô-lo;
 * 3. **campo ausente continua ausente.** Um JSON sem `level` vira `null` no domínio — nunca `0`;
 * 4. **cada `code` do envelope de erro vira o erro tipado certo.**
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkSocialProfileGatewayTest {

    private val socialId = "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d"

    // ---------------------------------------------------------------- requisição

    @Test
    fun `o perfil do amigo e um GET com o socialId no caminho`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = profileJson())

        gateway.friendProfile(socialId)

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(
            request.url.toString().endsWith("/v1/social/friends/$socialId/profile")
        )
        // Nenhum `friendCode` em URL nenhuma — ele é convite, e a URL é a parte que vaza mais fácil.
        assertFalse(request.url.toString().contains("SPK-", ignoreCase = true))
    }

    @Test
    fun `o corpo do PATCH carrega preferencia, e nunca progresso`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = sharingJson())

        gateway.updateProgressSharing(shareWeeklyWorkoutCount = true, weekTimeZone = "America/Sao_Paulo")

        val body = bodyOf(sent.single())
        assertTrue(body.contains("shareWeeklyWorkoutCount"))
        assertTrue(body.contains("America/Sao_Paulo"))
        for (forbidden in listOf(
            "\"level\"", "\"streak\"", "\"consistencyStreak\"", "\"weeklyWorkoutCount\"",
            "totalXp", "earnedAchievementIds", "ownerUid", "\"uid\"", "firebaseUid", "email"
        )) {
            assertFalse("o corpo não pode conter $forbidden: $body", body.contains(forbidden))
        }
    }

    @Test
    fun `o PATCH e parcial — o que nao foi pedido nao vai no corpo`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = sharingJson())

        gateway.updateProgressSharing(shareLevel = true)

        val body = bodyOf(sent.single())
        assertTrue(body.contains("shareLevel"))
        // Um `null` explícito faria "não mexa" virar "desligue" num contrato mais frouxo — e aqui
        // faria o servidor recusar. `explicitNulls = false` é o que garante a semântica de PATCH.
        assertFalse(body.contains("shareConsistencyStreak"))
        assertFalse(body.contains("weekTimeZone"))
    }

    @Test
    fun `sem conta nao sai requisicao`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        assertEquals(
            SocialProfileOutcome.Failure(SocialProfileError.AUTH_REQUIRED),
            gateway.friendProfile(socialId)
        )
        assertTrue("nenhuma requisição pode ter saído", sent.isEmpty())
    }

    // ---------------------------------------------------------------- resposta

    @Test
    fun `campo ausente vira nulo, e nunca zero`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"profile":{"socialId":"$socialId","displayName":"Igor",
                       "sharedProgress":{"weeklyWorkoutCount":3}}}"""
        )

        val outcome = gateway.friendProfile(socialId)
        val progress = (outcome as SocialProfileOutcome.Success).value.sharedProgress

        assertEquals(3, progress.weeklyWorkoutCount)
        assertNull(progress.level)
        assertNull(progress.consistencyStreak)
        assertTrue(progress.highlightedAchievementIds.isEmpty())
        assertFalse(progress.isEmpty)
    }

    @Test
    fun `progresso vazio e um estado valido, e nao um erro`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"profile":{"socialId":"$socialId","displayName":"Igor","sharedProgress":{}}}"""
        )

        val outcome = gateway.friendProfile(socialId)
        assertTrue((outcome as SocialProfileOutcome.Success).value.sharedProgress.isEmpty)
    }

    @Test
    fun `uma disponibilidade desconhecida vira o valor conservador`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"settings":{"shareLevel":true},
                       "availability":{"level":"MAGIC","weeklyWorkoutCount":"AVAILABLE"}}"""
        )

        val outcome = gateway.progressSharing()
        val availability = (outcome as SocialProfileOutcome.Success).value.availability

        // Um servidor mais novo que invente um estado não pode fazer a tela dizer "Disponível".
        assertEquals(SocialFieldAvailability.UNAVAILABLE, availability.level)
        assertEquals(SocialFieldAvailability.AVAILABLE, availability.weeklyWorkoutCount)
    }

    @Test
    fun `um corpo ilegivel nao vira meio perfil`() = runBlocking {
        val gateway = gatewayWith(body = """{"profile":{"displayName":"Igor"}}""")

        assertEquals(
            SocialProfileOutcome.Failure(SocialProfileError.REJECTED),
            gateway.friendProfile(socialId)
        )
    }

    // ---------------------------------------------------------------- erros

    @Test
    fun `cada code do envelope vira o erro tipado certo`() = runBlocking {
        val cases = mapOf(
            "FRIEND_PROFILE_NOT_FOUND" to SocialProfileError.PROFILE_UNAVAILABLE,
            "INVALID_PROGRESS_SETTINGS" to SocialProfileError.INVALID_SETTINGS,
            "SOCIAL_NOT_ENABLED" to SocialProfileError.SOCIAL_NOT_ENABLED,
            "SOCIAL_PROFILE_DISABLED" to SocialProfileError.SOCIAL_DISABLED,
            "SOCIAL_RATE_LIMITED" to SocialProfileError.RATE_LIMITED,
            "UNAUTHENTICATED" to SocialProfileError.AUTH_REQUIRED
        )

        for ((code, expected) in cases) {
            val gateway = gatewayWith(
                status = 400,
                body = """{"error":{"code":"$code","message":"x","requestId":"r"}}"""
            )
            assertEquals(code, SocialProfileOutcome.Failure(expected), gateway.friendProfile(socialId))
        }
    }

    @Test
    fun `sem rede o resultado e NETWORK — e nada ficou pendente`() = runBlocking {
        val http = OkHttpClient.Builder()
            .addInterceptor(SparkAuthInterceptor(tokenProvider(AuthTokenResult.Token("t"))))
            .addInterceptor(Interceptor { throw IOException("sem rede") })
            .build()
        val gateway = SparkSocialProfileGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokenProvider(AuthTokenResult.Token("t")),
                httpClient = http
            )
        )

        assertEquals(
            SocialProfileOutcome.Failure(SocialProfileError.NETWORK),
            gateway.updateProgressSharing(shareLevel = true)
        )
    }

    @Test
    fun `sem backend configurado nada e oferecido`() = runBlocking {
        val gateway = SparkSocialProfileGateway(null)

        assertFalse(gateway.isConfigured)
        assertEquals(
            SocialProfileOutcome.Failure(SocialProfileError.NOT_CONFIGURED),
            gateway.profilePreview()
        )
    }

    // ---------------------------------------------------------------- apoio

    private fun profileJson(): String =
        """{"profile":{"socialId":"$socialId","displayName":"Igor",
            "sharedProgress":{"level":14}}}"""

    private fun sharingJson(): String =
        """{"settings":{"shareLevel":false,"shareConsistencyStreak":false,
            "shareWeeklyWorkoutCount":true,"shareHighlightedAchievements":false,
            "weekTimeZone":"America/Sao_Paulo","updatedAt":1},
            "availability":{"level":"UNSUPPORTED","consistencyStreak":"UNSUPPORTED",
            "weeklyWorkoutCount":"AVAILABLE","highlightedAchievements":"UNSUPPORTED"}}"""

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-de-teste")
    ): SparkSocialProfileGateway {
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

        return SparkSocialProfileGateway(
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
