package com.example.data.account

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.backup.CloudDataBindingDao
import com.example.data.backup.CloudDataBindingEntity
import com.example.data.remote.spark.SparkAuthInterceptor
import com.example.data.remote.spark.SparkBackendClient
import com.example.domain.account.AccountDeletionError
import com.example.domain.account.AccountDeletionOutcome
import com.example.domain.auth.AuthTokenProvider
import com.example.domain.auth.AuthTokenResult
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SparkAccountDeletionGatewayTest {

    @Test
    fun `deleteAccount envia DELETE v1 account, desvincula cloud binding e desconecta auth`() = runBlocking {
        val sent = mutableListOf<Request>()
        var bindingDeleted = false
        val fakeDao = object : CloudDataBindingDao {
            override suspend fun get(id: Int): CloudDataBindingEntity? = null
            override fun observe(id: Int): Flow<CloudDataBindingEntity?> = emptyFlow()
            override suspend fun insertIfAbsent(binding: CloudDataBindingEntity): Long = 1L
            override suspend fun deleteBinding() { bindingDeleted = true }
            override suspend fun markBackupSucceeded(ownerUid: String, state: String, backupId: String, backupAt: Long, id: Int) {}
        }
        val fakeAuth = FakeAuthGateway(initialAccount = SparkAccount("uid-123", "User"))

        val gateway = gatewayWith(
            sent = sent,
            status = 200,
            body = """{"status":"DELETED"}""",
            bindingDao = fakeDao,
            authGateway = fakeAuth
        )

        val outcome = gateway.deleteAccount()

        assertEquals(AccountDeletionOutcome.Success, outcome)
        val req = sent.single()
        assertEquals("DELETE", req.method)
        assertTrue(req.url.toString().endsWith("/v1/account"))
        assertTrue("binding local deve ser desvinculado", bindingDeleted)
        assertTrue("sessão deve ser desconectada", fakeAuth.signOutCalls > 0)
    }

    @Test
    fun `sem autenticacao nao envia DELETE`() = runBlocking {
        val sent = mutableListOf<Request>()
        val fakeDao = object : CloudDataBindingDao {
            override suspend fun get(id: Int): CloudDataBindingEntity? = null
            override fun observe(id: Int): Flow<CloudDataBindingEntity?> = emptyFlow()
            override suspend fun insertIfAbsent(binding: CloudDataBindingEntity): Long = 1L
            override suspend fun deleteBinding() {}
            override suspend fun markBackupSucceeded(ownerUid: String, state: String, backupId: String, backupAt: Long, id: Int) {}
        }
        val gateway = gatewayWith(
            sent = sent,
            token = AuthTokenResult.SignedOut,
            bindingDao = fakeDao,
            authGateway = FakeAuthGateway()
        )

        val outcome = gateway.deleteAccount()

        assertEquals(AccountDeletionOutcome.Failure(AccountDeletionError.AUTH_REQUIRED), outcome)
        assertTrue(sent.isEmpty())
    }

    private fun gatewayWith(
        sent: MutableList<Request> = mutableListOf(),
        status: Int = 200,
        body: String = "{}",
        token: AuthTokenResult = AuthTokenResult.Token("token-valido"),
        bindingDao: CloudDataBindingDao,
        authGateway: FakeAuthGateway
    ): SparkAccountDeletionGateway {
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

        return SparkAccountDeletionGateway(
            client = SparkBackendClient(
                baseUrl = "https://spark.example",
                tokens = tokens,
                httpClient = http
            ),
            cloudDataBindingDao = bindingDao,
            authGateway = authGateway
        )
    }
}
