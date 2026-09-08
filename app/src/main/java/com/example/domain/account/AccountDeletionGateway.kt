package com.example.domain.account

/** Desfecho da exclusão de conta Spark. */
sealed interface AccountDeletionOutcome {
    data object Success : AccountDeletionOutcome
    data class Failure(val error: AccountDeletionError) : AccountDeletionOutcome
}

/** Erros durante a exclusão de conta. */
enum class AccountDeletionError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    RATE_LIMITED,
    REJECTED,
    UNAVAILABLE,
    NETWORK
}

/**
 * Fronteira para exclusão de conta Spark (T17.6).
 *
 * Remove a conta online, backups e dados sociais na nuvem.
 * O banco de dados local Room é preservado (local-first).
 */
interface AccountDeletionGateway {

    /** `true` quando existe endereço de Spark Backend configurado neste build. */
    val isConfigured: Boolean

    /**
     * Executa a exclusão da conta no servidor, desvincula o dataset local da nuvem
     * e desconecta a sessão de autenticação.
     */
    suspend fun deleteAccount(): AccountDeletionOutcome
}
