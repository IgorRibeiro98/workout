package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class RestNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MainApplication ?: return
        val workoutEngine = app.workoutEngine
        val settingsManager = app.settingsManager
        val notificationManager = WorkoutNotificationManager(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_ADD_30S -> {
                        val target = workoutEngine.adjustRestTimer(30)
                        val exName = intent.getStringExtra("exerciseName")
                            ?: workoutEngine.getActiveExerciseNameForTimer()
                            ?: "Exercício"
                        notificationManager.showTimerNotification(exName, target)
                    }

                    ACTION_SKIP -> {
                        workoutEngine.skipRestTimer()
                        notificationManager.cancelNotification()
                    }

                    ACTION_TIMER_FINISHED -> {
                        val soundEnabled = settingsManager.soundEnabledFlow.firstOrNull() ?: true
                        val hapticEnabled = settingsManager.hapticEnabledFlow.firstOrNull() ?: true
                        val exName = intent.getStringExtra("exerciseName")
                            ?: workoutEngine.getActiveExerciseNameForTimer()

                        // 1. Clear timer state from WorkoutEngine and DataStore
                        workoutEngine.skipRestTimer()
                        // 2. Cancel the ongoing countdown notification & alarm
                        notificationManager.cancelNotification()
                        // 3. Emit completion notification with sound and vibration
                        notificationManager.showTimerFinishedAlert(
                            exerciseName = exName,
                            soundEnabled = soundEnabled,
                            hapticEnabled = hapticEnabled
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ADD_30S = "com.example.ACTION_ADD_30S"
        const val ACTION_SKIP = "com.example.ACTION_SKIP"
        const val ACTION_TIMER_FINISHED = "com.example.ACTION_TIMER_FINISHED"
    }
}

