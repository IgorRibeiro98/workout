package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MainApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MainApplication ?: return
        val workoutEngine = app.workoutEngine

        when (intent.action) {
            ACTION_ADD_30S -> {
                CoroutineScope(Dispatchers.IO).launch {
                    workoutEngine.adjustRestTimer(30)
                }
            }
            ACTION_SKIP -> {
                CoroutineScope(Dispatchers.IO).launch {
                    workoutEngine.skipRestTimer()
                }
            }
        }
    }

    companion object {
        const val ACTION_ADD_30S = "com.example.ACTION_ADD_30S"
        const val ACTION_SKIP = "com.example.ACTION_SKIP"
    }
}
