package com.example.presentation

import com.example.data.sync.SyncActivity
import com.example.data.sync.SyncApplyStop
import com.example.data.sync.SyncOutcome
import com.example.domain.social.SocialSyncResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ponte entre "Sincronizar dados" (Social) e o ciclo da T16 (T19.H5).
 *
 * O que ela precisa garantir: nenhum desfecho da T16 vira "deu certo" por engano, e um ciclo que
 * já estava rodando é **esperado** — não duplicado, e não respondido como "tente de novo" quando o
 * resultado dele chega em instantes.
 */
class SocialAssistedSyncTest {

    @Test
    fun `cada desfecho da T16 vira a frase certa do Social`() {
        val cases = mapOf(
            SyncOutcome.Success(pushed = 3) to SocialSyncResult.SYNCED,
            // Adiado por treino em execução não é problema: o ciclo seguinte aplica.
            SyncOutcome.Success(pausedAt = SyncApplyStop.DeferredActiveWorkout(9)) to SocialSyncResult.SYNCED,
            SyncOutcome.Success(conflicts = 1) to SocialSyncResult.NEEDS_ATTENTION,
            SyncOutcome.Success(pausedAt = SyncApplyStop.Unsupported(9, "NOVO")) to
                SocialSyncResult.NEEDS_ATTENTION,
            SyncOutcome.Success(pausedAt = SyncApplyStop.Failed(9, "ref")) to SocialSyncResult.NEEDS_ATTENTION,
            SyncOutcome.NotEnabled to SocialSyncResult.NOT_ENABLED,
            SyncOutcome.AuthRequired to SocialSyncResult.AUTH_REQUIRED,
            SyncOutcome.AccountMismatch("outra") to SocialSyncResult.ACCOUNT_MISMATCH,
            SyncOutcome.Offline to SocialSyncResult.OFFLINE,
            SyncOutcome.Unavailable to SocialSyncResult.FAILED,
            SyncOutcome.RateLimited to SocialSyncResult.FAILED,
            SyncOutcome.NotConfigured to SocialSyncResult.FAILED,
            SyncOutcome.Rejected("INVALID") to SocialSyncResult.FAILED,
            SyncOutcome.Rejected("CURSOR_EXPIRED") to SocialSyncResult.NEEDS_ATTENTION,
            SyncOutcome.AlreadyRunning to SocialSyncResult.ALREADY_RUNNING
        )
        for ((outcome, expected) in cases) {
            assertEquals("$outcome", expected, outcome.toSocialSyncResult())
        }
    }

    @Test
    fun `um ciclo novo devolve o proprio desfecho, sem esperar nada`() = runBlocking {
        var calls = 0
        val result = runSocialAssistedSync(
            runOnce = {
                calls++
                SyncOutcome.Success(pushed = 1)
            },
            activity = MutableStateFlow(SyncActivity.Idle),
            clock = { NOW }
        )
        assertEquals(SocialSyncResult.SYNCED, result)
        assertEquals(1, calls)
    }

    @Test
    fun `ja havia um ciclo rodando — a tela espera o desfecho dele, sem abrir outro`() = runBlocking {
        val activity = MutableStateFlow<SyncActivity>(SyncActivity.Running)
        var calls = 0
        launch {
            delay(50)
            activity.value = SyncActivity.Finished(SyncOutcome.Success(pushed = 2), at = NOW + 1)
        }

        val result = runSocialAssistedSync(
            runOnce = {
                calls++
                SyncOutcome.AlreadyRunning
            },
            activity = activity,
            clock = { NOW }
        )

        assertEquals(SocialSyncResult.SYNCED, result)
        assertEquals(1, calls)
    }

    @Test
    fun `um desfecho anterior ao pedido nao responde por ele`() = runBlocking {
        // O último ciclo terminou antes do toque, e outro começou: a resposta é a do que está
        // rodando agora, não a do anterior.
        val activity = MutableStateFlow<SyncActivity>(
            SyncActivity.Finished(SyncOutcome.Offline, at = NOW - 10)
        )
        launch {
            delay(50)
            activity.value = SyncActivity.Running
            delay(50)
            activity.value = SyncActivity.Finished(SyncOutcome.Success(), at = NOW + 5)
        }

        val result = runSocialAssistedSync(
            runOnce = { SyncOutcome.AlreadyRunning },
            activity = activity,
            clock = { NOW }
        )

        assertEquals(SocialSyncResult.SYNCED, result)
    }

    @Test
    fun `um ciclo que nao termina a tempo vira ALREADY_RUNNING — nunca um spinner eterno`() =
        runBlocking {
            val started = System.nanoTime()
            val result = runSocialAssistedSync(
                runOnce = { SyncOutcome.AlreadyRunning },
                activity = MutableStateFlow(SyncActivity.Running),
                clock = { NOW },
                waitForRunningMs = 100
            )

            assertEquals(SocialSyncResult.ALREADY_RUNNING, result)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }

    @Test
    fun `backup ou restore em andamento recusam o ciclo — a resposta vem na hora`() = runBlocking {
        // A trava de nuvem do repositório recusa e o coordenador publica o desfecho do próprio
        // pedido: não há ciclo nenhum para esperar.
        val activity = MutableStateFlow<SyncActivity>(SyncActivity.Idle)
        val result = runSocialAssistedSync(
            runOnce = {
                activity.value = SyncActivity.Finished(SyncOutcome.AlreadyRunning, at = NOW)
                SyncOutcome.AlreadyRunning
            },
            activity = activity,
            clock = { NOW },
            waitForRunningMs = 60_000
        )

        assertEquals(SocialSyncResult.ALREADY_RUNNING, result)
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
