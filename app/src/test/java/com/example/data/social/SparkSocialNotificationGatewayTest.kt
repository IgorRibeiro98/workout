package com.example.data.social

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.social.SocialNotificationPreferences
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
 * Robolectric porque `SparkBackendClient` registra a falha de rede em `android.util.Log`.
 *
 * Até a auditoria de 2026-09-12 o teste rodava em JVM pura e passava por causa de
 * `isReturnDefaultValues`, que fazia o `Log` devolver `0` em silêncio — o mesmo mecanismo que
 * esconderia qualquer outra chamada a `android.*` num teste sem runner. Com a flag desligada, o
 * caminho de erro precisa do framework de verdade.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkSocialNotificationGatewayTest {

    private val testDeviceId = "dev-12345-abcde"
    private val testFcmToken = "fcm-token-test-xyz"

    @Test
    fun `registerDevice envia deviceId, platform ANDROID e token no corpo`() = runBlocking {
        val sent = mutableListOf<Request>()
        val responseBody = """
            {
                "id": "reg-1",
                "deviceId": "$testDeviceId",
                "platform": "ANDROID",
                "enabled": true,
                "createdAt": 1700000000000,
                "updatedAt": 1700000000000,
                "lastRegisteredAt": 1700000000000
            }
        """.trimIndent()
        val gateway = gatewayWith(sent, status = 201, body = responseBody)

        val result = gateway.registerDevice(deviceId = testDeviceId, fcmToken = testFcmToken)

        assertTrue(result.isSuccess)
        val registration = result.getOrThrow()
        assertEquals(testDeviceId, registration.deviceId)
        assertEquals("ANDROID", registration.platform)
        assertTrue(registration.enabled)

        val request = sent.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/notifications/devices"))
        val body = bodyOf(request)
        assertTrue(body.contains(testDeviceId))
        assertTrue(body.contains(testFcmToken))
        assertTrue(body.contains("ANDROID"))
    }

    @Test
    fun `unregisterDevice faz DELETE no path com deviceId`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, status = 204, body = "")

        val result = gateway.unregisterDevice(deviceId = testDeviceId)

        assertTrue(result.isSuccess)
        val request = sent.single()
        assertEquals("DELETE", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/notifications/devices/$testDeviceId"))
    }

    @Test
    fun `unregisterDevice aceita 404 como sucesso idempotente`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, status = 404, body = """{"error":"NOT_FOUND"}""")

        val result = gateway.unregisterDevice(deviceId = testDeviceId)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `getPreferences retorna preferencias decodificadas com sucesso`() = runBlocking {
        val sent = mutableListOf<Request>()
        val responseBody = """
            {
                "pushEnabled": true,
                "friendRequestReceived": true,
                "friendRequestAccepted": true,
                "challengeInvitationReceived": false,
                "challengeStartingSoon": true,
                "challengeEnded": false,
                "updatedAt": "2026-09-08T12:00:00.000Z"
            }
        """.trimIndent()
        val gateway = gatewayWith(sent, status = 200, body = responseBody)

        val result = gateway.getPreferences()

        assertTrue(result.isSuccess)
        val prefs = result.getOrThrow()
        assertEquals(
            SocialNotificationPreferences(
                pushEnabled = true,
                friendRequestReceived = true,
                friendRequestAccepted = true,
                challengeInvitationReceived = false,
                challengeStartingSoon = true,
                challengeEnded = false
            ),
            prefs
        )

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/notifications/preferences"))
    }

    @Test
    fun `updatePreferences envia payload parcial em PATCH e retorna preferencias atualizadas`() = runBlocking {
        val sent = mutableListOf<Request>()
        val responseBody = """
            {
                "pushEnabled": false,
                "friendRequestReceived": true,
                "friendRequestAccepted": true,
                "challengeInvitationReceived": true,
                "challengeStartingSoon": true,
                "challengeEnded": true,
                "updatedAt": "2026-09-08T12:00:00.000Z"
            }
        """.trimIndent()
        val gateway = gatewayWith(sent, status = 200, body = responseBody)

        val result = gateway.updatePreferences(pushEnabled = false)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().pushEnabled)

        val request = sent.single()
        assertEquals("PATCH", request.method)
        assertTrue(request.url.toString().endsWith("/v1/social/notifications/preferences"))
        val body = bodyOf(request)
        assertTrue(body.contains("\"pushEnabled\":false"))
    }

    @Test
    fun `sem backend configurado retorna erro sem tentar rede`() = runBlocking {
        val gateway = SparkSocialNotificationGateway(client = null)

        assertFalse(gateway.isConfigured)
        assertTrue(gateway.registerDevice(testDeviceId, testFcmToken).isFailure)
        assertTrue(gateway.unregisterDevice(testDeviceId).isFailure)
        assertTrue(gateway.getPreferences().isFailure)
        assertTrue(gateway.updatePreferences(pushEnabled = true).isFailure)
    }

    @Test
    fun `sem autenticacao retorna falha`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, token = AuthTokenResult.SignedOut)

        val result = gateway.getPreferences()

        assertTrue(result.isFailure)
        assertEquals("Autenticação necessária", result.exceptionOrNull()?.message)
    }

    @Test
    fun `falha de rede retorna falha`() = runBlocking {
        val gateway = SparkSocialNotificationGateway(
            SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokenProvider(AuthTokenResult.Token("token")),
                httpClient = OkHttpClient.Builder()
                    .addInterceptor(SparkAuthInterceptor(tokenProvider(AuthTokenResult.Token("token"))))
                    .addInterceptor(Interceptor { throw IOException("sem rota para o host") })
                    .build()
            )
        )

        val result = gateway.getPreferences()

        assertTrue(result.isFailure)
        assertEquals("Falha de rede", result.exceptionOrNull()?.message)
    }

    @Test
    fun `HTTP 500 do backend retorna erro tipado com status`() = runBlocking {
        val sent = mutableListOf<Request>()
        val gateway = gatewayWith(sent, status = 500, body = """{"error":"INTERNAL"}""")

        val result = gateway.getPreferences()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP 500") == true)
    }

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-de-teste")
    ): SparkSocialNotificationGateway {
        val tokens = tokenProvider(token)
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

        return SparkSocialNotificationGateway(
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
