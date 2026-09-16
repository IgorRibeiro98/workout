package com.example.domain.multiplayer

/**
 * A fronteira HTTP do multiplayer remoto (T19.5).
 *
 * Um gateway, e não um repositório: a sala é server-authoritative e não tem dado local para
 * reconciliar. O que este aparelho persiste sobre ela é só o vínculo sessão ↔ sala
 * (`WorkoutSessionMultiplayerLinkEntity`), e isso é assunto do motor, não daqui.
 *
 * Toda chamada passa pelo `SparkBackendClient` — um cliente, um interceptor, um lugar montando
 * `Authorization: Bearer`. Sem endereço de backend, tudo responde [MultiplayerError.NOT_CONFIGURED]
 * sem abrir conexão.
 */
interface MultiplayerGateway {
    val isConfigured: Boolean

    suspend fun createRoom(
        clientRequestId: String,
        inviteeSocialId: String,
        workout: MultiplayerWorkoutBlueprint
    ): MultiplayerOutcome<MultiplayerRoom>

    suspend fun listInvitations(): MultiplayerOutcome<List<MultiplayerInvitation>>

    suspend fun getRoom(roomId: String): MultiplayerOutcome<MultiplayerRoom>

    /** Entra (INVITED → ACTIVE) ou reentra: a membership continua sendo uma só. */
    suspend fun join(roomId: String): MultiplayerOutcome<MultiplayerRoom>

    suspend fun leave(roomId: String): MultiplayerOutcome<Unit>

    suspend fun close(roomId: String): MultiplayerOutcome<Unit>

    suspend fun publish(roomId: String, events: List<OutgoingMultiplayerEvent>): MultiplayerOutcome<MultiplayerPublishReceipt>

    /**
     * Os eventos depois de [after]. Com [waitMs] > 0 a resposta fica aberta até haver novidade ou o
     * prazo vencer (long-polling); o servidor rebaixa o prazo ao teto dele.
     */
    suspend fun poll(roomId: String, after: Long, waitMs: Long): MultiplayerOutcome<MultiplayerEventsPage>
}

sealed interface MultiplayerOutcome<out T> {
    data class Success<T>(val data: T) : MultiplayerOutcome<T>
    data class Failure(val error: MultiplayerError) : MultiplayerOutcome<Nothing>
}

/**
 * Por que uma chamada falhou, no vocabulário do domínio.
 *
 * O que importa para o coordenador é a **classe** da falha: [NETWORK] e [UNAVAILABLE] são
 * transitórias (reconectar); [ROOM_CLOSED], [ROOM_EXPIRED], [MEMBER_LEFT] e [ROOM_NOT_FOUND] são
 * finais (a sala acabou para este aparelho); o resto é recusa de uma ação específica.
 */
enum class MultiplayerError {
    NOT_CONFIGURED,
    AUTH_REQUIRED,
    NETWORK,
    UNAVAILABLE,
    RATE_LIMITED,
    SOCIAL_NOT_ENABLED,
    FRIENDSHIP_REQUIRED,
    CANNOT_INVITE_SELF,
    ROOM_NOT_FOUND,
    ROOM_CLOSED,
    ROOM_EXPIRED,
    NOT_A_MEMBER,
    MEMBER_LEFT,
    NOT_HOST,
    INVALID_REQUEST,
    REJECTED;

    /** A sala acabou para este aparelho: não há o que reconectar. */
    val isTerminal: Boolean
        get() = this == ROOM_NOT_FOUND || this == ROOM_CLOSED || this == ROOM_EXPIRED || this == MEMBER_LEFT

    /** Vale a pena tentar de novo sem mudar nada. */
    val isTransient: Boolean
        get() = this == NETWORK || this == UNAVAILABLE || this == RATE_LIMITED
}
