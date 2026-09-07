package com.example.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainApplication
import com.example.data.sync.SyncOutcome
import com.example.data.sync.SyncScheduler
import java.util.concurrent.TimeUnit

/**
 * O agendamento do sync incremental em background (T16.6).
 *
 * ```text
 * alteração local entra na Outbox
 *      ↓
 * scheduleSoon()  →  WorkManager: trabalho ÚNICO, com rede, com backoff
 *      ↓
 * SparkSyncWorker →  SyncCoordinator.runOnce()
 * ```
 *
 * ## O que ele deliberadamente não é
 *
 * - **não é periódico.** Não existe `PeriodicWorkRequest`, `AlarmManager`, `setRepeating` nem
 *   timer. O Spark não precisa de tempo real, e um trabalho que roda sozinho a cada X minutos
 *   gastaria bateria e banda para descobrir, quase sempre, que nada mudou;
 * - **não é polling.** Nada aqui consulta o servidor em laço. O gatilho é uma alteração local, o
 *   app voltando ao primeiro plano, ou o toque do usuário;
 * - **não é obrigatório.** Se o trabalho nunca rodar, o Spark continua completo: o Room é a
 *   autoridade local, e a fila é durável.
 *
 * ## Deduplicação
 *
 * `enqueueUniqueWork` com [ExistingWorkPolicy.KEEP]: vinte alterações seguidas agendam **um**
 * trabalho, não vinte. E o [com.example.data.sync.SyncCoordinator] recusa um segundo ciclo
 * simultâneo mesmo que dois gatilhos coincidam.
 */
class SparkSyncWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? MainApplication ?: return Result.success()
        val coordinator = application.syncCoordinator

        return when (val outcome = coordinator.runOnce()) {
            // Transitório: vale tentar de novo, com o backoff do WorkManager.
            SyncOutcome.Offline,
            SyncOutcome.Unavailable,
            SyncOutcome.RateLimited -> Result.retry()

            // Definitivo para este trabalho. `STALE`, `INVALID` e afins **não** são retentados:
            // reenviar o mesmo produziria a mesma recusa, e a resolução é da T16.7.
            is SyncOutcome.Success,
            SyncOutcome.AlreadyRunning,
            SyncOutcome.NotConfigured,
            SyncOutcome.NotEnabled,
            SyncOutcome.AuthRequired,
            is SyncOutcome.AccountMismatch,
            is SyncOutcome.Rejected -> {
                // O worker não interpreta domínio: ele só sabe se vale insistir.
                @Suppress("UNUSED_EXPRESSION") outcome
                Result.success()
            }
        }
    }

    companion object {
        /** O nome do trabalho único. Um só, para o app inteiro. */
        const val UNIQUE_WORK_NAME: String = "spark-sync"
    }
}

/**
 * A implementação real de [SyncScheduler].
 *
 * Vive aqui, e não em `data/sync`, porque é ela que conhece `Context` e `WorkManager`. O
 * coordenador continua falando com a interface — e continua testável sem subir o WorkManager.
 */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {

    override fun scheduleSoon() {
        val request = OneTimeWorkRequestBuilder<SparkSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    // Sem rede não há o que fazer, e acordar para descobrir isso é desperdício.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // Um respiro antes de subir: editar cinco campos seguidos vira um envio, não cinco.
            .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            // Exponencial: um servidor fora do ar não é martelado enquanto volta.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SparkSyncWorker.UNIQUE_WORK_NAME,
            // `KEEP`: se já há um agendado, ele basta. `REPLACE` empurraria o envio para frente a
            // cada alteração nova, e uma edição contínua adiaria o sync indefinidamente.
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val INITIAL_DELAY_SECONDS = 30L
        const val BACKOFF_MINUTES = 5L
    }
}
