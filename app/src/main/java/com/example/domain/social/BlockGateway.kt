package com.example.domain.social

/**
 * A fronteira de bloqueio social com o Spark Backend (T17.6).
 *
 * Bloquear é server-authoritative e bilateral.
 * Sem Room, sem outbox local e sem retentativa automática.
 */
interface BlockGateway {

    /** `true` quando existe endereço de Spark Backend configurado neste build. */
    val isConfigured: Boolean

    /** Bloqueia um usuário pelo seu [socialId]. */
    suspend fun blockUser(socialId: String): BlockOutcome<Unit>

    /** Desbloqueia um usuário previamente bloqueado. */
    suspend fun unblockUser(socialId: String): BlockOutcome<Unit>

    /** Lista os usuários bloqueados pelo usuário autenticado. */
    suspend fun listBlockedUsers(): BlockOutcome<List<BlockedUser>>
}
