package com.example.data.social

object WorkoutShareContract {
    // O prefixo `v1/` faz parte do caminho, como em todos os outros contratos sociais: o
    // `SparkBackendClient` monta a URL como `baseUrl + "/" + path` e não acrescenta versão
    // nenhuma. Sem ele, todas as rotas de compartilhamento batiam em `/social/workout-shares`.
    const val WORKOUT_SHARES_PATH = "v1/social/workout-shares"

    fun receivedPath(): String = "$WORKOUT_SHARES_PATH/received"
    fun sentPath(): String = "$WORKOUT_SHARES_PATH/sent"
    fun detailPath(shareId: String): String = "$WORKOUT_SHARES_PATH/$shareId"
    fun acceptPath(shareId: String): String = "$WORKOUT_SHARES_PATH/$shareId/accept"
    fun completeImportPath(shareId: String): String = "$WORKOUT_SHARES_PATH/$shareId/complete-import"
    fun declinePath(shareId: String): String = "$WORKOUT_SHARES_PATH/$shareId/decline"
    fun cancelPath(shareId: String): String = "$WORKOUT_SHARES_PATH/$shareId/cancel"

    /**
     * Os códigos **como o servidor os escreve** (`WorkoutShareErrorCodes`, no backend).
     *
     * Três deles estavam com outro texto aqui até a T19.3 (`SHARE_NOT_FOUND`, `INVALID_SHARE_STATE`,
     * `RATE_LIMITED`) e nunca casavam: bloqueio e cancelamento caíam no fallback por status HTTP e
     * viravam um "falha" genérico. O aceite passou a depender deles para explicar o motivo.
     */
    object ErrorCodes {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val API_RATE_LIMITED = "API_RATE_LIMITED"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val FRIENDSHIP_REQUIRED = "FRIENDSHIP_REQUIRED"
        const val CANNOT_SHARE_WITH_SELF = "CANNOT_SHARE_WITH_SELF"
        const val BLOCKED_USER = "BLOCKED_USER"
        const val RECIPIENT_NOT_FOUND = "RECIPIENT_NOT_FOUND"
        const val SHARE_NOT_FOUND = "WORKOUT_SHARE_NOT_FOUND"
        const val SHARE_NOT_AVAILABLE = "SHARE_NOT_AVAILABLE"
        const val INVALID_SNAPSHOT = "INVALID_SNAPSHOT"
        const val RATE_LIMITED = "WORKOUT_SHARE_RATE_LIMITED"
        const val CONFLICT = "WORKOUT_SHARE_CONFLICT"
    }
}
