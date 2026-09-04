package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 *
 * Sound, vibration and the notification are executed exactly once per timer expiration, no matter
 * how many sources report it (the in-app countdown and the AlarmManager broadcast both fire when
 * the app is in the foreground), and regardless of whether the app is in the foreground, in the
 * background, or the device is locked.
 */
class RestTimerAlertAuthority(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Playback is held in fields on purpose: a [Ringtone] or [ToneGenerator] kept only in a local
     * variable can be collected while the alert is still sounding, which is the usual cause of
     * "the timer finished but it didn't play".
     */
    private var activeRingtone: Ringtone? = null
    private var activeToneGenerator: ToneGenerator? = null

    init {
        createAlertNotificationChannel()
    }

    private fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Older channels enabled vibration and are immutable once created, which made the
            // notification vibrate even with haptics turned off. A new id adopts the new settings.
            LEGACY_CHANNEL_IDS.forEach { legacyId ->
                try {
                    notificationManager.deleteNotificationChannel(legacyId)
                } catch (_: Exception) {
                }
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Alertas de Fim de Descanso",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerta visual, sonoro e tátil ao finalizar o descanso"
                // Sound and vibration are driven explicitly below so that the user's own
                // preferences decide, and so that neither ever fires twice.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
        source: String = "UNKNOWN",
        title: String? = null,
        message: String? = null
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
            showNotification(exerciseName, title, message)
        }

        return true
    }

    private fun playSound() {
        if (playRingtone()) return
        playFallbackTone()
    }

    /**
     * Plays the user's alarm (or notification) tone on the alarm stream, which keeps sounding
     * with the screen locked and is not muted by the ringer's silent mode.
     *
     * @return true when playback actually started
     */
    private fun playRingtone(): Boolean {
        return try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return false

            val ringtone = RingtoneManager.getRingtone(context, alarmUri) ?: return false
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            stopActiveRingtone()
            activeRingtone = ringtone
            ringtone.play()

            // Rest alerts are a short cue, not an alarm the user has to dismiss.
            mainHandler.postDelayed({ stopActiveRingtone() }, RINGTONE_MAX_DURATION_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "RingtoneManager playback failed, trying ToneGenerator fallback", e)
            false
        }
    }

    private fun playFallbackTone() {
        try {
            releaseToneGenerator()
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
            activeToneGenerator = toneGen
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_DURATION_MS)
            // The generator must outlive startTone, which returns immediately.
            mainHandler.postDelayed({ releaseToneGenerator() }, TONE_DURATION_MS + 300L)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator fallback failed", e)
        }
    }

    @Synchronized
    private fun stopActiveRingtone() {
        try {
            activeRingtone?.takeIf { it.isPlaying }?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop ringtone", e)
        } finally {
            activeRingtone = null
        }
    }

    @Synchronized
    private fun releaseToneGenerator() {
        try {
            activeToneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release tone generator", e)
        } finally {
            activeToneGenerator = null
        }
    }

    private fun triggerVibration() {
        try {
            val vibrator = resolveVibrator()
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.d(TAG, "No vibrator available on this device")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_AMPLITUDES, -1)
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_PATTERN, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveVibrator(): Vibrator? {
        return try {
            val legacyVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator ?: legacyVibrator
            } else {
                legacyVibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve vibrator service", e)
            null
        }
    }

    private fun showNotification(exerciseName: String?, title: String? = null, message: String? = null) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val resolvedTitle = title
                ?: if (!exerciseName.isNullOrBlank()) "Descanso finalizado: $exerciseName" else "Descanso finalizado!"
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(resolvedTitle)
                .setContentText(message ?: "Hora de voltar ao treino.")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                // No setSound/setVibrate: both are driven explicitly above so that the sound and
                // haptic settings are honoured and neither fires a second time here.
                .build()

            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Notification display failed", e)
        }
    }

    companion object {
        const val ALERT_NOTIFICATION_ID = 1002
        const val ALERT_CHANNEL_ID = "workout_rest_alert_channel_v4"

        private val LEGACY_CHANNEL_IDS = listOf(
            "workout_rest_alert_channel_v3",
            "workout_alert_channel_v2"
        )

        private const val TAG = "RestTimerAlertAuthority"
        private const val MIN_DEBOUNCE_INTERVAL_MS = 3500L
        private const val RINGTONE_MAX_DURATION_MS = 4000L
        private const val TONE_DURATION_MS = 700
        private const val TONE_VOLUME = 100
        private val VIBRATION_PATTERN = longArrayOf(0, 350, 150, 350)
        private val VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255)
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
