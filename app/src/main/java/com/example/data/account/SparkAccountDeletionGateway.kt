package com.example.data.account

import com.example.data.backup.CloudDataBindingDao
import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.data.sync.DeviceIdProvider
import com.example.domain.account.AccountDeletionError
import com.example.domain.account.AccountDeletionGateway
import com.example.domain.account.AccountDeletionOutcome
import com.example.domain.auth.AuthGateway
import com.example.service.PushAccountScope

/**
 * Implementação da exclusão de conta Spark (T17.6).
 *
 * Exclui a conta no servidor e desvincula localmente a Conta Spark.
 * Os treinos locais no Room são estritamente preservados (local-first).
 */
class SparkAccountDeletionGateway(
    private val client: SparkBackendClient?,
    private val cloudDataBindingDao: CloudDataBindingDao,
    private val authGateway: AuthGateway,
    private val pushAccountScope: PushAccountScope? = null,
    private val deviceIdProvider: DeviceIdProvider? = null
) : AccountDeletionGateway {

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun deleteAccount(): AccountDeletionOutcome {
        val activeClient = client ?: return AccountDeletionOutcome.Failure(AccountDeletionError.NOT_CONFIGURED)

        val outcome = activeClient.delete("v1/account")

        return when (outcome) {
            SparkHttpOutcome.NotConfigured -> AccountDeletionOutcome.Failure(AccountDeletionError.NOT_CONFIGURED)
            SparkHttpOutcome.SignedOut -> AccountDeletionOutcome.Failure(AccountDeletionError.AUTH_REQUIRED)
            SparkHttpOutcome.NetworkFailure -> AccountDeletionOutcome.Failure(AccountDeletionError.NETWORK)
            is SparkHttpOutcome.Response -> if (outcome.code in SUCCESS_RANGE) {
                // Desvincula o dataset local da nuvem — o banco volta a ser local sem dono
                cloudDataBindingDao.deleteBinding()

                // Limpa registro de push
                pushAccountScope?.clearRegisteredAccount()

                // Desconecta a sessão de autenticação local
                authGateway.signOut()

                AccountDeletionOutcome.Success
            } else {
                AccountDeletionOutcome.Failure(mapError(outcome.code))
            }
        }
    }

    private fun mapError(statusCode: Int): AccountDeletionError = when {
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN -> AccountDeletionError.AUTH_REQUIRED
        statusCode == HTTP_TOO_MANY_REQUESTS -> AccountDeletionError.RATE_LIMITED
        statusCode >= HTTP_SERVER_ERROR -> AccountDeletionError.UNAVAILABLE
        else -> AccountDeletionError.REJECTED
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
