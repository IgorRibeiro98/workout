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

    object ErrorCodes {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val API_RATE_LIMITED = "API_RATE_LIMITED"
        const val SOCIAL_NOT_ENABLED = "SOCIAL_NOT_ENABLED"
        const val FRIENDSHIP_REQUIRED = "FRIENDSHIP_REQUIRED"
        const val CANNOT_SHARE_WITH_SELF = "CANNOT_SHARE_WITH_SELF"
        const val BLOCKED_USER = "BLOCKED_USER"
        const val RECIPIENT_NOT_FOUND = "RECIPIENT_NOT_FOUND"
        const val SHARE_NOT_FOUND = "SHARE_NOT_FOUND"
        const val INVALID_SHARE_STATE = "INVALID_SHARE_STATE"
        const val INVALID_SNAPSHOT = "INVALID_SNAPSHOT"
        const val RATE_LIMITED = "RATE_LIMITED"
    }
}
