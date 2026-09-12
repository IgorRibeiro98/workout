package com.example.service

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Provedor de token FCM para reconciliação de registro do aparelho (T17.5.1 Problema 1).
 *
 * Desacopla a obtenção do token atual do ciclo de vida exclusivo de `onNewToken()`,
 * permitindo que testes utilizem um provedor falso sem depender de Firebase real.
 */
interface FcmTokenProvider {
    suspend fun getToken(): String?
}

class DefaultFcmTokenProvider : FcmTokenProvider {
    override suspend fun getToken(): String? = runCatching {
        FirebaseMessaging.getInstance().token.await()
    }.getOrNull()
}
