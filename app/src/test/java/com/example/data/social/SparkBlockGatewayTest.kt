package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.BlockError
import com.example.domain.social.BlockOutcome
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
class SparkBlockGatewayTest {

    private val targetSocialId = "target-social-id-123"

    @Test
    fun `blockUser envia POST com target no corpo sem vazar identificadores privados`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = """{"result":"BLOCKED","blockedSocialId":"$targetSocialId"}""")

        val result = gateway.blockUser(targetSocialId)

        assertTrue(result is BlockOutcome.Success)
        val req = sent.single()
        assertEquals("POST", req.method)
        assertTrue(req.url.toString().endsWith("/v1/social/blocks"))
        val body = req.bodyString()
        assertTrue(body.contains(targetSocialId))
        for (forbidden in listOf("ownerUid", "\"uid\"", "firebaseUid", "email")) {
            assertFalse("corpo não pode conter $forbidden: $body", body.contains(forbidden))
        }
    }

    @Test
    fun `unblockUser envia DELETE com id na rota`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = """{"result":"UNBLOCKED","unblockedSocialId":"$targetSocialId"}""")

        val result = gateway.unblockUser(targetSocialId)

        assertTrue(result is BlockOutcome.Success)
        val req = sent.single()
        assertEquals("DELETE", req.method)
        assertTrue(req.url.toString().endsWith("/v1/social/blocks/$targetSocialId"))
    }

    @Test
    fun `listBlockedUsers envia GET e converte itens`() = runBlocking {
        val sent = mutableListOf<Request>()
        val json = """{"blockedUsers":[{"socialId":"$targetSocialId","displayName":"Bloqueado","blockedAt":1700000000000}]}"""
        val gateway = gatewayWith(sent, body = json)

        val result = gateway.listBlockedUsers()

        assertTrue(result is BlockOutcome.Success)
        val users = (result as BlockOutcome.Success).value
        assertEquals(1, users.size)
        assertEquals(targetSocialId, users[0].socialId)
        assertEquals("Bloqueado", users[0].displayName)
        assertEquals(1700000000000L, users[0].blockedAt)
        assertEquals("GET", sent.single().method)
    }

    @Test
    fun `sem autenticacao nao sai requisicao`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val result = gateway.blockUser(targetSocialId)

        assertEquals(BlockOutcome.Failure(BlockError.AUTH_REQUIRED), result)
        assertTrue("nenhuma requisição deve sair sem conta", sent.isEmpty())
    }

    @Test
    fun `mapeia erros tipados do backend`() = runBlocking {
        val cases = listOf(
            BlockContract.ErrorCodes.CANNOT_BLOCK_SELF to BlockError.CANNOT_BLOCK_SELF,
            BlockContract.ErrorCodes.BLOCK_NOT_FOUND to BlockError.BLOCK_NOT_FOUND,
            BlockContract.ErrorCodes.SOCIAL_PROFILE_NOT_FOUND to BlockError.PROFILE_NOT_FOUND,
            SocialContract.ErrorCodes.API_RATE_LIMITED to BlockError.RATE_LIMITED
        )

        for ((code, expected) in cases) {
            val errorBody = """{"error":{"code":"$code","message":"msg","requestId":"r-1"}}"""
            val gateway = gatewayWith(status = 400, body = errorBody)
            assertEquals(BlockOutcome.Failure(expected), gateway.blockUser(targetSocialId))
        }
    }

    private fun Request.bodyString(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-valido")
    ): SparkBlockGateway {
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
                        .message("OK")
                        .body(body.toResponseBody(null))
                        .build()
                }
            )
            .build()

        return SparkBlockGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokens,
                httpClient = http
            )
        )
    }
}
