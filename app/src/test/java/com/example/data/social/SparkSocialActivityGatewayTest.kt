package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.SocialActivityError
import com.example.domain.social.SocialActivityOutcome
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkSocialActivityGatewayTest {

    @Test
    fun `getRecentFriendActivity faz GET para v1 social activity e mapeia lista`() = runBlocking {
        val sent = mutableListOf<Request>()
        val json = """
            {
                "items": [
                    {
                        "type": "TRAINING_DAY",
                        "actor": {"socialId": "soc-1", "displayName": "Beto"},
                        "daysAgo": 0
                    },
                    {
                        "type": "TRAINING_DAY",
                        "actor": {"socialId": "soc-2", "displayName": "Carla"},
                        "daysAgo": 3
                    }
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(sent, body = json)
        val outcome = gateway.getRecentFriendActivity()

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/activity"))

        assertTrue(outcome is SocialActivityOutcome.Success)
        val items = (outcome as SocialActivityOutcome.Success).data
        assertEquals(2, items.size)
        assertEquals("soc-1", items[0].socialId)
        assertEquals("Beto", items[0].displayName)
        assertEquals(0, items[0].daysAgo)
        assertEquals("Carla", items[1].displayName)
        assertEquals(3, items[1].daysAgo)
    }

    @Test
    fun `getFriendRankingLast7Days faz GET para v1 social rankings last-7-days e mapeia ranking`() = runBlocking {
        val sent = mutableListOf<Request>()
        val json = """
            {
                "type": "WORKOUTS_COMPLETED_LAST_7_DAYS",
                "participantCount": 2,
                "entries": [
                    {"socialId": "soc-1", "displayName": "Beto", "score": 5, "rank": 1, "isCurrentUser": false},
                    {"socialId": "soc-2", "displayName": "Ana", "score": 3, "rank": 2, "isCurrentUser": true}
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(sent, body = json)
        val outcome = gateway.getFriendRankingLast7Days()

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/rankings/last-7-days"))

        assertTrue(outcome is SocialActivityOutcome.Success)
        val leaderboard = (outcome as SocialActivityOutcome.Success).data
        assertEquals(2, leaderboard.participantCount)
        assertEquals(2, leaderboard.entries.size)
        assertEquals(1, leaderboard.entries[0].rank)
        assertEquals(5, leaderboard.entries[0].score)
        assertFalse(leaderboard.entries[0].isCurrentUser)
        assertTrue(leaderboard.entries[1].isCurrentUser)
    }

    @Test
    fun `getFriendRankingLast7Days com 403 RANKING_NOT_ENABLED retorna erro tipado correspondente`() = runBlocking {
        val sent = mutableListOf<Request>()
        val json = """
            {
                "error": {
                    "code": "RANKING_NOT_ENABLED",
                    "message": "Friend ranking participation is not enabled"
                }
            }
        """.trimIndent()

        val gateway = gatewayWith(sent, status = 403, body = json)
        val outcome = gateway.getFriendRankingLast7Days()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.RANKING_NOT_ENABLED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `sem backend configurado retorna NOT_CONFIGURED`() = runBlocking {
        val gateway = SparkSocialActivityGateway(null)

        assertFalse(gateway.isConfigured)
        val outcome = gateway.getRecentFriendActivity()
        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.NOT_CONFIGURED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `sem token retorna AUTH_REQUIRED`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val outcome = gateway.getRecentFriendActivity()
        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.AUTH_REQUIRED, (outcome as SocialActivityOutcome.Failure).error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `getRecentFriendActivity rejeita type desconhecido com REJECTED`() = runBlocking {
        val json = """
            {
                "items": [
                    {
                        "type": "UNKNOWN_TYPE",
                        "actor": {"socialId": "soc-1", "displayName": "Beto"},
                        "daysAgo": 0
                    }
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(body = json)
        val outcome = gateway.getRecentFriendActivity()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `getRecentFriendActivity rejeita socialId ou displayName em branco com REJECTED`() = runBlocking {
        val json = """
            {
                "items": [
                    {
                        "type": "TRAINING_DAY",
                        "actor": {"socialId": "   ", "displayName": "Beto"},
                        "daysAgo": 0
                    }
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(body = json)
        val outcome = gateway.getRecentFriendActivity()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `getRecentFriendActivity rejeita daysAgo fora de faixa com REJECTED`() = runBlocking {
        val jsonNegative = """
            {
                "items": [
                    {
                        "type": "TRAINING_DAY",
                        "actor": {"socialId": "soc-1", "displayName": "Beto"},
                        "daysAgo": -1
                    }
                ]
            }
        """.trimIndent()

        val gatewayNeg = gatewayWith(body = jsonNegative)
        val outcomeNeg = gatewayNeg.getRecentFriendActivity()
        assertTrue(outcomeNeg is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcomeNeg as SocialActivityOutcome.Failure).error)

        val jsonExcess = """
            {
                "items": [
                    {
                        "type": "TRAINING_DAY",
                        "actor": {"socialId": "soc-1", "displayName": "Beto"},
                        "daysAgo": 14
                    }
                ]
            }
        """.trimIndent()

        val gatewayExc = gatewayWith(body = jsonExcess)
        val outcomeExc = gatewayExc.getRecentFriendActivity()
        assertTrue(outcomeExc is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcomeExc as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `getFriendRankingLast7Days rejeita type desconhecido com REJECTED`() = runBlocking {
        val json = """
            {
                "type": "ALL_TIME_RANKING",
                "participantCount": 1,
                "entries": [
                    {"socialId": "soc-1", "displayName": "Beto", "score": 5, "rank": 1, "isCurrentUser": true}
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(body = json)
        val outcome = gateway.getFriendRankingLast7Days()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `getFriendRankingLast7Days rejeita score negativo com REJECTED`() = runBlocking {
        val json = """
            {
                "type": "WORKOUTS_COMPLETED_LAST_7_DAYS",
                "participantCount": 1,
                "entries": [
                    {"socialId": "soc-1", "displayName": "Beto", "score": -1, "rank": 1, "isCurrentUser": true}
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(body = json)
        val outcome = gateway.getFriendRankingLast7Days()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `getFriendRankingLast7Days rejeita rank invalido zero ou negativo com REJECTED`() = runBlocking {
        val json = """
            {
                "type": "WORKOUTS_COMPLETED_LAST_7_DAYS",
                "participantCount": 1,
                "entries": [
                    {"socialId": "soc-1", "displayName": "Beto", "score": 5, "rank": 0, "isCurrentUser": true}
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(body = json)
        val outcome = gateway.getFriendRankingLast7Days()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcome as SocialActivityOutcome.Failure).error)
    }

    @Test
    fun `getFriendRankingLast7Days rejeita incoerencia de participantCount com REJECTED`() = runBlocking {
        // participantCount menor que entries.size
        val json = """
            {
                "type": "WORKOUTS_COMPLETED_LAST_7_DAYS",
                "participantCount": 1,
                "entries": [
                    {"socialId": "soc-1", "displayName": "Beto", "score": 5, "rank": 1, "isCurrentUser": false},
                    {"socialId": "soc-2", "displayName": "Ana", "score": 3, "rank": 2, "isCurrentUser": true}
                ]
            }
        """.trimIndent()

        val gateway = gatewayWith(body = json)
        val outcome = gateway.getFriendRankingLast7Days()

        assertTrue(outcome is SocialActivityOutcome.Failure)
        assertEquals(SocialActivityError.REJECTED, (outcome as SocialActivityOutcome.Failure).error)
    }

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("test-token")
    ): SparkSocialActivityGateway {
        val tokens = object : AuthTokenProvider {
            override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult = token
        }
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

        return SparkSocialActivityGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokens,
                httpClient = http
            )
        )
    }
}
