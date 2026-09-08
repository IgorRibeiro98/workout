package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainApplication
import com.example.domain.social.SocialOutcome
import java.util.concurrent.TimeUnit

/**
 * Worker responsável pelo registro atômico do token FCM no backend (T17.5 §13–§20).
 *
 * Disparado quando:
 * - O FirebaseMessagingService recebe um novo token (`onNewToken`);
 * - O usuário ativa o Social no app;
 * - O usuário faz login em uma nova conta com Social ativo.
 */
class PushTokenRegistrationWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    companion object {
        private const val TAG = "PushRegistrationWorker"
        const val UNIQUE_WORK_NAME = "spark_push_token_registration"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PushTokenRegistrationWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val application = applicationContext as? MainApplication ?: return Result.success()

        // 1. Se backend não estiver configurado -> não registrar (T17.5.1 Problema 1)
        if (application.sparkBackendClient == null) {
            Log.d(TAG, "Backend não configurado. Registro push suprimido.")
            return Result.success()
        }

        // 2. Se não houver conta autenticada -> não registrar
        val signedIn = application.authGateway.state.value as? com.example.domain.auth.AuthState.SignedIn
        if (signedIn == null) {
            Log.d(TAG, "Nenhuma conta autenticada. Registro push suprimido.")
            return Result.success()
        }
        val currentUid = signedIn.account.uid

        // 3. Se Social não estiver ativo -> não registrar
        val socialGateway = application.socialGateway
        val profileOutcome = socialGateway.profile()
        val profile = when (profileOutcome) {
            is SocialOutcome.Success -> profileOutcome.profile
            SocialOutcome.NotEnabled -> {
                Log.d(TAG, "Social não ativado para a conta atual. Registro suprimido.")
                return Result.success()
            }
            is SocialOutcome.Failure -> {
                Log.d(TAG, "Falha ao obter perfil social para registro push")
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }

        // 4. Se pushEnabled == false -> não registrar
        val notificationGateway = application.socialNotificationGateway
        val prefsResult = notificationGateway.getPreferences()
        val prefs = prefsResult.getOrNull()
        if (prefsResult.isFailure) {
            Log.w(TAG, "Falha ao consultar preferências de notificação para registro push")
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        if (prefs == null || !prefs.pushEnabled) {
            Log.d(TAG, "Notificações push desabilitadas nas preferências (pushEnabled=false). Registro suprimido.")
            return Result.success()
        }

        // 5. Obtenção do token FCM atual (cache ou FcmTokenProvider)
        val pushScope = application.pushAccountScope
        var token = pushScope.getFcmToken()
        if (token.isNullOrBlank()) {
            token = application.fcmTokenProvider.getToken()
            if (!token.isNullOrBlank()) {
                pushScope.setFcmToken(token)
            }
        }

        if (token.isNullOrBlank()) {
            Log.w(TAG, "Nenhum token FCM disponível para registro")
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        // 6. Registro idempotente do aparelho
        val deviceId = application.deviceIdProvider.deviceId()
        val registerResult = notificationGateway.registerDevice(deviceId, token)

        return if (registerResult.isSuccess) {
            // Revalida se o usuário ainda é o mesmo após a resposta de rede
            val finalSignedIn = application.authGateway.state.value as? com.example.domain.auth.AuthState.SignedIn
            if (finalSignedIn?.account?.uid == currentUid) {
                pushScope.setRegisteredAccount(profile.socialId, System.currentTimeMillis())
                Log.d(TAG, "Aparelho registrado com sucesso para push")
            }
            Result.success()
        } else {
            Log.w(TAG, "Falha ao registrar aparelho no backend: ${registerResult.exceptionOrNull()?.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
