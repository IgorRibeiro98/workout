package com.example.service

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainApplication
import com.example.R
import com.example.data.social.PushDataPayload
import com.example.domain.social.SocialNotificationType
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receptor de mensagens e tokens do Firebase Cloud Messaging (T17.5 §13–§20, §85–§94).
 *
 * Princípios invioláveis do Spark:
 * 1. Mensagens são 100% "data-only": não há campo `notification` no payload FCM.
 * 2. O conteúdo é mínimo e opaco: apenas tipo, recipientSocialId e entityId (sem nomes, sem UIDs, sem emails).
 * 3. Isolamento estrito de conta: se o aparelho estiver registrado para outra conta, descarta silenciosamente.
 * 4. Deduplicação local: mensagens repetidas pelo FCM não produzem notificações duplicadas na bandeja.
 */
class SparkFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "SparkFCM"
        private const val DEDUPE_CACHE_CAPACITY = 100

        // Cache LRU thread-safe para deduplicação dos últimos 100 eventIds
        private val processedEventIds: MutableSet<String> = Collections.synchronizedSet(
            object : LinkedHashMap<String, Boolean>(DEDUPE_CACHE_CAPACITY, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > DEDUPE_CACHE_CAPACITY
                }
            }.let { Collections.newSetFromMap(it) }
        )

        fun isEventProcessed(eventId: String): Boolean {
            return processedEventIds.contains(eventId)
        }

        fun markEventProcessed(eventId: String) {
            processedEventIds.add(eventId)
        }

        fun clearDedupeCache() {
            processedEventIds.clear()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Novo token FCM emitido")

        val app = applicationContext as? MainApplication ?: return
        serviceScope.launch {
            app.pushAccountScope.setFcmToken(token)
            PushTokenRegistrationWorker.schedule(applicationContext)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.d(TAG, "Mensagem FCM vazia descartada")
            return
        }

        val payload = PushDataPayload.fromMap(data)
        if (payload == null) {
            Log.w(TAG, "Payload FCM com formato ou versão inválida descartado")
            return
        }

        val app = applicationContext as? MainApplication ?: return

        serviceScope.launch {
            // 1. Isolamento de Conta (T17.5 §87)
            val registeredSocialId = app.pushAccountScope.getRegisteredSocialId()
            if (registeredSocialId.isNullOrBlank() || registeredSocialId != payload.recipientSocialId) {
                Log.d(TAG, "Push ignorado por não pertencer à conta ativa no aparelho")
                return@launch
            }

            // 2. Deduplicação Local (T17.5 §90)
            if (isEventProcessed(payload.eventId)) {
                Log.d(TAG, "Push duplicado ignorado: ${payload.eventId}")
                return@launch
            }
            markEventProcessed(payload.eventId)

            // 3. Exibição da notificação local no Android
            displayLocalNotification(payload)
        }
    }

    private fun displayLocalNotification(payload: PushDataPayload) {
        val type = SocialNotificationType.fromStringOrNull(payload.type) ?: run {
            Log.w(TAG, "Tipo desconhecido de notificação: ${payload.type}")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Permissão POST_NOTIFICATIONS não concedida")
            return
        }

        val (channelId, title, body) = when (type) {
            SocialNotificationType.FRIEND_REQUEST_RECEIVED -> Triple(
                SocialNotificationChannels.CHANNEL_SOCIAL_REQUESTS,
                getString(R.string.notification_friend_request_received_title),
                getString(R.string.notification_friend_request_received_body)
            )
            SocialNotificationType.FRIEND_REQUEST_ACCEPTED -> Triple(
                SocialNotificationChannels.CHANNEL_SOCIAL_REQUESTS,
                getString(R.string.notification_friend_request_accepted_title),
                getString(R.string.notification_friend_request_accepted_body)
            )
            SocialNotificationType.CHALLENGE_INVITATION_RECEIVED -> Triple(
                SocialNotificationChannels.CHANNEL_SOCIAL_CHALLENGES,
                getString(R.string.notification_challenge_invitation_received_title),
                getString(R.string.notification_challenge_invitation_received_body)
            )
            SocialNotificationType.CHALLENGE_STARTING_SOON -> Triple(
                SocialNotificationChannels.CHANNEL_SOCIAL_CHALLENGES,
                getString(R.string.notification_challenge_starting_soon_title),
                getString(R.string.notification_challenge_starting_soon_body)
            )
            SocialNotificationType.CHALLENGE_ENDED -> Triple(
                SocialNotificationChannels.CHANNEL_SOCIAL_CHALLENGES,
                getString(R.string.notification_challenge_ended_title),
                getString(R.string.notification_challenge_ended_body)
            )
        }

        val pendingIntent = SocialNotificationChannels.buildPendingIntent(
            context = this,
            eventId = payload.eventId,
            type = payload.type,
            entityId = payload.entityId
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationId = payload.eventId.hashCode()

        runCatching {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
        }.onFailure { e ->
            Log.w(TAG, "Falha ao emitir notificação local", e)
        }
    }
}
