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
    WORKOUT_SHARE_RECEIVED,

    /**
     * Convite para um Squad (T17.11 §90).
     *
     * A **única** categoria que a T17.11 acrescenta. Não existe push para "entrou", "saiu", "foi
     * removido", "posse transferida", "check-in compartilhado" nem "Squad excluído" (§95).
     *
     * O payload continua data-only e mínimo (§91): `entityId` é o `invitationId`, e o nome do
     * Squad, o de quem convidou e os dos membros **não** viajam nele.
     */
    GROUP_INVITATION_RECEIVED;

    companion object {
        fun fromStringOrNull(value: String): SocialNotificationType? =
            entries.find { it.name == value }
    }
}
