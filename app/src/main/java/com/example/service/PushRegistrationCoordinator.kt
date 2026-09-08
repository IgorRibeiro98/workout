package com.example.service

import android.content.Context
import android.util.Log

/**
 * Coordenador do ciclo de reconciliação de registro push (T17.5.1 Problema 1).
 *
 * Garante que o aparelho seja registrado no backend quando os requisitos necessários
 * estiverem satisfeitos (conta autenticada, Social ativo, pushEnabled == true, backend configurado),
 * sem depender exclusivamente do disparo de `onNewToken()`.
 */
class PushRegistrationCoordinator(
    private val context: Context
) {
    companion object {
        private const val TAG = "PushRegCoordinator"
    }

    /**
     * Dispara uma reconciliação idempotente via WorkManager com política REPLACE.
     * @param reason Identificador do motivo para rastreabilidade em log técnico.
     */
    fun reconcile(reason: String) {
        Log.d(TAG, "Reconciliação push solicitada. Motivo: $reason")
        PushTokenRegistrationWorker.schedule(context)
    }
}
