package com.example.presentation

import com.example.data.sync.SyncActivity
import com.example.data.sync.SyncApplyStop
import com.example.data.sync.SyncOutcome
import com.example.domain.social.SocialSyncResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Sincronizar dados" da tela de compartilhamento de progresso (T19.H5), sobre a infraestrutura da
 * T16 — e só dela.
 *
 * ```text
 * Compartilhar progresso ─▶ SocialProfileViewModel ─▶ (esta ponte) ─▶ SyncCoordinator.runOnce()
 *                                                                          │
 *                                                            SyncRepository ─▶ Room + Spark Backend
 * ```
 *
 * Mora **fora** do pacote social de propósito: o Social não conhece Outbox, cursor nem desfecho de
 * sync (`SocialBoundaryInspectionTest`). A ViewModel recebe uma função que devolve
 * [SocialSyncResult]; esta é a tradução, montada em `MainViewModelFactory` como os parâmetros de
 * consistência da T19.2.
 *
 * Não existe "sync social": é o mesmo ciclo do "Sincronizar agora" do Perfil, com a mesma trava —
 * dois toques produzem um ciclo, e um backup ou restore em andamento recusa o ciclo.
 *
 * ## Um ciclo que já estava rodando
 *
 * O app volta ao primeiro plano e o coordenador já começou um ciclo; a pessoa abre a tela e toca em
 * "Sincronizar dados". `runOnce` responde `AlreadyRunning` — e o desfecho que interessa é o do
 * ciclo em andamento, não "tente de novo". A ponte espera o próximo `Finished` (posterior ao pedido)
 * por até [waitForRunningMs], sem abrir um segundo ciclo. Se ele não vier, a tela diz que há um em
 * andamento.
 */
suspend fun runSocialAssistedSync(
    runOnce: suspend () -> SyncOutcome,
    activity: StateFlow<SyncActivity>,
    clock: () -> Long = { System.currentTimeMillis() },
    waitForRunningMs: Long = RUNNING_CYCLE_WAIT_MS
): SocialSyncResult {
    val requestedAt = clock()
    val outcome = runOnce()
    if (outcome !is SyncOutcome.AlreadyRunning) return outcome.toSocialSyncResult()

    val finished = withTimeoutOrNull(waitForRunningMs) {
        activity.first { it is SyncActivity.Finished && it.at >= requestedAt } as SyncActivity.Finished
    }
    return finished?.outcome?.toSocialSyncResult() ?: SocialSyncResult.ALREADY_RUNNING
}

/**
 * A tradução do desfecho da T16 para o vocabulário do Social.
 *
 * Um ciclo que rodou mas deixou algo para trás — conflito a decidir, mudança que este app não sabe
 * ler, cursor expirado — é [SocialSyncResult.NEEDS_ATTENTION]: parte dos treinos pode não ter
 * chegado, e o lugar de resolver é a seção Sincronização do Perfil, não esta tela.
 */
internal fun SyncOutcome.toSocialSyncResult(): SocialSyncResult = when (this) {
    is SyncOutcome.Success -> when {
        conflicts > 0 -> SocialSyncResult.NEEDS_ATTENTION
        pausedAt is SyncApplyStop.Unsupported || pausedAt is SyncApplyStop.Failed ->
            SocialSyncResult.NEEDS_ATTENTION
        // Adiado por treino em execução não é problema: o ciclo seguinte aplica.
        else -> SocialSyncResult.SYNCED
    }
    SyncOutcome.NotEnabled -> SocialSyncResult.NOT_ENABLED
    SyncOutcome.AuthRequired -> SocialSyncResult.AUTH_REQUIRED
    is SyncOutcome.AccountMismatch -> SocialSyncResult.ACCOUNT_MISMATCH
    SyncOutcome.Offline -> SocialSyncResult.OFFLINE
    is SyncOutcome.Rejected ->
        if (code == CURSOR_EXPIRED) SocialSyncResult.NEEDS_ATTENTION else SocialSyncResult.FAILED
    SyncOutcome.Unavailable,
    SyncOutcome.RateLimited,
    SyncOutcome.NotConfigured -> SocialSyncResult.FAILED
    SyncOutcome.AlreadyRunning -> SocialSyncResult.ALREADY_RUNNING
}

/**
 * Quanto a tela espera por um ciclo que já estava rodando. Um minuto: mais que isso, o ciclo está
 * preso em rede lenta, e dizer "há uma sincronização em andamento" é mais honesto que um spinner.
 */
private const val RUNNING_CYCLE_WAIT_MS = 60_000L

/** O cursor deste aparelho ficou para trás do que o servidor guarda (T16.7) — ver `SyncViewModel`. */
private const val CURSOR_EXPIRED = "CURSOR_EXPIRED"
