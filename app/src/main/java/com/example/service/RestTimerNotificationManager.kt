package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class RestTimerNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alertChannelId = "workout_rest_alert_channel_v3"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alertChannel = NotificationChannel(
                alertChannelId,
                "Alertas de Fim de Descanso",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificação visual e sonora quando o tempo de descanso termina"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    @Synchronized
    fun onTimerFinished(
        exerciseName: String? = null,
        soundEnabled: Boolean = true,
        hapticEnabled: Boolean = true,
        notificationEnabled: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTimestampMs < MIN_ALERT_INTERVAL_MS) {
            // Debounce duplicate invocations from concurrent triggers
            return
        }
        lastAlertTimestampMs = now

        if (notificationEnabled) {
            showFinishedNotification(exerciseName)
        }
        if (soundEnabled) {
            playSoundAlert()
        }
        if (hapticEnabled) {
            triggerVibrationAlert()
        }
    }

    private fun showFinishedNotification(exerciseName: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (!exerciseName.isNullOrBlank()) "Descanso finalizado: $exerciseName" else "Descanso finalizado!"
        val notification = NotificationCompat.Builder(context, alertChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Hora de voltar ao treino.")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun playSoundAlert() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        } catch (_: Exception) {
            try {
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(context, alertUri)
                ringtone?.play()
            } catch (_: Exception) {}
        }
    }

    private fun triggerVibrationAlert() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 400, 200, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                val effect = VibrationEffect.createWaveform(pattern, -1)
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val ALERT_NOTIFICATION_ID = 1002
        @Volatile
        private var lastAlertTimestampMs: Long = 0L
        private const val MIN_ALERT_INTERVAL_MS = 3000L
    }
}
