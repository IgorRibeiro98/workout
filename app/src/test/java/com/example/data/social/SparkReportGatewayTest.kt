package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.ReportError
import com.example.domain.social.ReportOutcome
import com.example.domain.social.ReportReason
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
class SparkReportGatewayTest {

    private val targetSocialId = "reported-social-id-456"

    @Test
    fun `reportUser envia POST com target e motivo enum no corpo`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, body = """{"result":"REPORT_RECEIVED","reportId":"rep-1"}""")

        val result = gateway.reportUser(targetSocialId, ReportReason.HARASSMENT)

        assertTrue(result is ReportOutcome.Success)
        val req = sent.single()
        assertEquals("POST", req.method)
        assertTrue(req.url.toString().endsWith("/v1/social/reports"))
        val body = req.bodyString()
        assertTrue(body.contains(targetSocialId))
        assertTrue(body.contains("HARASSMENT"))
        for (forbidden in listOf("ownerUid", "\"uid\"", "firebaseUid", "email")) {
            assertFalse("corpo não pode conter $forbidden: $body", body.contains(forbidden))
        }
    }

    @Test
    fun `sem autenticacao nao sai requisicao`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val result = gateway.reportUser(targetSocialId, ReportReason.SPAM)

        assertEquals(ReportOutcome.Failure(ReportError.AUTH_REQUIRED), result)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `mapeia erros tipados de denuncia`() = runBlocking {
        val cases = listOf(
            ReportContract.ErrorCodes.CANNOT_REPORT_SELF to ReportError.CANNOT_REPORT_SELF,
            ReportContract.ErrorCodes.NO_LEGITIMATE_CONTEXT to ReportError.NO_LEGITIMATE_CONTEXT,
            ReportContract.ErrorCodes.REPORT_RATE_LIMITED to ReportError.RATE_LIMITED,
            SocialContract.ErrorCodes.SOCIAL_NOT_ENABLED to ReportError.SOCIAL_NOT_ENABLED
        )

        for ((code, expected) in cases) {
            val errorBody = """{"error":{"code":"$code","message":"msg","requestId":"r-1"}}"""
            val gateway = gatewayWith(status = 400, body = errorBody)
            assertEquals(ReportOutcome.Failure(expected), gateway.reportUser(targetSocialId, ReportReason.OTHER))
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
    ): SparkReportGateway {
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

        return SparkReportGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokens,
                httpClient = http
            )
        )
    }
}
