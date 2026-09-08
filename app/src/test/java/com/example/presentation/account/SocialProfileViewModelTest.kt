package com.example.presentation.account

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.social.FakeSocialProfileGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SharedProgress
import com.example.domain.social.SocialFieldAvailability
import com.example.domain.social.SocialProfileError
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
 * O perfil social enriquecido no nível do estado da tela (T17.2).
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **nada acontece sozinho, e nada acontece em lote.** Criar o ViewModel não pede nada; um
 *    perfil é lido no toque, e a lista de amigos não dispara uma requisição por linha;
 * 2. **ausência não vira zero.** Um campo ligado sem dado chega ausente, e a tela mostra "ainda
 *    não compartilha" — nunca "0 treinos";
 * 3. **offline não finge.** Sem servidor, o interruptor **não** se move e nada fica pendente;
 * 4. **trocar de conta invalida na hora.** O perfil que A estava vendo nunca aparece para C, nem
 *    por meio segundo, nem quando a resposta de A chega depois do login de C;
 * 5. **o Room não muda.** Nenhuma operação do perfil social escreve uma linha no banco do
 *    aparelho — nem na Outbox.
 *
 * Room de verdade (para provar que ele **não** muda) e servidor dublê. Nenhum teste abre socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialProfileViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeSocialProfileGateway
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val accountA = SparkAccount(uid = UID_A, displayName = "Ana", email = "a@example.com")
    private val accountC = SparkAccount(uid = UID_C, displayName = "Carla", email = "c@example.com")

    /** Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado no fim. */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeSocialProfileGateway()

        gateway.register(
            uid = UID_A,
            socialId = SOCIAL_A,
            displayName = "Ana",
            availability = allAvailable(),
            progress = SharedProgress(level = 7, consistencyStreak = 2, weeklyWorkoutCount = 1)
        )
        gateway.register(
            uid = UID_B,
            socialId = SOCIAL_B,
            displayName = "Igor",
            availability = allAvailable(),
            progress = SharedProgress(level = 14, consistencyStreak = 4, weeklyWorkoutCount = 3)
        )
        gateway.register(uid = UID_C, socialId = SOCIAL_C, displayName = "Carla")
        gateway.makeFriends(UID_A, UID_B)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        database.close()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ nada automático

    @Test
    fun `criar o ViewModel nao faz requisicao nenhuma`() = runBlocking {
        gateway.currentUid = UID_A
        viewModel(FakeAuthGateway(initialAccount = accountA))

        assertEquals(0, gateway.requestCount)
    }

    @Test
    fun `abrir o perfil de um amigo faz uma requisicao — e reabrir nao refaz`() = runBlocking {
        share(UID_B, level = true)
        val viewModel = signedIn(accountA)

        viewModel.openFriendProfile(SOCIAL_B)
        awaitFriendPhase(viewModel) { it is FriendProfilePhase.Ready }
        assertEquals(1, gateway.requestCount)

        // Voltar para a tela não repete a leitura. Uma lista de dez amigos não vira dez
        // requisições só porque alguém entrou e saiu de dois perfis.
        viewModel.openFriendProfile(SOCIAL_B)
        assertEquals(1, gateway.requestCount)
    }

    // ------------------------------------------------------------------ o que o amigo vê

    @Test
    fun `compartilhamento parcial mostra so o que foi compartilhado`() = runBlocking {
        share(UID_B, level = true, weekly = false, streak = false)
        val viewModel = signedIn(accountA)

        viewModel.openFriendProfile(SOCIAL_B)
        val phase = awaitFriendPhase(viewModel) { it is FriendProfilePhase.Ready }
        val progress = (phase as FriendProfilePhase.Ready).profile.sharedProgress

        assertEquals(14, progress.level)
        // Ausente, e não zero: a tela não desenha linha nenhuma para o que não veio.
        assertNull(progress.consistencyStreak)
        assertNull(progress.weeklyWorkoutCount)
    }

    @Test
    fun `sem nada compartilhado a tela diz que nao ha progresso — e nao que nao treina`() =
        runBlocking {
            val viewModel = signedIn(accountA)

            viewModel.openFriendProfile(SOCIAL_B)
            val phase = awaitFriendPhase(viewModel) { it is FriendProfilePhase.NoSharedProgress }

            assertTrue((phase as FriendProfilePhase.NoSharedProgress).profile.sharedProgress.isEmpty)
        }

    @Test
    fun `ligado sem dado disponivel chega ausente, nunca zero`() = runBlocking {
        // O caso real desta versão: nível e sequência são calculados no aparelho e não chegam ao
        // servidor. Ligar o interruptor guarda a preferência e **não** publica um zero.
        gateway.register(
            uid = UID_B,
            socialId = SOCIAL_B,
            displayName = "Igor",
            progress = SharedProgress(level = 14, weeklyWorkoutCount = 3),
            availability = ProgressSharingAvailability(
                level = SocialFieldAvailability.UNSUPPORTED,
                consistencyStreak = SocialFieldAvailability.UNSUPPORTED,
                weeklyWorkoutCount = SocialFieldAvailability.UNAVAILABLE,
                highlightedAchievements = SocialFieldAvailability.UNSUPPORTED
            ),
            settings = ProgressSharingSettings(
                shareLevel = true,
                shareWeeklyWorkoutCount = true
            )
        )
        gateway.makeFriends(UID_A, UID_B)
        val viewModel = signedIn(accountA)

        viewModel.openFriendProfile(SOCIAL_B)
        val phase = awaitFriendPhase(viewModel) { it is FriendProfilePhase.NoSharedProgress }
        val progress = (phase as FriendProfilePhase.NoSharedProgress).profile.sharedProgress

        assertNull(progress.level)
        assertNull(progress.weeklyWorkoutCount)
    }

    @Test
    fun `desfazer a amizade torna o perfil indisponivel na proxima leitura`() = runBlocking {
        share(UID_B, level = true)
        val viewModel = signedIn(accountA)
        viewModel.openFriendProfile(SOCIAL_B)
        awaitFriendPhase(viewModel) { it is FriendProfilePhase.Ready }

        gateway.unfriend(UID_A, UID_B)
        viewModel.refreshFriendProfile()

        awaitFriendPhase(viewModel) { it is FriendProfilePhase.NotAvailable }
        Unit
    }

    @Test
    fun `nao amigo nao ve perfil`() = runBlocking {
        val viewModel = signedIn(accountC)

        viewModel.openFriendProfile(SOCIAL_B)

        awaitFriendPhase(viewModel) { it is FriendProfilePhase.NotAvailable }
        Unit
    }

    @Test
    fun `sem internet o perfil fica offline, e nada foi enviado`() = runBlocking {
        val viewModel = signedIn(accountA)
        gateway.failWith = SocialProfileError.NETWORK

        viewModel.openFriendProfile(SOCIAL_B)

        awaitFriendPhase(viewModel) { it is FriendProfilePhase.Offline }
        Unit
    }

    // ------------------------------------------------------------------ minhas configurações

    @Test
    fun `as configuracoes comecam desligadas`() = runBlocking {
        val viewModel = signedIn(accountA)

        viewModel.openProgressSharing()
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }

        val settings = viewModel.uiState.value.settings
        assertFalse(settings.shareLevel)
        assertFalse(settings.shareConsistencyStreak)
        assertFalse(settings.shareWeeklyWorkoutCount)
        assertFalse(settings.shareHighlightedAchievements)
    }

    @Test
    fun `ligar um campo salva no servidor e reflete o que ele devolveu`() = runBlocking {
        val viewModel = signedIn(accountA)
        viewModel.openProgressSharing()
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }

        viewModel.setShareWeeklyWorkoutCount(true)
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }

        assertTrue(viewModel.uiState.value.settings.shareWeeklyWorkoutCount)
        // O fuso viaja junto na primeira alteração: sem ele o servidor não conseguiria usar a
        // mesma semana canônica da tela de consistência.
        assertEquals(gateway.settingsOf(UID_A).weekTimeZone, viewModel.uiState.value.settings.weekTimeZone)
        assertTrue(viewModel.uiState.value.settings.weekTimeZone != null)
        // E os outros três não foram tocados: a semântica é de PATCH.
        assertFalse(viewModel.uiState.value.settings.shareLevel)
    }

    @Test
    fun `offline o interruptor nao se move e a tela avisa que nada foi alterado`() = runBlocking {
        val viewModel = signedIn(accountA)
        viewModel.openProgressSharing()
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }

        gateway.failWith = SocialProfileError.NETWORK
        viewModel.setShareLevel(true)
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }

        // Não houve atualização otimista: o estado continua o que o servidor confirmou por último.
        assertFalse(viewModel.uiState.value.settings.shareLevel)
        assertEquals(SocialProfileError.NETWORK, viewModel.uiState.value.notice)
        // E nada ficou pendente: o dublê não guardou nada, porque não existe Outbox social.
        assertFalse(gateway.settingsOf(UID_A).shareLevel)
    }

    @Test
    fun `a previa vem do servidor, pelo mesmo caminho do perfil do amigo`() = runBlocking {
        share(UID_A, level = true)
        val viewModel = signedIn(accountA)
        viewModel.openProgressSharing()
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }

        viewModel.loadPreview()
        val preview = withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.uiState.first { it.preview != null }.preview!!
        }

        assertEquals(SOCIAL_A, preview.socialId)
        assertEquals(7, preview.sharedProgress.level)
        // O que está desligado não aparece nem na prévia do próprio dono: ela mostra o que os
        // amigos veem, e não o que eu tenho.
        assertNull(preview.sharedProgress.weeklyWorkoutCount)
    }

    @Test
    fun `alterar uma configuracao atualiza a previa que estava na tela`() = runBlocking {
        val viewModel = signedIn(accountA)
        viewModel.openProgressSharing()
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }
        viewModel.loadPreview()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.uiState.first { it.preview != null } }

        viewModel.setShareLevel(true)

        withTimeout(AWAIT_TIMEOUT_MS) {
            viewModel.uiState.first { it.preview?.sharedProgress?.level == 7 }
        }
        Unit
    }

    // ------------------------------------------------------------------ troca de conta

    @Test
    fun `trocar de conta descarta o perfil que estava aberto`() = runBlocking {
        share(UID_B, level = true)
        val auth = FakeAuthGateway(initialAccount = accountA)
        gateway.currentUid = UID_A
        val viewModel = viewModel(auth)

        viewModel.openFriendProfile(SOCIAL_B)
        awaitFriendPhase(viewModel) { it is FriendProfilePhase.Ready }

        switchTo(auth, accountC)

        val state = viewModel.uiState.value
        assertNull("o perfil de B não pode sobreviver ao login de C", state.openedSocialId)
        assertEquals(FriendProfilePhase.Idle, state.friendPhase)
        assertNull(state.preview)
        assertFalse(state.settings.shareLevel)
    }

    @Test
    fun `a resposta iniciada pela conta anterior e descartada`() = runBlocking {
        share(UID_B, level = true)
        val auth = FakeAuthGateway(initialAccount = accountA)
        gateway.currentUid = UID_A
        val viewModel = viewModel(auth)

        // A requisição de A fica presa no ar.
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        viewModel.openFriendProfile(SOCIAL_B)
        awaitFriendPhase(viewModel) { it is FriendProfilePhase.Loading }

        // C entra enquanto ela voa, e só então a resposta de A chega.
        switchTo(auth, accountC)
        gateway.gate = null
        gate.complete(Unit)

        val state = viewModel.uiState.value
        assertEquals(
            "a resposta da relação A–B não pode renderizar para C",
            FriendProfilePhase.Idle,
            state.friendPhase
        )
        assertNull(state.openedSocialId)
    }

    // ------------------------------------------------------------------ o Room não muda

    @Test
    fun `nenhuma operacao do perfil social escreve no Room`() = runBlocking {
        share(UID_B, level = true)
        val viewModel = signedIn(accountA)

        viewModel.openFriendProfile(SOCIAL_B)
        awaitFriendPhase(viewModel) { it is FriendProfilePhase.Ready }
        viewModel.openProgressSharing()
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }
        viewModel.setShareLevel(true)
        awaitSharing(viewModel) { it is ProgressSharingPhase.Ready }
        viewModel.loadPreview()
        withTimeout(AWAIT_TIMEOUT_MS) { viewModel.uiState.first { it.preview != null } }

        // A prova de que não há Outbox social, e de que perfil de terceiro não é guardado: as
        // tabelas do aparelho continuam vazias depois de ler, configurar e prever.
        val db = database.openHelper.readableDatabase
        for (table in listOf("sync_outbox", "workout_sessions", "xp_transactions")) {
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table deveria continuar vazia", 0, cursor.getInt(0))
            }
        }
    }

    // ------------------------------------------------------------------ apoio

    private fun share(
        uid: String,
        level: Boolean = false,
        streak: Boolean = false,
        weekly: Boolean = false
    ) {
        val socialId = when (uid) {
            UID_A -> SOCIAL_A
            UID_B -> SOCIAL_B
            else -> SOCIAL_C
        }
        val name = when (uid) {
            UID_A -> "Ana"
            UID_B -> "Igor"
            else -> "Carla"
        }
        val progress = when (uid) {
            UID_A -> SharedProgress(level = 7, consistencyStreak = 2, weeklyWorkoutCount = 1)
            UID_B -> SharedProgress(level = 14, consistencyStreak = 4, weeklyWorkoutCount = 3)
            else -> SharedProgress()
        }
        gateway.register(
            uid = uid,
            socialId = socialId,
            displayName = name,
            progress = progress,
            availability = allAvailable(),
            settings = ProgressSharingSettings(
                shareLevel = level,
                shareConsistencyStreak = streak,
                shareWeeklyWorkoutCount = weekly
            )
        )
        gateway.makeFriends(UID_A, UID_B)
    }

    private fun allAvailable() = ProgressSharingAvailability(
        level = SocialFieldAvailability.AVAILABLE,
        consistencyStreak = SocialFieldAvailability.AVAILABLE,
        weeklyWorkoutCount = SocialFieldAvailability.AVAILABLE,
        highlightedAchievements = SocialFieldAvailability.AVAILABLE
    )

    /** Sai da conta atual e entra em outra — o caminho real de troca de conta. */
    private suspend fun switchTo(auth: FakeAuthGateway, account: SparkAccount) {
        gateway.currentUid = account.uid
        auth.signOut()
        auth.nextOutcome = AuthOutcome.Success(account)
        auth.signIn(context)
    }

    private fun signedIn(account: SparkAccount): SocialProfileViewModel {
        gateway.currentUid = account.uid
        return viewModel(FakeAuthGateway(initialAccount = account))
    }

    private suspend fun awaitFriendPhase(
        viewModel: SocialProfileViewModel,
        predicate: (FriendProfilePhase) -> Boolean
    ): FriendProfilePhase = withTimeout(AWAIT_TIMEOUT_MS) {
        viewModel.uiState.first { predicate(it.friendPhase) }.friendPhase
    }

    private suspend fun awaitSharing(
        viewModel: SocialProfileViewModel,
        predicate: (ProgressSharingPhase) -> Boolean
    ): ProgressSharingPhase = withTimeout(AWAIT_TIMEOUT_MS) {
        viewModel.uiState.first { predicate(it.sharingPhase) }.sharingPhase
    }

    private fun viewModel(auth: FakeAuthGateway) =
        SocialProfileViewModel(
            gateway = gateway,
            authGateway = auth,
            deviceTimeZoneId = { "America/Sao_Paulo" }
        ).also { viewModels.put("social-profile-${viewModelKeys++}", it) }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val UID_A = "uid-A"
        const val UID_B = "uid-B"
        const val UID_C = "uid-C"
        const val SOCIAL_A = "social-a"
        const val SOCIAL_B = "social-b"
        const val SOCIAL_C = "social-c"
    }
}
