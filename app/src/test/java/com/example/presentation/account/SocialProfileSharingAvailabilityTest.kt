package com.example.presentation.account

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.social.FakeSocialProfileGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.PROGRESS_SHARING_CONTRACT_VERSION
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingField
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SocialAvailabilityReason
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialSyncResult
import com.example.domain.social.isShared
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Compartilhar progresso" com disponibilidade real (T19.H5), no nível do estado da tela.
 *
 * O que estes testes protegem:
 *
 * 1. **backend legado não é "ainda não disponível".** Sem `contractVersion`, os interruptores da
 *    T19.H3 não são enviados — o servidor os recusaria;
 * 2. **"Sincronizar dados" é o ciclo da T16 e depois uma releitura** — e o "↻" nunca sincroniza;
 * 3. **cada desfecho diz alguma coisa.** Offline, falha e "sincronizou e continua sem treino" não
 *    são o mesmo spinner que volta ao mesmo estado;
 * 4. **a resposta mais velha não escreve.** Uma leitura que saiu antes de um `PATCH` ou de um sync
 *    e voltou depois dele não desfaz a tela;
 * 5. **o fuso que o servidor não conhece é declarado ao abrir**, sem mover interruptor;
 * 6. **trocar de conta não vaza** configuração, disponibilidade, versão nem resultado de sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialProfileSharingAvailabilityTest {

    private lateinit var gateway: FakeSocialProfileGateway
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val accountA = SparkAccount(uid = UID_A, displayName = "Ana", email = "a@example.com")
    private val accountC = SparkAccount(uid = UID_C, displayName = "Carla", email = "c@example.com")

    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    /** Quantas vezes o ciclo de sync foi pedido. É o contador que prova "só no toque". */
    private var syncCalls = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        gateway = FakeSocialProfileGateway()
        gateway.register(
            uid = UID_A,
            socialId = SOCIAL_A,
            displayName = "Ana",
            availability = nothingSynced(),
            settings = ProgressSharingSettings(weekTimeZone = TZ)
        )
        gateway.register(uid = UID_C, socialId = SOCIAL_C, displayName = "Carla")
        gateway.currentUid = UID_A
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ fuso (WEEK_TIME_ZONE_MISSING)

    @Test
    fun `abrir a tela declara o fuso que o servidor nao conhece — sem mover interruptor`() = runBlocking {
        gateway.register(uid = UID_A, socialId = SOCIAL_A, displayName = "Ana")
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        viewModel.openProgressSharing()
        awaitReady(viewModel)

        assertEquals(listOf<String?>(TZ), gateway.weekTimeZoneUpdates)
        assertEquals(listOf(emptyMap<ProgressSharingField, Boolean>()), gateway.sentChanges)
        assertEquals(TZ, viewModel.uiState.value.settings.weekTimeZone)
        for (field in ProgressSharingField.entries) {
            assertFalse("$field continua desligado", gateway.settingsOf(UID_A).isShared(field))
        }
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `o fuso que o servidor ja conhece nao e reenviado — nem ao abrir, nem ao reler`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        viewModel.openProgressSharing()
        awaitReady(viewModel)
        viewModel.refreshProgressSharing()
        awaitIdleRefresh(viewModel)

        assertEquals(emptyList<String?>(), gateway.weekTimeZoneUpdates)
        assertEquals(2, gateway.requestCount)
    }

    // ------------------------------------------------------------------ refresh ≠ sync (§15/§16)

    @Test
    fun `abrir e reler nunca sincronizam — o sync so sai do toque`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
            syncCalls++
            SocialSyncResult.SYNCED
        }

        viewModel.openProgressSharing()
        awaitReady(viewModel)
        repeat(3) {
            viewModel.refreshProgressSharing()
            awaitIdleRefresh(viewModel)
        }

        assertEquals(0, syncCalls)
        // O que falta é treino no servidor: a tela oferece a ação que resolve, mas não a executa.
        assertTrue(viewModel.uiState.value.availability.needsSync)
        assertTrue(viewModel.uiState.value.canSyncData)
        assertEquals(SharingDataSync.Idle, viewModel.uiState.value.dataSync)
    }

    // ------------------------------------------------------------------ sync assistido (§13/§17)

    @Test
    fun `Sincronizar dados roda o ciclo e rele — o dado que chegou fica disponivel`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
            syncCalls++
            // O servidor reprojeta com o treino que acabou de chegar.
            gateway.setAvailability(UID_A, everythingAvailable())
            SocialSyncResult.SYNCED
        }
        viewModel.openProgressSharing()
        awaitReady(viewModel)
        val readsBefore = gateway.requestCount

        viewModel.syncData()
        val finished = awaitSyncFinished(viewModel)

        assertEquals(SharingDataSync.Finished(SocialSyncResult.SYNCED, reread = true), finished)
        assertEquals(1, syncCalls)
        assertEquals(readsBefore + 1, gateway.requestCount)
        val availability = viewModel.uiState.value.availability
        assertEquals(SocialFieldAvailability.AVAILABLE, availability.totalWorkouts)
        assertFalse(availability.needsSync)
        assertFalse(viewModel.uiState.value.isSharingRefreshing)
    }

    @Test
    fun `sincronizou e o servidor continua sem treino — o motivo continua dito, sem spinner`() =
        runBlocking {
            val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
                syncCalls++
                SocialSyncResult.SYNCED
            }
            viewModel.openProgressSharing()
            awaitReady(viewModel)

            viewModel.syncData()
            val finished = awaitSyncFinished(viewModel)

            assertEquals(SharingDataSync.Finished(SocialSyncResult.SYNCED, reread = true), finished)
            assertTrue(viewModel.uiState.value.availability.needsSync)
            assertEquals(
                SocialAvailabilityReason.NO_SYNCED_WORKOUTS,
                viewModel.uiState.value.availability.detailOf(ProgressSharingField.TOTAL_WORKOUTS)?.reason
            )
        }

    @Test
    fun `offline o sync nao rele, nao mexe em preferencia e diz por que`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
            syncCalls++
            SocialSyncResult.OFFLINE
        }
        viewModel.openProgressSharing()
        awaitReady(viewModel)
        val requestsBefore = gateway.requestCount
        val settingsBefore = viewModel.uiState.value.settings

        viewModel.syncData()
        val finished = awaitSyncFinished(viewModel)

        assertEquals(SharingDataSync.Finished(SocialSyncResult.OFFLINE, reread = false), finished)
        assertEquals(requestsBefore, gateway.requestCount)
        assertEquals(settingsBefore, viewModel.uiState.value.settings)
        assertEquals(emptyList<Map<ProgressSharingField, Boolean>>(), gateway.sentChanges)
        assertEquals(ProgressSharingPhase.Ready, viewModel.uiState.value.sharingPhase)
    }

    @Test
    fun `um sync que falha por excecao vira FAILED — nunca spinner eterno`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
            error("o banco local travou")
        }
        viewModel.openProgressSharing()
        awaitReady(viewModel)

        viewModel.syncData()

        assertEquals(
            SharingDataSync.Finished(SocialSyncResult.FAILED, reread = false),
            awaitSyncFinished(viewModel)
        )
    }

    @Test
    fun `dois toques em Sincronizar dados produzem um ciclo, e os interruptores esperam`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
                syncCalls++
                release.await()
                SocialSyncResult.SYNCED
            }
            viewModel.openProgressSharing()
            awaitReady(viewModel)
            val requestsBefore = gateway.requestCount

            viewModel.syncData()
            viewModel.syncData()
            assertEquals(SharingDataSync.Running, viewModel.uiState.value.dataSync)
            // Um toque num interruptor no meio do ciclo não sai: a releitura que vem depois e o
            // `PATCH` cruzados poderiam deixar na tela o interruptor anterior ao toque.
            viewModel.setShare(ProgressSharingField.LEVEL, true)
            assertEquals(requestsBefore, gateway.requestCount)

            release.complete(Unit)
            awaitSyncFinished(viewModel)
            assertEquals(1, syncCalls)
            assertFalse(gateway.settingsOf(UID_A).shareLevel)
        }

    // ------------------------------------------------------------------ corridas (§41)

    @Test
    fun `uma leitura que saiu antes de um PATCH e voltou depois nao desfaz o interruptor`() =
        runBlocking {
            val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))
            viewModel.openProgressSharing()
            awaitReady(viewModel)

            // O "↻" sai com o nível desligado e fica preso na rede...
            val staleRead = CompletableDeferred<Unit>()
            gateway.sharingReadGate = staleRead
            viewModel.refreshProgressSharing()
            // ...o toque grava o nível ligado e volta primeiro...
            viewModel.setShare(ProgressSharingField.LEVEL, true)
            awaitReady(viewModel)
            assertTrue(viewModel.uiState.value.settings.shareLevel)

            // ...e a leitura velha chega por último. Ela não escreve.
            staleRead.complete(Unit)
            assertTrue(viewModel.uiState.value.settings.shareLevel)
            assertTrue(gateway.settingsOf(UID_A).shareLevel)
            assertFalse(viewModel.uiState.value.isSharingRefreshing)
        }

    @Test
    fun `um refresh antigo que volta depois do sync e da releitura nao sobrescreve a tela`() =
        runBlocking {
            val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA)) {
                gateway.setAvailability(UID_A, everythingAvailable())
                SocialSyncResult.SYNCED
            }
            viewModel.openProgressSharing()
            awaitReady(viewModel)

            val staleRead = CompletableDeferred<Unit>()
            gateway.sharingReadGate = staleRead
            viewModel.refreshProgressSharing() // sai com "sem treino no servidor"
            viewModel.syncData() // sincroniza e relê: agora está tudo disponível
            awaitSyncFinished(viewModel)
            assertFalse(viewModel.uiState.value.availability.needsSync)

            staleRead.complete(Unit)
            assertFalse(viewModel.uiState.value.availability.needsSync)
            assertEquals(
                SocialFieldAvailability.AVAILABLE,
                viewModel.uiState.value.availability.totalWorkouts
            )
        }

    // ------------------------------------------------------------------ backend legado (§8)

    @Test
    fun `servidor legado — os interruptores da T19H3 nao sao enviados, e os da T17_2 sim`() =
        runBlocking {
            gateway.contractVersion = 1
            val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))
            viewModel.openProgressSharing()
            awaitReady(viewModel)
            assertEquals(1, viewModel.uiState.value.contractVersion)

            for (field in listOf(
                ProgressSharingField.WORKOUT_NAME,
                ProgressSharingField.WEEKLY_VOLUME,
                ProgressSharingField.TOTAL_WORKOUTS
            )) {
                viewModel.setShare(field, true)
            }
            assertEquals(emptyList<Map<ProgressSharingField, Boolean>>(), gateway.sentChanges)
            assertNull(viewModel.uiState.value.notice)

            viewModel.setShare(ProgressSharingField.LEVEL, true)
            awaitReady(viewModel)
            assertEquals(listOf(mapOf(ProgressSharingField.LEVEL to true)), gateway.sentChanges)
        }

    // ------------------------------------------------------------------ troca de conta (§43)

    @Test
    fun `trocar de conta descarta configuracao, disponibilidade, versao e resultado do sync`() =
        runBlocking {
            gateway.contractVersion = 1
            val auth = FakeAuthGateway(initialAccount = accountA)
            val viewModel = viewModel(auth) { SocialSyncResult.OFFLINE }
            viewModel.openProgressSharing()
            awaitReady(viewModel)
            viewModel.syncData()
            awaitSyncFinished(viewModel)
            assertEquals(1, viewModel.uiState.value.contractVersion)

            switchTo(auth, accountC)

            val state = viewModel.uiState.value
            assertEquals(ProgressSharingPhase.Idle, state.sharingPhase)
            assertEquals(ProgressSharingSettings(), state.settings)
            assertEquals(ProgressSharingAvailability(), state.availability)
            assertEquals(PROGRESS_SHARING_CONTRACT_VERSION, state.contractVersion)
            assertEquals(SharingDataSync.Idle, state.dataSync)
        }

    @Test
    fun `o resultado de um sync iniciado pela conta anterior e descartado`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val auth = FakeAuthGateway(initialAccount = accountA)
        val viewModel = viewModel(auth) {
            release.await()
            SocialSyncResult.SYNCED
        }
        viewModel.openProgressSharing()
        awaitReady(viewModel)
        viewModel.syncData()
        val requestsBefore = gateway.requestCount

        switchTo(auth, accountC)
        release.complete(Unit)

        // Nem o resultado nem a releitura de A chegam à tela de C.
        assertEquals(SharingDataSync.Idle, viewModel.uiState.value.dataSync)
        assertEquals(requestsBefore, gateway.requestCount)
        assertEquals(ProgressSharingPhase.Idle, viewModel.uiState.value.sharingPhase)
    }

    // ------------------------------------------------------------------ apoio

    private fun viewModel(
        auth: FakeAuthGateway,
        assistedSync: (suspend () -> SocialSyncResult)? = null
    ) = SocialProfileViewModel(
        gateway = gateway,
        authGateway = auth,
        deviceTimeZoneId = { TZ },
        assistedSync = assistedSync
    ).also { viewModels.put("sharing-availability-${viewModelKeys++}", it) }

    private suspend fun switchTo(auth: FakeAuthGateway, account: SparkAccount) {
        gateway.currentUid = account.uid
        auth.signOut()
        auth.nextOutcome = AuthOutcome.Success(account)
        auth.signIn(context)
    }

    private suspend fun awaitReady(viewModel: SocialProfileViewModel) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.uiState.first { it.sharingPhase is ProgressSharingPhase.Ready }
        }
    }

    private suspend fun awaitIdleRefresh(viewModel: SocialProfileViewModel) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.uiState.first {
                !it.isSharingRefreshing && it.sharingPhase is ProgressSharingPhase.Ready
            }
        }
    }

    private suspend fun awaitSyncFinished(viewModel: SocialProfileViewModel): SharingDataSync.Finished =
        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.uiState.first { it.dataSync is SharingDataSync.Finished }.dataSync
                as SharingDataSync.Finished
        }

    /** O servidor não tem treino concluído desta conta: tudo indisponível, pelo mesmo motivo. */
    private fun nothingSynced() = ProgressSharingAvailability(
        level = SocialFieldAvailability.UNAVAILABLE,
        consistencyStreak = SocialFieldAvailability.UNAVAILABLE,
        weeklyWorkoutCount = SocialFieldAvailability.UNAVAILABLE,
        highlightedAchievements = SocialFieldAvailability.UNAVAILABLE,
        weeklyTrainingMinutes = SocialFieldAvailability.UNAVAILABLE,
        weeklyCompletedSets = SocialFieldAvailability.UNAVAILABLE,
        weeklyVolume = SocialFieldAvailability.UNAVAILABLE,
        totalWorkouts = SocialFieldAvailability.UNAVAILABLE,
        reasons = ProgressSharingField.entries
            .filter { it.group != com.example.domain.social.ProgressSharingGroup.CHECK_IN_DETAILS }
            .associateWith { SocialAvailabilityReason.NO_SYNCED_WORKOUTS }
    )

    private fun everythingAvailable() = ProgressSharingAvailability(
        level = SocialFieldAvailability.AVAILABLE,
        consistencyStreak = SocialFieldAvailability.AVAILABLE,
        weeklyWorkoutCount = SocialFieldAvailability.AVAILABLE,
        highlightedAchievements = SocialFieldAvailability.AVAILABLE,
        weeklyTrainingMinutes = SocialFieldAvailability.AVAILABLE,
        weeklyCompletedSets = SocialFieldAvailability.AVAILABLE,
        weeklyVolume = SocialFieldAvailability.AVAILABLE,
        totalWorkouts = SocialFieldAvailability.AVAILABLE
    )

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val TZ = "America/Sao_Paulo"
        const val UID_A = "uid-A"
        const val UID_C = "uid-C"
        const val SOCIAL_A = "social-a"
        const val SOCIAL_C = "social-c"
    }
}
