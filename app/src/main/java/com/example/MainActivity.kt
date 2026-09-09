package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.example.presentation.MainScreen
import android.content.Intent
import com.example.service.SocialNotificationChannels
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationNavTarget(val destination: String, val entityId: String?)

class MainActivity : ComponentActivity() {

    companion object {
        private val _notificationNavTarget = MutableStateFlow<NotificationNavTarget?>(null)
        val notificationNavTarget = _notificationNavTarget.asStateFlow()

        fun clearNotificationNavTarget() {
            _notificationNavTarget.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return

        val typeStr = intent.getStringExtra(SocialNotificationChannels.EXTRA_NOTIFICATION_TYPE)
        val entityId = intent.getStringExtra(SocialNotificationChannels.EXTRA_ENTITY_ID)

        if (!typeStr.isNullOrBlank() && !entityId.isNullOrBlank()) {
            val type = com.example.domain.social.SocialNotificationType.fromStringOrNull(typeStr)
            if (type != null) {
                val destination = when (com.example.service.SocialNotificationNavigationResolver.resolve(type, entityId)) {
                    is com.example.service.NotificationNavDestination.FriendRequests -> SocialNotificationChannels.DESTINATION_FRIEND_REQUESTS
                    is com.example.service.NotificationNavDestination.Friends -> SocialNotificationChannels.DESTINATION_FRIENDS
                    is com.example.service.NotificationNavDestination.Challenges -> SocialNotificationChannels.DESTINATION_CHALLENGES
                    is com.example.service.NotificationNavDestination.ChallengeDetail -> SocialNotificationChannels.DESTINATION_CHALLENGE_DETAIL
                    is com.example.service.NotificationNavDestination.SharedWorkouts -> SocialNotificationChannels.DESTINATION_SHARED_WORKOUTS
                    is com.example.service.NotificationNavDestination.Squads -> SocialNotificationChannels.DESTINATION_SQUADS
                }
                _notificationNavTarget.value = NotificationNavTarget(destination, entityId)
                return
            }
        }

        val destination = intent.getStringExtra(SocialNotificationChannels.EXTRA_DESTINATION) ?: return
        _notificationNavTarget.value = NotificationNavTarget(destination, entityId)
    }

    /**
     * O app voltou para o primeiro plano (T16.6).
     *
     * Este é o gatilho conservador de sincronização: o coordenador decide se vale rodar — só roda
     * se houver alteração pendente ou se a última sincronização já estiver velha. Abrir o Spark
     * dez vezes em cinco minutos não produz dez ciclos.
     *
     * Ele **não bloqueia a primeira renderização**: a chamada apenas lança uma corrotina, e a UI
     * continua lendo o Room. Sem rede, sem conta ou sem vínculo, nada acontece e nada muda.
     */
    override fun onStart() {
        super.onStart()
        (application as? MainApplication)?.syncCoordinator?.onAppForeground()
    }
}
