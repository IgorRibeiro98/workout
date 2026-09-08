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
        val pushScope = application.pushAccountScope
        val socialGateway = application.socialGateway
        val notificationGateway = application.socialNotificationGateway
        val deviceIdProvider = application.deviceIdProvider

        val token = pushScope.getFcmToken()
        if (token.isNullOrBlank()) {
            Log.d(TAG, "Nenhum token FCM disponível para registro")
            return Result.success()
        }

        // Verifica se a conta está ativa no Social
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

        val deviceId = deviceIdProvider.deviceId()
        val registerResult = notificationGateway.registerDevice(deviceId, token)

        return if (registerResult.isSuccess) {
            pushScope.setRegisteredAccount(profile.socialId, System.currentTimeMillis())
            Log.d(TAG, "Aparelho registrado com sucesso para push")
            Result.success()
        } else {
            Log.w(TAG, "Falha ao registrar aparelho no backend: ${registerResult.exceptionOrNull()?.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
