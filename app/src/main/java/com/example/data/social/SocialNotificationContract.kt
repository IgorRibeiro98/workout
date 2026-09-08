package com.example.data.social

/**
 * Rotas e constantes do protocolo de notificações sociais (T17.5).
 */
object SocialNotificationContract {
    const val DEVICES_PATH = "v1/social/notifications/devices"
    const val PREFERENCES_PATH = "v1/social/notifications/preferences"

    fun devicePath(deviceId: String): String = "$DEVICES_PATH/$deviceId"
}
