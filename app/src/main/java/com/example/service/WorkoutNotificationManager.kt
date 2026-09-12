package com.example.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class WorkoutNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val channelId = "workout_channel"

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

        // Alarme exato quando o sistema permite; inexato quando não (auditoria 2026-09-12).
        //
        // O app declarava `SCHEDULE_EXACT_ALARM` **e** `USE_EXACT_ALARM`. A segunda saiu: o Play a
        // restringe a despertadores, temporizadores e calendários como função central, e um app de
        // treino que a declara é risco de recusa na revisão. Só que sem ela o alarme exato deixa de
        // ser automático — a partir do Android 14, `SCHEDULE_EXACT_ALARM` nasce **negada** para quem
        // mira 33+ e precisa ser concedida pelo usuário nas configurações do sistema. O app agora
        // oferece esse caminho na tela de Configurações (ver [canScheduleExactAlarms] e
        // [exactAlarmSettingsIntent]).
        //
        // Enquanto a permissão não é concedida, o alarme é inexato. Para um descanso isso é menos
        // grave do que parece: o aparelho está em uso durante o treino, fora do Doze, e a
        // contagem na tela e a notificação derivam do mesmo alvo persistido — o alarme cobre só o
        // caso de tela apagada. `setAlarmClock` não seria saída: ele exige a mesma permissão desde
        // o Android 12 (o lint acusa `MissingPermission`), e ainda pintaria um ícone de despertador
        // na barra de status a cada descanso.
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTimeMs, alertPendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTimeMs, alertPendingIntent)
            }
        } catch (e: SecurityException) {
            // A permissão pode ser revogada entre a checagem e o agendamento. Perder o alarme não
            // pode derrubar o treino; o que não pode é a perda ser silenciosa.
            Log.w(TAG, "alarme exato recusado pelo sistema; agendando inexato: ${e.javaClass.simpleName}")
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTimeMs, alertPendingIntent)
            }.onFailure { Log.e(TAG, "alarme de fim de descanso não pôde ser agendado", it) }
        }
    }

    /**
     * O sistema deixa este app agendar alarmes exatos.
     *
     * Antes do Android 12 não existe a permissão e a resposta é sempre sim. A partir do Android 14,
     * para quem mira 33+, a resposta nasce **não** até o usuário conceder em Configurações — é o
     * que [exactAlarmSettingsIntent] abre.
     */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * A tela do sistema onde o usuário concede alarmes exatos a este app, ou `null` onde ela não
     * existe (Android 11 e anteriores, onde a permissão também não existe).
     */
    fun exactAlarmSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
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
        private const val TAG = "WorkoutNotification"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = RestTimerAlertAuthority.ALERT_NOTIFICATION_ID
    }
}
