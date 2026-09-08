package com.example.service

import com.example.domain.social.SocialNotificationType

/**
 * Destinos canônicos seguros para notificações sociais (T17.5.1 Problema 3).
 *
 * Princípios de segurança:
 * 1. Mapeamento fechado por allowlist (sem rotas arbitrárias vindas do FCM).
 * 2. CHALLENGE_INVITATION_RECEIVED leva à lista de desafios onde o convite pode ser aceito/recusado.
 *    Nunca abre ChallengeDetail diretamente para quem não é participante ativo (T17.3).
 * 3. CHALLENGE_STARTING_SOON e CHALLENGE_ENDED abrem ChallengeDetail(challengeId) para participantes.
 */
sealed interface NotificationNavDestination {
    data object FriendRequests : NotificationNavDestination
    data object Friends : NotificationNavDestination
    data object Challenges : NotificationNavDestination
    data class ChallengeDetail(val challengeId: String) : NotificationNavDestination
}

object SocialNotificationNavigationResolver {
    fun resolve(type: SocialNotificationType, entityId: String): NotificationNavDestination {
        return when (type) {
            SocialNotificationType.FRIEND_REQUEST_RECEIVED -> NotificationNavDestination.FriendRequests
            SocialNotificationType.FRIEND_REQUEST_ACCEPTED -> NotificationNavDestination.Friends
            SocialNotificationType.CHALLENGE_INVITATION_RECEIVED -> NotificationNavDestination.Challenges
            SocialNotificationType.CHALLENGE_STARTING_SOON -> NotificationNavDestination.ChallengeDetail(entityId)
            SocialNotificationType.CHALLENGE_ENDED -> NotificationNavDestination.ChallengeDetail(entityId)
        }
    }
}
