package com.example.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class WorkoutNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val channelId = "workout_channel"
    private val alertChannelId = "workout_alert_channel_v2"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Treino e Temporizador de Descanso",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificações de treinos ativos e contagem regressiva de descanso"
            }
            notificationManager.createNotificationChannel(channel)
            
            val alertChannel = NotificationChannel(
                alertChannelId,
                "Alertas de Fim de Descanso",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificação visual quando o descanso acaba"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    fun showTimerNotification(exerciseName: String, targetTimeMs: Long) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val add30sIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_ADD_30S
            putExtra("exerciseName", exerciseName)
        }
        val add30sPendingIntent = PendingIntent.getBroadcast(
            context, 1, add30sIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val skipIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_SKIP
        }

        val skipPendingIntent = PendingIntent.getBroadcast(
            context, 2, skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Descanso: $exerciseName")
            .setContentText("Tempo de descanso rolando...")
            .setContentIntent(pendingIntent)
            .setUsesChronometer(true)
            .setWhen(targetTimeMs)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "+30s", add30sPendingIntent)
            .addAction(0, "Pular", skipPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Schedule exact alarm for the final alert
        val alertIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_TIMER_FINISHED
            putExtra("exerciseName", exerciseName)
        }
        val alertPendingIntent = PendingIntent.getBroadcast(
            context, 3, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Explicitly cancel previous alarm before re-scheduling
        try {
            alarmManager.cancel(alertPendingIntent)
        } catch (_: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTimeMs, alertPendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTimeMs, alertPendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTimeMs, alertPendingIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
        
        val alertIntent = Intent(context, RestNotificationReceiver::class.java).apply {
            action = RestNotificationReceiver.ACTION_TIMER_FINISHED
        }
        val alertPendingIntent = PendingIntent.getBroadcast(
            context, 3, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.cancel(alertPendingIntent)
        } catch (_: Exception) {}
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002
    }
}
