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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.atomic.AtomicLong

/**
 * Single authority for rest timer completion alerts across the entire application.
 * Guarantees that sound, vibration, and notifications are executed exactly once per timer expiration,
 * regardless of whether the app is in the foreground, background, or device is locked.
 */
class RestTimerAlertAuthority(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alertChannelId = "workout_rest_alert_channel_v3"

    init {
        createAlertNotificationChannel()
    }

    private fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alertChannel = NotificationChannel(
                alertChannelId,
                "Alertas de Fim de Descanso",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerta visual, sonoro e tátil ao finalizar o descanso"
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
                setSound(null, null) // Sound is managed directly via Ringtone/ToneGenerator for reliability
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    /**
     * Executes the rest completion sequence atomically.
     * Prevents duplicate firing between concurrent UI countdowns and AlarmManager broadcasts.
     *
     * @return true if the alert was executed, false if dropped due to debouncing
     */
    @Synchronized
    fun notifyTimerFinished(
        exerciseName: String? = null,
        soundEnabled: Boolean = true,
        hapticEnabled: Boolean = true,
        notificationEnabled: Boolean = true,
        source: String = "UNKNOWN"
    ): Boolean {
        val now = System.currentTimeMillis()
        val lastTimestamp = lastAlertTimestampMs.get()
        if (now - lastTimestamp < MIN_DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Dropped duplicate timer finish from $source (elapsed: ${now - lastTimestamp}ms)")
            return false
        }
        lastAlertTimestampMs.set(now)
        Log.d(TAG, "Executing rest timer finished alert from $source (exercise: $exerciseName, sound: $soundEnabled, haptic: $hapticEnabled)")

        // 1. Play sound if enabled
        if (soundEnabled) {
            playSound()
        }

        // 2. Trigger vibration if enabled
        if (hapticEnabled) {
            triggerVibration()
        }

        // 3. Show notification if enabled
        if (notificationEnabled) {
            showNotification(exerciseName)
        }

        return true
    }

    private fun playSound() {
        try {
            // First attempt: RingtoneManager ALARM or NOTIFICATION for maximum compatibility and user sound preferences
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                ringtone.play()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "RingtoneManager playback failed, trying ToneGenerator fallback", e)
        }

        try {
            // Fallback: ToneGenerator
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator fallback failed", e)
        }
    }

    private fun triggerVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)
                vibrator?.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VIBRATION_PATTERN, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun showNotification(exerciseName: String?) {
        try {
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
                .setVibrate(VIBRATION_PATTERN)
                .build()

            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Notification display failed", e)
        }
    }

    companion object {
        const val ALERT_NOTIFICATION_ID = 1002
        private const val TAG = "RestTimerAlertAuthority"
        private const val MIN_DEBOUNCE_INTERVAL_MS = 3500L
        private val VIBRATION_PATTERN = longArrayOf(0, 350, 150, 350)
        private val lastAlertTimestampMs = AtomicLong(0L)

        @Volatile
        private var instance: RestTimerAlertAuthority? = null

        fun getInstance(context: Context): RestTimerAlertAuthority {
            return instance ?: synchronized(this) {
                instance ?: RestTimerAlertAuthority(context.applicationContext).also { instance = it }
            }
        }
    }
}
