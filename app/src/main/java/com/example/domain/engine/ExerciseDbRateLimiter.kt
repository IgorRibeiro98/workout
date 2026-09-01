package com.example.domain.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

class ExerciseDbRateLimiter(
    private val requestsPerSecond: Double = 2.0
) {
    private val mutex = Mutex()
    private val minIntervalMs = (1000.0 / requestsPerSecond).toLong() // 500ms
    private var lastRequestTime = 0L

    suspend fun acquire() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTime
            if (timeSinceLast < minIntervalMs) {
                val delayTime = minIntervalMs - timeSinceLast
                delay(delayTime)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

    suspend fun cooldown(seconds: Long) {
        mutex.withLock {
            Log.w("RateLimiter", "Rate limit (429) detectado. Aplicando cooldown de ${seconds}s...")
            delay(seconds * 1000)
            lastRequestTime = System.currentTimeMillis()
        }
    }
}
