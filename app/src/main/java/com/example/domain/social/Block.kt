package com.example.domain.social

/**
 * Usuário bloqueado no domínio social (T17.6).
 *
 * Bloquear é server-authoritative e bilateral:
 * - A blocks B impede qualquer interação entre A e B.
 * - Amizades e solicitações mútuas são removidas.
 * - O ato de bloquear não gera push.
 * - Desbloquear não restaura relações anteriores.
 */
data class BlockedUser(
    val socialId: String,
    val displayName: String,
    val blockedAt: Long
)

/** Desfecho de operações de bloqueio social. */
sealed interface BlockOutcome<out T> {
    data class Success<T>(val value: T) : BlockOutcome<T>
    data class Failure(val error: BlockError) : BlockOutcome<Nothing>
}

/** Erros do domínio de bloqueio. */
enum class BlockError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    SOCIAL_NOT_ENABLED,
    SOCIAL_DISABLED,
    PROFILE_NOT_FOUND,
    CANNOT_BLOCK_SELF,
    BLOCK_NOT_FOUND,
    RATE_LIMITED,
    REJECTED,
    UNAVAILABLE,
    NETWORK
}
