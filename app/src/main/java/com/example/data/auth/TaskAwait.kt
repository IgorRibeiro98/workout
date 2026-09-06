package com.example.data.auth

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Espera uma `Task` do Google Play Services dentro de uma corrotina.
 *
 * Existe para não trazer `kotlinx-coroutines-play-services` só por uma função: são as duas únicas
 * `Task` que o Spark usa (`signInWithCredential` e `getIdToken`), e a conversão é a mesma que
 * aquela biblioteca faz.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            task.isCanceled -> continuation.cancel()
            error != null -> continuation.resumeWithException(error)
            else -> @Suppress("UNCHECKED_CAST") continuation.resume(task.result as T)
        }
    }
}
