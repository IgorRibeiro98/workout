package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.MainActivity

/**
 * Definição dos canais de notificação social e construtores de navegação (T17.5 §101–§110).
 */
object SocialNotificationChannels {

    const val CHANNEL_SOCIAL_REQUESTS = "social_requests"
    const val CHANNEL_SOCIAL_CHALLENGES = "social_challenges"

    const val EXTRA_DESTINATION = "social_nav_destination"
    const val EXTRA_ENTITY_ID = "social_nav_entity_id"

    const val DESTINATION_FRIEND_REQUESTS = "friend_requests"
    const val DESTINATION_FRIENDS = "friends"
    const val DESTINATION_CHALLENGES = "challenges"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val requestsChannel = NotificationChannel(
                CHANNEL_SOCIAL_REQUESTS,
                "Solicitações de Amizade",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificações de novas solicitações e amizades aceitas no Spark"
            }

            val challengesChannel = NotificationChannel(
                CHANNEL_SOCIAL_CHALLENGES,
                "Desafios Sociais",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Convites e atualizações de desafios entre amigos"
            }

            manager.createNotificationChannel(requestsChannel)
            manager.createNotificationChannel(challengesChannel)
        }
    }

    fun buildPendingIntent(
        context: Context,
        destination: String,
        entityId: String? = null,
        requestCode: Int = destination.hashCode()
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DESTINATION, destination)
            if (!entityId.isNullOrBlank()) {
                putExtra(EXTRA_ENTITY_ID, entityId)
            }
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }
}
