package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainApplication
import com.example.data.datastore.SettingsManager
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class RestNotificationReceiver : BroadcastReceiver() {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MainApplication ?: return
        val workoutEngine = app.workoutEngine
        val settingsManager = app.settingsManager
        val notificationManager = app.notificationManager
        val pendingResult = goAsync()

        // `SupervisorJob` + teto de tempo (auditoria 2026-09-12).
        //
        // `goAsync()` dá ao receiver uma janela de ~10 s antes de o sistema considerar o processo
        // ocioso e passível de morte. Um DataStore ou um Room que não responda deixaria
        // `pendingResult.finish()` sem ser chamado — e um `BroadcastReceiver` que não termina é
        // um ANR de broadcast. `withTimeoutOrNull` garante o `finish()`; o `SupervisorJob` impede
        // que uma falha aqui cancele o escopo de outro broadcast em andamento.
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            try {
                withTimeoutOrNull(GO_ASYNC_BUDGET_MS) {
                    handleIntent(intent, workoutEngine, settingsManager, notificationManager, context.applicationContext)
                } ?: android.util.Log.w(TAG, "ação ${intent.action} não terminou no orçamento do goAsync")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "falha ao tratar ${intent.action}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    suspend fun handleIntent(
        intent: Intent,
        workoutEngine: WorkoutEngine,
        settingsManager: SettingsManager,
        notificationManager: WorkoutNotificationManager,
        context: Context? = null
    ) {
        when (intent.action) {
            ACTION_ADD_30S -> {
                // Sem descanso em andamento não há o que estender: o botão veio de uma notificação
                // obsoleta. `adjustRestTimer` devolve [WorkoutEngine.NO_ACTIVE_REST_TIMER] nesse
                // caso, e remostrar a notificação com um alvo inventado era justamente o defeito
                // que a auditoria de 2026-09-12 encontrou.
                val target = workoutEngine.adjustRestTimer(30)
                if (target == WorkoutEngine.NO_ACTIVE_REST_TIMER) {
                    notificationManager.cancelNotification()
                } else {
                    // Quem observa `restTimerTarget` e remostra a notificação é o `MainApplication`,
                    // autoridade única desde a auditoria. Aqui basta mudar o alvo.
                    val exName = intent.getStringExtra("exerciseName")
                        ?: workoutEngine.getActiveExerciseNameForTimer()
                        ?: "Exercício"
                    notificationManager.showTimerNotification(exName, target)
                }
            }

            ACTION_SKIP -> {
                workoutEngine.skipRestTimer()
                notificationManager.cancelNotification()
            }

            ACTION_TIMER_FINISHED -> {
                val soundEnabled = settingsManager.soundEnabledFlow.firstOrNull() ?: true
                val hapticEnabled = settingsManager.hapticEnabledFlow.firstOrNull() ?: true
                val notificationEnabled = settingsManager.timerNotificationEnabledFlow.firstOrNull() ?: true
                val exName = intent.getStringExtra("exerciseName")
                    ?: workoutEngine.getActiveExerciseNameForTimer()

                // 1. Clear timer state from WorkoutEngine and DataStore
                workoutEngine.skipRestTimer()
                // 2. Cancel the ongoing countdown notification & alarm
                notificationManager.cancelNotification()
                // 3. Emit completion notification with sound and vibration via RestTimerNotificationManager
                context?.let { ctx ->
                    val restTimerNotifManager = RestTimerNotificationManager(ctx)
                    restTimerNotifManager.onTimerFinished(
                        exerciseName = exName,
                        soundEnabled = soundEnabled,
                        hapticEnabled = hapticEnabled,
                        notificationEnabled = notificationEnabled
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "RestNotifReceiver"

        /**
         * Orçamento de tempo do `goAsync()`.
         *
         * O sistema dá cerca de 10 s a um `BroadcastReceiver` assíncrono. 8 s deixa margem para o
         * `finish()` acontecer dentro da janela em vez de exatamente na borda dela.
         */
        private const val GO_ASYNC_BUDGET_MS = 8_000L

        const val ACTION_ADD_30S = "com.example.ACTION_ADD_30S"
        const val ACTION_SKIP = "com.example.ACTION_SKIP"
        const val ACTION_TIMER_FINISHED = "com.example.ACTION_TIMER_FINISHED"
    }
}


