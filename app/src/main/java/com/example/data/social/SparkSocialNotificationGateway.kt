package com.example.data.social

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import com.example.domain.social.SocialNotificationGateway
import com.example.domain.social.SocialNotificationPreferences
import kotlinx.serialization.json.Json

class SparkSocialNotificationGateway(
    private val client: SparkBackendClient?
) : SocialNotificationGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    override val isConfigured: Boolean
        get() = client?.isConfigured == true

    override suspend fun registerDevice(
        deviceId: String,
        fcmToken: String
    ): Result<PushDeviceRegistrationDto> {
        val backend = client ?: return Result.failure(IllegalStateException("Backend não configurado"))
        val requestBody = json.encodeToString(
            RegisterPushDeviceRequestDto(deviceId = deviceId, platform = "ANDROID", fcmToken = fcmToken)
        )

        return when (val outcome = backend.postJson(SocialNotificationContract.DEVICES_PATH, requestBody)) {
            is SparkHttpOutcome.Response -> {
                if (outcome.code == 201) {
                    runCatching { json.decodeFromString<PushDeviceRegistrationDto>(outcome.body) }
                } else {
                    Result.failure(IllegalStateException("Falha ao registrar aparelho: HTTP ${outcome.code}"))
                }
            }
            SparkHttpOutcome.NetworkFailure -> Result.failure(IllegalStateException("Falha de rede"))
            SparkHttpOutcome.NotConfigured -> Result.failure(IllegalStateException("Backend não configurado"))
            SparkHttpOutcome.SignedOut -> Result.failure(IllegalStateException("Autenticação necessária"))
        }
    }

    override suspend fun unregisterDevice(deviceId: String): Result<Unit> {
        val backend = client ?: return Result.failure(IllegalStateException("Backend não configurado"))
        val path = SocialNotificationContract.devicePath(deviceId)

        return when (val outcome = backend.delete(path)) {
            is SparkHttpOutcome.Response -> {
                if (outcome.code == 204 || outcome.code == 404) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Falha ao desregistrar aparelho: HTTP ${outcome.code}"))
                }
            }
            SparkHttpOutcome.NetworkFailure -> Result.failure(IllegalStateException("Falha de rede"))
            SparkHttpOutcome.NotConfigured -> Result.failure(IllegalStateException("Backend não configurado"))
            SparkHttpOutcome.SignedOut -> Result.failure(IllegalStateException("Autenticação necessária"))
        }
    }

    override suspend fun getPreferences(): Result<SocialNotificationPreferences> {
        val backend = client ?: return Result.failure(IllegalStateException("Backend não configurado"))

        return when (val outcome = backend.getJson(SocialNotificationContract.PREFERENCES_PATH)) {
            is SparkHttpOutcome.Response -> {
                if (outcome.code == 200) {
                    runCatching {
                        val dto = json.decodeFromString<NotificationPreferencesDto>(outcome.body)
                        dto.toDomain()
                    }
                } else {
                    Result.failure(IllegalStateException("Falha ao ler preferências: HTTP ${outcome.code}"))
                }
            }
            SparkHttpOutcome.NetworkFailure -> Result.failure(IllegalStateException("Falha de rede"))
            SparkHttpOutcome.NotConfigured -> Result.failure(IllegalStateException("Backend não configurado"))
            SparkHttpOutcome.SignedOut -> Result.failure(IllegalStateException("Autenticação necessária"))
        }
    }

    override suspend fun updatePreferences(
        pushEnabled: Boolean?,
        friendRequestReceived: Boolean?,
        friendRequestAccepted: Boolean?,
        challengeInvitationReceived: Boolean?,
        challengeStartingSoon: Boolean?,
        challengeEnded: Boolean?,
        workoutShareReceived: Boolean?
    ): Result<SocialNotificationPreferences> {
        val backend = client ?: return Result.failure(IllegalStateException("Backend não configurado"))
        val requestBody = json.encodeToString(
            UpdateNotificationPreferencesRequestDto(
                pushEnabled = pushEnabled,
                friendRequestReceived = friendRequestReceived,
                friendRequestAccepted = friendRequestAccepted,
                challengeInvitationReceived = challengeInvitationReceived,
                challengeStartingSoon = challengeStartingSoon,
                challengeEnded = challengeEnded,
                workoutShareReceived = workoutShareReceived
            )
        )

        return when (val outcome = backend.patchJson(SocialNotificationContract.PREFERENCES_PATH, requestBody)) {
            is SparkHttpOutcome.Response -> {
                if (outcome.code == 200) {
                    runCatching {
                        val dto = json.decodeFromString<NotificationPreferencesDto>(outcome.body)
                        dto.toDomain()
                    }
                } else {
                    Result.failure(IllegalStateException("Falha ao atualizar preferências: HTTP ${outcome.code}"))
                }
            }
            SparkHttpOutcome.NetworkFailure -> Result.failure(IllegalStateException("Falha de rede"))
            SparkHttpOutcome.NotConfigured -> Result.failure(IllegalStateException("Backend não configurado"))
            SparkHttpOutcome.SignedOut -> Result.failure(IllegalStateException("Autenticação necessária"))
        }
    }

    private fun NotificationPreferencesDto.toDomain() = SocialNotificationPreferences(
        pushEnabled = pushEnabled,
        friendRequestReceived = friendRequestReceived,
        friendRequestAccepted = friendRequestAccepted,
        challengeInvitationReceived = challengeInvitationReceived,
        challengeStartingSoon = challengeStartingSoon,
        challengeEnded = challengeEnded,
        workoutShareReceived = workoutShareReceived
    )
}
