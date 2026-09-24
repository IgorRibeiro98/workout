package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInOutcome
import com.example.domain.social.WorkoutSocialExercise
import com.example.domain.social.WorkoutSocialSet
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O gateway HTTP do check-in: o upload da foto e o resumo de treino no Feed (T19.H3 §16/§28).
 *
 * O que se afirma aqui é o que **sai pela rede** — tipo, bytes, caminho — e o que o app faz com o
 * **texto** que volta. O servidor é um interceptor; a montagem do cliente (auth incluído) é a de
 * produção.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkWorkoutCheckInGatewayTest {

    private val sessionSyncId = "0b9b1c2e-4a4d-4d3e-9a8e-1f2e3d4c5b6a"
    private val clientUploadId = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f"

    // ------------------------------------------------------------------ upload (§16)

    @Test
    fun `o upload envia os bytes crus como image-jpeg, com os dois identificadores no caminho`() =
        runBlocking {
            val sent = mutableListOf<Request>()
            val gateway = gatewayWith(
                sent,
                status = 201,
                body = """{"mediaId":"m-1","width":1200,"height":1600,"byteSize":1234,"expiresAt":9}"""
            )
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

            val outcome = gateway.uploadMedia(sessionSyncId, clientUploadId, jpeg)

            val request = sent.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/social/checkin-media", request.url.encodedPath)
            assertEquals(sessionSyncId, request.url.queryParameter("sessionSyncId"))
            assertEquals(clientUploadId, request.url.queryParameter("clientUploadId"))
            // O tipo que monta o `express.raw` do servidor: com qualquer outro, o corpo chegaria
            // ao controller como `{}` e a foto seria recusada como INVALID_IMAGE.
            assertEquals("image/jpeg", request.body!!.contentType().toString())
            val body = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            assertArrayEquals(jpeg, body)
            // Autenticado, sempre (§48).
            assertEquals("Bearer token-de-teste", request.header("Authorization"))

            val media = (outcome as WorkoutCheckInOutcome.Success).data
            assertEquals("m-1", media.mediaId)
            assertEquals(1200 to 1600, media.width to media.height)
        }

    @Test
    fun `cada recusa do upload vira a classe de erro certa (H3 13)`() = runBlocking {
        val cases = listOf(
            Triple(422, "INVALID_IMAGE", WorkoutCheckInError.INVALID_IMAGE),
            Triple(422, "MEDIA_TOO_LARGE", WorkoutCheckInError.MEDIA_TOO_LARGE),
            // O parser binário recusa acima do teto antes do controller: status 413, e o código é
            // o nome do status — não MEDIA_TOO_LARGE. Para a tela é o mesmo fato.
            Triple(413, "PAYLOAD_TOO_LARGE", WorkoutCheckInError.MEDIA_TOO_LARGE),
            Triple(422, "MEDIA_QUOTA_EXCEEDED", WorkoutCheckInError.MEDIA_QUOTA_EXCEEDED),
            Triple(404, "MEDIA_NOT_FOUND", WorkoutCheckInError.MEDIA_NOT_FOUND),
            Triple(404, "SESSION_NOT_FOUND", WorkoutCheckInError.SESSION_NOT_FOUND),
            Triple(422, "CHECKIN_WINDOW_EXPIRED", WorkoutCheckInError.CHECKIN_WINDOW_EXPIRED),
            Triple(429, "RATE_LIMITED", WorkoutCheckInError.RATE_LIMITED),
            Triple(503, "SOCIAL_UNAVAILABLE", WorkoutCheckInError.UNAVAILABLE),
            Triple(401, "UNAUTHENTICATED", WorkoutCheckInError.AUTH_REQUIRED)
        )
        for ((status, code, expected) in cases) {
            val gateway = gatewayWith(
                status = status,
                body = """{"error":{"code":"$code","message":"x","requestId":"r"}}"""
            )
            assertEquals(
                code,
                WorkoutCheckInOutcome.Failure(expected),
                gateway.uploadMedia(sessionSyncId, clientUploadId, byteArrayOf(1))
            )
        }
    }

    @Test
    fun `sem rede o upload e NETWORK`() = runBlocking {
        val tokens = tokenProvider()
        val http = OkHttpClient.Builder()
            .addInterceptor(SparkAuthInterceptor(tokens))
            .addInterceptor(Interceptor { throw IOException("sem rede") })
            .build()
        val gateway = SparkWorkoutCheckInGateway(
            SparkBackendClient(baseUrl = "https://spark.example", tokens = tokens, httpClient = http)
        )

        assertEquals(
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK),
            gateway.uploadMedia(sessionSyncId, clientUploadId, byteArrayOf(1))
        )
    }

    // ------------------------------------------------------------------ resumo de treino (§28)

    @Test
    fun `o resumo do treino chega do texto do servidor para o dominio`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"items":[${checkInJson(
                """"workoutSummary":{"name":"Superiores A","startedAt":1789000000000,
                   "durationSeconds":3300,"exerciseCount":2,"completedSetCount":4,
                   "totalVolumeKg":4560,"exercises":[
                     {"name":"Supino reto","primaryMuscle":"CHEST",
                      "sets":[{"reps":10,"weightKg":80},{"reps":10,"weightKg":80}]},
                     {"name":"Prancha","sets":[{"durationSeconds":60}]}]}"""
            )}]}"""
        )

        val checkIn = (gateway.feed(null) as WorkoutCheckInOutcome.Success).data.single()
        val summary = checkIn.workoutSummary!!
        assertEquals("Superiores A", summary.name)
        assertEquals(1_789_000_000_000L, summary.startedAt)
        assertEquals(3300L, summary.durationSeconds)
        assertEquals(4560.0, summary.totalVolumeKg!!, 0.0)
        assertEquals(
            listOf(
                WorkoutSocialExercise(
                    name = "Supino reto",
                    primaryMuscle = "CHEST",
                    sets = listOf(WorkoutSocialSet(reps = 10, weightKg = 80.0), WorkoutSocialSet(reps = 10, weightKg = 80.0))
                ),
                WorkoutSocialExercise(name = "Prancha", sets = listOf(WorkoutSocialSet(durationSeconds = 60)))
            ),
            summary.exercises
        )
    }

    @Test
    fun `sem resumo, com resumo vazio ou de servidor antigo, o check-in fica como era`() =
        runBlocking {
            for (extra in listOf("", """"workoutSummary":{}""", """"workoutSummary":null""")) {
                val gateway = gatewayWith(body = """{"items":[${checkInJson(extra)}]}""")
                val checkIn = (gateway.feed(null) as WorkoutCheckInOutcome.Success).data.single()
                assertNull("extra=$extra", checkIn.workoutSummary)
            }
        }

    @Test
    fun `um campo novo do servidor nao quebra a leitura do Feed`() = runBlocking {
        val gateway = gatewayWith(
            body = """{"items":[${checkInJson(
                """"workoutSummary":{"name":"Pernas","somethingFromTheFuture":{"x":1}},"alsoNew":true"""
            )}]}"""
        )

        val checkIn = (gateway.feed(null) as WorkoutCheckInOutcome.Success).data.single()
        assertEquals("Pernas", checkIn.workoutSummary?.name)
        // Sem carga declarada, nenhuma série inventa peso.
        assertTrue(checkIn.workoutSummary?.exercises == null)
    }

    // ------------------------------------------------------------------ apoio

    private fun checkInJson(extra: String): String {
        val tail = if (extra.isBlank()) "" else ",$extra"
        return """{"type":"WORKOUT_CHECK_IN","checkInId":"c-1",
            "author":{"socialId":"s-1","displayName":"Igor"},"publishedAt":1,
            "caption":null,"media":null,"reactions":{},"currentUserReaction":null,
            "commentCount":0,"isCurrentUser":false,"canInteract":true$tail}"""
    }

    private fun tokenProvider(): AuthTokenProvider = object : AuthTokenProvider {
        override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult =
            AuthTokenResult.Token("token-de-teste")
    }

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}"
    ): SparkWorkoutCheckInGateway {
        val tokens = tokenProvider()
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
        return SparkWorkoutCheckInGateway(
            SparkBackendClient(baseUrl = "https://spark.example", tokens = tokens, httpClient = http)
        )
    }
}
