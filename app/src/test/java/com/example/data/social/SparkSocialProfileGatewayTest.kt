package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.ProgressSharingField
import com.example.domain.social.ProgressSharingGroup
import com.example.domain.social.SocialAvailabilityReason
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialFieldAvailabilityDetail
import com.example.domain.social.SocialProfileError
import com.example.domain.social.SocialProfileOutcome
import com.example.domain.social.isSupportedBy
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

        gateway.updateProgressSharing(
            changes = mapOf(ProgressSharingField.WEEKLY_WORKOUT_COUNT to true),
            weekTimeZone = "America/Sao_Paulo"
        )

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

        gateway.updateProgressSharing(changes = mapOf(ProgressSharingField.LEVEL to true))

        val body = bodyOf(sent.single())
        assertTrue(body.contains("shareLevel"))
        // Um `null` explícito faria "não mexa" virar "desligue" num contrato mais frouxo — e aqui
        // faria o servidor recusar. `explicitNulls = false` é o que garante a semântica de PATCH.
        assertFalse(body.contains("shareConsistencyStreak"))
        assertFalse(body.contains("weekTimeZone"))
    }

    // ------------------------------------------------------------------ T19.H3 texto do contrato

    @Test
    fun `o PATCH de um interruptor V3 e exatamente o campo pedido — o texto, e nao o objeto`() =
        runBlocking {
            // PROJECT_RULES §13.27 (T19.H2): contrato de rede ganha teste do **texto**. Um default
            // omitido ou um nome trocado aqui não aparece em teste que compara objeto Kotlin.
            for ((field, wire) in listOf(
                ProgressSharingField.WEEKLY_TRAINING_MINUTES to "shareWeeklyTrainingMinutes",
                ProgressSharingField.WEEKLY_COMPLETED_SETS to "shareWeeklyCompletedSets",
                ProgressSharingField.WEEKLY_VOLUME to "shareWeeklyVolume",
                ProgressSharingField.TOTAL_WORKOUTS to "shareTotalWorkouts",
                ProgressSharingField.WORKOUT_NAME to "shareWorkoutName",
                ProgressSharingField.WORKOUT_TIME to "shareWorkoutTime",
                ProgressSharingField.WORKOUT_DURATION to "shareWorkoutDuration",
                ProgressSharingField.WORKOUT_EXERCISES to "shareWorkoutExercises",
                ProgressSharingField.WORKOUT_SETS to "shareWorkoutSets",
                ProgressSharingField.WORKOUT_WEIGHTS to "shareWorkoutWeights",
                ProgressSharingField.WORKOUT_VOLUME to "shareWorkoutVolume"
            )) {
                val sent = mutableListOf<Request>()
                gatewayWith(sent, body = sharingJson())
                    .updateProgressSharing(changes = mapOf(field to false))
                // `false` também vai: desligar é uma escolha, e não "não mexa".
                assertEquals("""{"$wire":false}""", bodyOf(sent.single()))
            }
        }

    @Test
    fun `as configuracoes V3 e a disponibilidade nova sao lidas do texto do servidor`() =
        runBlocking {
            // T19.H5: o servidor da T19.H3 em diante declara a versão. Sem ela, a resposta é de um
            // servidor legado — ver `sem contractVersion o servidor e legado`.
            val gateway = gatewayWith(
                body = """{"contractVersion":2,
                           "settings":{"shareLevel":false,"shareWorkoutExercises":true,
                           "shareWorkoutWeights":true,"shareTotalWorkouts":true,"updatedAt":7},
                           "availability":{"weeklyTrainingMinutes":"AVAILABLE",
                           "weeklyVolume":"UNAVAILABLE","totalWorkouts":"AVAILABLE"}}"""
            )

            val sharing = (gateway.progressSharing() as SocialProfileOutcome.Success).value
            assertTrue(sharing.settings.shareWorkoutExercises)
            assertTrue(sharing.settings.shareWorkoutWeights)
            assertTrue(sharing.settings.shareTotalWorkouts)
            assertFalse(sharing.settings.shareWorkoutSets)
            assertEquals(SocialFieldAvailability.AVAILABLE, sharing.availability.weeklyTrainingMinutes)
            assertEquals(SocialFieldAvailability.UNAVAILABLE, sharing.availability.weeklyVolume)
            // Ausente no JSON de um servidor v2: o valor conservador, nunca "Disponível".
            assertEquals(SocialFieldAvailability.UNAVAILABLE, sharing.availability.weeklyCompletedSets)
        }

    // ------------------------------------------------------------------ T19.H5 contrato versionado

    @Test
    fun `contrato v2 — o motivo de cada campo indisponivel e lido do texto do servidor`() =
        runBlocking {
            val gateway = gatewayWith(
                body = """{"contractVersion":2,
                           "settings":{"shareLevel":true,"weekTimeZone":"America/Sao_Paulo","updatedAt":3},
                           "availability":{"level":"UNAVAILABLE","consistencyStreak":"UNAVAILABLE",
                             "weeklyWorkoutCount":"AVAILABLE","highlightedAchievements":"AVAILABLE",
                             "weeklyTrainingMinutes":"UNAVAILABLE","weeklyCompletedSets":"UNAVAILABLE",
                             "weeklyVolume":"UNAVAILABLE","totalWorkouts":"AVAILABLE"},
                           "availabilityReasons":{"level":"CONSISTENCY_PARAMETERS_MISSING",
                             "consistencyStreak":"CONSISTENCY_PARAMETERS_MISSING",
                             "weeklyTrainingMinutes":"SOURCE_LIMIT_REACHED",
                             "weeklyCompletedSets":"UM_MOTIVO_DO_FUTURO"}}"""
            )

            val sharing = (gateway.progressSharing() as SocialProfileOutcome.Success).value
            val availability = sharing.availability
            assertEquals(2, sharing.contractVersion)
            assertEquals(
                SocialFieldAvailabilityDetail(
                    SocialFieldAvailability.UNAVAILABLE,
                    SocialAvailabilityReason.CONSISTENCY_PARAMETERS_MISSING
                ),
                availability.detailOf(ProgressSharingField.LEVEL)
            )
            assertEquals(
                SocialFieldAvailabilityDetail(
                    SocialFieldAvailability.UNAVAILABLE,
                    SocialAvailabilityReason.SOURCE_LIMIT_REACHED
                ),
                availability.detailOf(ProgressSharingField.WEEKLY_TRAINING_MINUTES)
            )
            // Motivo que este APK não conhece, e indisponível sem motivo: a tela diz menos, e não
            // adivinha.
            for (field in listOf(
                ProgressSharingField.WEEKLY_COMPLETED_SETS,
                ProgressSharingField.WEEKLY_VOLUME
            )) {
                assertEquals(
                    SocialFieldAvailabilityDetail(
                        SocialFieldAvailability.UNAVAILABLE,
                        SocialAvailabilityReason.UNKNOWN
                    ),
                    availability.detailOf(field)
                )
            }
            // Disponível não tem motivo, e detalhe de check-in não tem disponibilidade.
            assertEquals(
                SocialFieldAvailabilityDetail(SocialFieldAvailability.AVAILABLE, null),
                availability.detailOf(ProgressSharingField.TOTAL_WORKOUTS)
            )
            assertNull(availability.detailOf(ProgressSharingField.WORKOUT_NAME))
            // Nenhum desses motivos se resolve sincronizando.
            assertFalse(availability.needsSync)
            for (field in ProgressSharingField.entries) {
                assertTrue("$field é oferecido num servidor v2", field.isSupportedBy(sharing.contractVersion))
            }
        }

    @Test
    fun `sem contractVersion o servidor e legado — os campos da T19H3 nao viram ainda nao disponivel`() =
        runBlocking {
            // A resposta do servidor anterior à T19.H3 — a que a produção deu até 2026-09-25:
            // quatro disponibilidades, nenhum interruptor novo e nenhuma versão.
            val gateway = gatewayWith(
                body = """{"settings":{"shareLevel":true,"shareConsistencyStreak":false,
                           "shareWeeklyWorkoutCount":true,"shareHighlightedAchievements":false,
                           "weekTimeZone":"America/Sao_Paulo","updatedAt":1},
                           "availability":{"level":"AVAILABLE","consistencyStreak":"AVAILABLE",
                           "weeklyWorkoutCount":"AVAILABLE","highlightedAchievements":"AVAILABLE"}}"""
            )

            val sharing = (gateway.progressSharing() as SocialProfileOutcome.Success).value
            assertEquals(1, sharing.contractVersion)
            assertEquals(
                SocialFieldAvailabilityDetail(SocialFieldAvailability.AVAILABLE, null),
                sharing.availability.detailOf(ProgressSharingField.LEVEL)
            )
            for (field in listOf(
                ProgressSharingField.WEEKLY_TRAINING_MINUTES,
                ProgressSharingField.WEEKLY_COMPLETED_SETS,
                ProgressSharingField.WEEKLY_VOLUME,
                ProgressSharingField.TOTAL_WORKOUTS
            )) {
                // Não é UNAVAILABLE ("sincronize"): é um recurso que aquele servidor não tem.
                assertEquals(
                    SocialFieldAvailabilityDetail(
                        SocialFieldAvailability.UNSUPPORTED,
                        SocialAvailabilityReason.LEGACY_BACKEND
                    ),
                    sharing.availability.detailOf(field)
                )
            }
            assertFalse(sharing.availability.needsSync)
            // Nenhum interruptor que aquele servidor recusaria é oferecido.
            for (field in ProgressSharingField.entries) {
                assertEquals(
                    "$field num servidor legado",
                    field.group == ProgressSharingGroup.GENERAL,
                    field.isSupportedBy(sharing.contractVersion)
                )
            }
        }

    @Test
    fun `um servidor nao se declara legado — esses motivos sao do app`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"contractVersion":2,"settings":{},
                       "availability":{"level":"UNAVAILABLE","totalWorkouts":"UNAVAILABLE"},
                       "availabilityReasons":{"level":"LEGACY_BACKEND","totalWorkouts":"UNKNOWN"}}"""
        )

        val availability = (gateway.progressSharing() as SocialProfileOutcome.Success).value.availability
        assertEquals(SocialAvailabilityReason.UNKNOWN, availability.detailOf(ProgressSharingField.LEVEL)?.reason)
        assertEquals(
            SocialAvailabilityReason.UNKNOWN,
            availability.detailOf(ProgressSharingField.TOTAL_WORKOUTS)?.reason
        )
    }

    @Test
    fun `sem treino no servidor e o motivo que pede sincronizacao`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"contractVersion":2,"settings":{},
                       "availability":{"totalWorkouts":"UNAVAILABLE"},
                       "availabilityReasons":{"totalWorkouts":"NO_SYNCED_WORKOUTS"}}"""
        )

        val availability = (gateway.progressSharing() as SocialProfileOutcome.Success).value.availability
        assertTrue(availability.needsSync)
    }

    @Test
    fun `um servidor de contrato futuro continua oferecendo tudo o que este APK conhece`() =
        runBlocking {
            val gateway = gatewayWith(
                body = """{"contractVersion":7,"settings":{},"availability":{},
                           "algumaCoisaNova":{"x":1}}"""
            )

            val sharing = (gateway.progressSharing() as SocialProfileOutcome.Success).value
            assertEquals(7, sharing.contractVersion)
            assertTrue(ProgressSharingField.entries.all { it.isSupportedBy(sharing.contractVersion) })
        }

    @Test
    fun `as estatisticas de treino do amigo chegam como vieram — e ausentes continuam nulas`() =
        runBlocking {
            val gateway = gatewayWith(
                body = """{"profile":{"socialId":"$socialId","displayName":"Igor",
                           "sharedProgress":{"weeklyVolumeKg":6060.5,"totalWorkouts":42}}}"""
            )

            val progress = (gateway.friendProfile(socialId) as SocialProfileOutcome.Success)
                .value.sharedProgress
            assertEquals(6060.5, progress.weeklyVolumeKg!!, 0.0)
            assertEquals(42, progress.totalWorkouts)
            assertNull(progress.weeklyTrainingMinutes)
            assertNull(progress.weeklyCompletedSets)
            assertFalse(progress.isEmpty)
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
            gateway.updateProgressSharing(changes = mapOf(ProgressSharingField.LEVEL to true))
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
