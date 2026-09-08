package com.example.domain.social

import com.example.data.social.PushDeviceRegistrationDto

/**
 * Fronteira de domínio para notificações push e preferências no Spark Backend (T17.5).
 */
interface SocialNotificationGateway {

    val isConfigured: Boolean

    suspend fun registerDevice(
        deviceId: String,
        fcmToken: String
    ): Result<PushDeviceRegistrationDto>

    suspend fun unregisterDevice(
        deviceId: String
    ): Result<Unit>

    suspend fun getPreferences(): Result<SocialNotificationPreferences>

    suspend fun updatePreferences(
        pushEnabled: Boolean? = null,
        friendRequestReceived: Boolean? = null,
        friendRequestAccepted: Boolean? = null,
        challengeInvitationReceived: Boolean? = null,
        challengeStartingSoon: Boolean? = null,
        challengeEnded: Boolean? = null
    ): Result<SocialNotificationPreferences>
}
