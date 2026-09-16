package com.example.data.multiplayer

/**
 * Os caminhos e códigos do multiplayer remoto (T19.5), **como o servidor os escreve**
 * (`MultiplayerErrorCodes` em `backend/src/modules/multiplayer/multiplayer.contract.ts`).
 */
object MultiplayerContract {
    // O prefixo `v1/` faz parte do caminho: o `SparkBackendClient` monta `baseUrl + "/" + path`.
    const val ROOMS_PATH = "v1/multiplayer/rooms"

    fun invitationsPath(): String = "$ROOMS_PATH/invitations"
    fun roomPath(roomId: String): String = "$ROOMS_PATH/$roomId"
    fun joinPath(roomId: String): String = "$ROOMS_PATH/$roomId/join"
    fun leavePath(roomId: String): String = "$ROOMS_PATH/$roomId/leave"
    fun closePath(roomId: String): String = "$ROOMS_PATH/$roomId/close"
    fun eventsPath(roomId: String): String = "$ROOMS_PATH/$roomId/events"
    fun pollPath(roomId: String, after: Long, waitMs: Long): String =
        "$ROOMS_PATH/$roomId/events?after=$after&wait=$waitMs"

    /**
     * O long-poll pede ao servidor menos do que o cliente aceita esperar: o teto do servidor é
     * 20 s, e a leitura HTTP desta chamada tem folga sobre ele (ver [POLL_READ_TIMEOUT_SECONDS]).
     */
    const val POLL_WAIT_MS = 15_000L
    const val POLL_READ_TIMEOUT_SECONDS = 40L

    object ErrorCodes {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val API_RATE_LIMITED = "API_RATE_LIMITED"
        const val INVALID_REQUEST = "MULTIPLAYER_INVALID_REQUEST"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val FRIENDSHIP_REQUIRED = "FRIENDSHIP_REQUIRED"
        const val CANNOT_INVITE_SELF = "MULTIPLAYER_CANNOT_INVITE_SELF"
        const val ROOM_NOT_FOUND = "MULTIPLAYER_ROOM_NOT_FOUND"
        const val ROOM_CLOSED = "MULTIPLAYER_ROOM_CLOSED"
        const val ROOM_EXPIRED = "MULTIPLAYER_ROOM_EXPIRED"
        const val NOT_A_MEMBER = "MULTIPLAYER_NOT_A_MEMBER"
        const val MEMBER_LEFT = "MULTIPLAYER_MEMBER_LEFT"
        const val NOT_HOST = "MULTIPLAYER_NOT_HOST"
        const val CONFLICT = "MULTIPLAYER_CONFLICT"
        const val EVENT_LIMIT = "MULTIPLAYER_EVENT_LIMIT"
        const val RATE_LIMITED = "MULTIPLAYER_RATE_LIMITED"
    }
}
