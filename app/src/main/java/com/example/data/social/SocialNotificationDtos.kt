package com.example.data.social

import kotlinx.serialization.Serializable

@Serializable
data class RegisterPushDeviceRequestDto(
    val deviceId: String,
    val platform: String = "ANDROID",
    val fcmToken: String
)

@Serializable
data class PushDeviceRegistrationDto(
    val deviceId: String,
    val platform: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class NotificationPreferencesDto(
    val pushEnabled: Boolean,
    val friendRequestReceived: Boolean,
    val friendRequestAccepted: Boolean,
    val challengeInvitationReceived: Boolean,
    val challengeStartingSoon: Boolean,
    val challengeEnded: Boolean
)

@Serializable
data class UpdateNotificationPreferencesRequestDto(
    val pushEnabled: Boolean? = null,
    val friendRequestReceived: Boolean? = null,
    val friendRequestAccepted: Boolean? = null,
    val challengeInvitationReceived: Boolean? = null,
    val challengeStartingSoon: Boolean? = null,
    val challengeEnded: Boolean? = null
)

/**
 * Payload recebido via FCM data-only message (T17.5 §86).
 *
 * Exclusivamente campos de sinal:
 * - v: versão do protocolo de push (sempre '1')
 * - eventId: identificador único do evento para deduplicação local
 * - type: tipo do evento
 * - recipientSocialId: destinatário pretendido (usado para isolamento de conta)
 * - entityId: id da entidade relacionada (requestId, challengeId, etc.)
 */
data class PushDataPayload(
    val v: String,
    val eventId: String,
    val type: String,
    val recipientSocialId: String,
    val entityId: String
) {
    companion object {
        fun fromMap(data: Map<String, String>): PushDataPayload? {
            val v = data["v"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (v != "1") return null

            val eventId = data["eventId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            val typeStr = data["type"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val type = com.example.domain.social.SocialNotificationType.fromStringOrNull(typeStr) ?: return null

            val recipientSocialId = data["recipientSocialId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            val entityId = data["entityId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            return PushDataPayload(
                v = v,
                eventId = eventId,
                type = type.name,
                recipientSocialId = recipientSocialId,
                entityId = entityId
            )
        }
    }
}
