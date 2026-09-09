package com.example.domain.social

/**
 * Preferências de notificação social da conta (T17.5 §21–§28).
 */
data class SocialNotificationPreferences(
    val pushEnabled: Boolean = false,
    val friendRequestReceived: Boolean = true,
    val friendRequestAccepted: Boolean = true,
    val challengeInvitationReceived: Boolean = true,
    val challengeStartingSoon: Boolean = true,
    val challengeEnded: Boolean = true,
    val workoutShareReceived: Boolean = true
) {
    companion object {
        val DEFAULT = SocialNotificationPreferences(
            pushEnabled = false,
            friendRequestReceived = true,
            friendRequestAccepted = true,
            challengeInvitationReceived = true,
            challengeStartingSoon = true,
            challengeEnded = true,
            workoutShareReceived = true
        )
    }
}

/**
 * Tipos de notificação social suportados no Spark (T17.5 §3).
 */
enum class SocialNotificationType {
    FRIEND_REQUEST_RECEIVED,
    FRIEND_REQUEST_ACCEPTED,
    CHALLENGE_INVITATION_RECEIVED,
    CHALLENGE_STARTING_SOON,
    CHALLENGE_ENDED,
    WORKOUT_SHARE_RECEIVED;

    companion object {
        fun fromStringOrNull(value: String): SocialNotificationType? =
            entries.find { it.name == value }
    }
}
