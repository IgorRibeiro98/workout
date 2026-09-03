package com.example.service

import android.content.Context

/**
 * Thin entry point for rest-timer completion alerts raised outside of Compose.
 *
 * All behaviour lives in [RestTimerAlertAuthority], which is the single place that decides whether
 * an alert fires and owns sound, vibration and the notification. Keeping this class free of its
 * own channel, playback and debounce logic is what guarantees the alert cannot fire twice or with
 * settings that contradict the ones the user chose.
 */
class RestTimerNotificationManager(private val context: Context) {

    fun onTimerFinished(
        exerciseName: String? = null,
        soundEnabled: Boolean = true,
        hapticEnabled: Boolean = true,
        notificationEnabled: Boolean = true
    ) {
        RestTimerAlertAuthority.getInstance(context).notifyTimerFinished(
            exerciseName = exerciseName,
            soundEnabled = soundEnabled,
            hapticEnabled = hapticEnabled,
            notificationEnabled = notificationEnabled,
            source = "RestTimerNotificationManager"
        )
    }

    companion object {
        const val ALERT_NOTIFICATION_ID = RestTimerAlertAuthority.ALERT_NOTIFICATION_ID
    }
}
