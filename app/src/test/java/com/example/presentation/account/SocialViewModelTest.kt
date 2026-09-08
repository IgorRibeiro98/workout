package com.example.presentation.account

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.social.FakeSocialGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SocialError
import com.example.domain.social.SocialProfileStatus
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Os recursos sociais no nível do estado da tela (T17.0).
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **nada acontece sozinho.** Abrir o Perfil e entrar na conta não ativam Social;
 * 2. **o Room não muda.** Nenhuma operação social escreve uma linha no banco do aparelho — nem
 *    na Outbox, nem no vínculo de nuvem, nem no treino;
 * 3. **offline não finge.** Sem servidor, a alteração **não acontece**, e nada fica pendente;
 * 4. **trocar de conta invalida na hora.** O perfil de A nunca aparece como sendo de B.
 *
 * Room de verdade (para provar que ele **não** muda) e servidor dublê. Nenhum teste abre socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeSocialGateway
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val accountA = SparkAccount(uid = "uid-A", displayName = "Igor", email = "a@example.com")
    private val accountB = SparkAccount(uid = "uid-B", displayName = "Jonathas", email = "b@example.com")

    /** Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado no fim. */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeSocialGateway()
    }

    @After
    fun tearDown() {
        viewModels.clear()
        database.close()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ nada automático

    @Test
    fun `sem conta a secao convida a entrar e nao consulta o servidor`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway())

        assertEquals(SocialPhase.SignedOut, viewModel.uiState.value.phase)
        assertEquals(0, gateway.profileCalls)
        assertEquals(0, gateway.activateCalls)
    }

    @Test
    fun `entrar na conta le o perfil mas nao ativa nada`() = runBlocking {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth)

        auth.nextOutcome = AuthOutcome.Success(accountA)
        gateway.currentUid = accountA.uid
        auth.signIn(context)

        assertEquals(SocialPhase.NotEnabled, awaitPhase(viewModel) { it is SocialPhase.NotEnabled })
        assertEquals("ler o perfil é uma consulta, não uma criação", 1, gateway.profileCalls)
        assertEquals("login não pode ativar Social", 0, gateway.activateCalls)
        assertNull(gateway.stored(accountA.uid))
    }

    @Test
    fun `criar o ViewModel com sessao restaurada nao ativa Social`() = runBlocking {
        gateway.currentUid = accountA.uid
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        awaitPhase(viewModel) { it is SocialPhase.NotEnabled }

        assertEquals(0, gateway.activateCalls)
        assertNull(gateway.stored(accountA.uid))
    }

    @Test
    fun `sem backend configurado a secao some e nada e consultado`() = runBlocking {
        gateway = FakeSocialGateway(isConfigured = false)
        gateway.currentUid = accountA.uid
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        assertEquals(SocialPhase.NotConfigured, viewModel.uiState.value.phase)
        assertEquals(0, gateway.profileCalls)
    }

    // ------------------------------------------------------------------ ativação explícita

    @Test
    fun `abrir a confirmacao nao ativa nada`() = runBlocking {
        val viewModel = signedIn(accountA)

        viewModel.startActivation()

        val state = viewModel.uiState.first { it.isActivationSheetOpen }
        // O nome da conta Google entra como **sugestão** — nada foi criado ainda.
        assertEquals("Igor", state.displayNameInput)
        assertTrue(state.isDisplayNameAcceptable)
        assertEquals(0, gateway.activateCalls)
        assertNull(gateway.stored(accountA.uid))
    }

    @Test
    fun `cancelar a confirmacao nao ativa nada`() = runBlocking {
        val viewModel = signedIn(accountA)

        viewModel.startActivation()
        viewModel.uiState.first { it.isActivationSheetOpen }
        viewModel.cancelActivation()

        assertFalse(viewModel.uiState.value.isActivationSheetOpen)
        assertEquals(0, gateway.activateCalls)
        assertNull(gateway.stored(accountA.uid))
    }

    @Test
    fun `confirmar cria identidade social e a tela mostra codigo e nome`() = runBlocking {
        val viewModel = signedIn(accountA)

        activate(viewModel, "Igor")

        val profile = (viewModel.uiState.value.phase as SocialPhase.Active).profile
        assertEquals("Igor", profile.displayName)
        assertEquals(SocialProfileStatus.ACTIVE, profile.status)
        assertTrue(profile.friendCode.startsWith("SPK-"))
        assertNotNull(profile.socialId)
        // Defaults conservadores.
        assertEquals(true, profile.privacy.friendRequestsEnabled)
        assertEquals(false, profile.privacy.activitySharingEnabled)
    }

    @Test
    fun `nome invalido nao chega ao servidor`() = runBlocking {
        val viewModel = signedIn(accountA)
        viewModel.startActivation()
        viewModel.uiState.first { it.isActivationSheetOpen }

        for (invalid in listOf("", "  ", "I", "x".repeat(41), "Igor\nAdmin")) {
            viewModel.onDisplayNameChanged(invalid)
            assertFalse("deveria recusar: '$invalid'", viewModel.uiState.value.isDisplayNameAcceptable)
            viewModel.confirmActivation()
        }

        assertEquals("o servidor não recebe nome que a tela já sabe recusar", 0, gateway.activateCalls)
    }

    @Test
    fun `toque repetido em ativar produz uma ativacao`() = runBlocking {
        val viewModel = signedIn(accountA)
        viewModel.startActivation()
        viewModel.uiState.first { it.isActivationSheetOpen }
        viewModel.onDisplayNameChanged("Igor")

        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        repeat(5) { viewModel.confirmActivation() }
        gateway.gate = null
        gate.complete(Unit)

        awaitPhase(viewModel) { it is SocialPhase.Active }
        assertEquals("dez toques viram uma ativação, não dez", 1, gateway.activateCalls)
    }

    // ------------------------------------------------------------------ edição e privacidade

    @Test
    fun `editar o nome preserva a identidade`() = runBlocking {
        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")
        val before = (viewModel.uiState.value.phase as SocialPhase.Active).profile

        viewModel.startEditingName()
        viewModel.onDisplayNameChanged("Igor Ribeiro")
        viewModel.confirmDisplayName()

        val after = awaitPhase(viewModel) { it is SocialPhase.Active } as SocialPhase.Active
        assertEquals("Igor Ribeiro", after.profile.displayName)
        assertEquals(before.socialId, after.profile.socialId)
        assertEquals(before.friendCode, after.profile.friendCode)
    }

    @Test
    fun `alterar privacidade persiste no servidor e nao toca na identidade`() = runBlocking {
        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")
        val before = (viewModel.uiState.value.phase as SocialPhase.Active).profile

        viewModel.setActivitySharingEnabled(true)
        val afterSharing = awaitPhase(viewModel) { it is SocialPhase.Active } as SocialPhase.Active
        assertTrue(afterSharing.profile.privacy.activitySharingEnabled)

        viewModel.setFriendRequestsEnabled(false)
        val afterRequests = awaitPhase(viewModel) {
            it is SocialPhase.Active && !it.profile.privacy.friendRequestsEnabled
        } as SocialPhase.Active

        assertFalse(afterRequests.profile.privacy.friendRequestsEnabled)
        assertTrue(afterRequests.profile.privacy.activitySharingEnabled)
        assertEquals(before.socialId, afterRequests.profile.socialId)
        assertEquals(before.friendCode, afterRequests.profile.friendCode)
        assertEquals(2, gateway.privacyCalls)
    }

    // ------------------------------------------------------------------ desativar / reativar

    @Test
    fun `desativar exige confirmacao e preserva a identidade`() = runBlocking {
        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")
        val before = (viewModel.uiState.value.phase as SocialPhase.Active).profile

        viewModel.startDisable()
        assertTrue(viewModel.uiState.value.isConfirmingDisable)
        assertEquals("abrir a confirmação não desativa", 0, gateway.disableCalls)

        viewModel.cancelDisable()
        assertEquals(0, gateway.disableCalls)

        viewModel.startDisable()
        viewModel.confirmDisable()

        val disabled = awaitPhase(viewModel) {
            it is SocialPhase.Active && it.profile.status == SocialProfileStatus.DISABLED
        } as SocialPhase.Active
        assertEquals(before.socialId, disabled.profile.socialId)
        assertEquals(before.friendCode, disabled.profile.friendCode)
    }

    @Test
    fun `reativar devolve o mesmo socialId e o mesmo friendCode`() = runBlocking {
        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")
        val before = (viewModel.uiState.value.phase as SocialPhase.Active).profile

        viewModel.startDisable()
        viewModel.confirmDisable()
        awaitPhase(viewModel) {
            it is SocialPhase.Active && it.profile.status == SocialProfileStatus.DISABLED
        }

        viewModel.enable()

        val enabled = awaitPhase(viewModel) {
            it is SocialPhase.Active && it.profile.status == SocialProfileStatus.ACTIVE
        } as SocialPhase.Active
        assertEquals(before.socialId, enabled.profile.socialId)
        assertEquals(before.friendCode, enabled.profile.friendCode)
        assertEquals(before.displayName, enabled.profile.displayName)
    }

    @Test
    fun `estado ja aplicado em outro aparelho recarrega em vez de mostrar erro`() = runBlocking {
        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")

        // O perfil já está ativo: o servidor responde conflito, e a tela relê em vez de acusar.
        viewModel.enable()

        val phase = awaitPhase(viewModel) { it is SocialPhase.Active }
        assertTrue(phase is SocialPhase.Active)
    }

    // ------------------------------------------------------------------ offline

    @Test
    fun `offline nao altera nada e diz isso`() = runBlocking {
        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")
        val before = gateway.stored(accountA.uid)

        gateway.failWith = SocialError.NETWORK
        viewModel.startEditingName()
        viewModel.onDisplayNameChanged("Outro Nome")
        viewModel.confirmDisplayName()

        val phase = awaitPhase(viewModel) { it is SocialPhase.Offline } as SocialPhase.Offline
        // O perfil anterior continua visível — e continua sendo o do servidor.
        assertEquals("Igor", phase.profile?.displayName)
        assertEquals(before, gateway.stored(accountA.uid))
    }

    @Test
    fun `offline na leitura inicial nao esconde a conta nem inventa perfil`() = runBlocking {
        gateway.currentUid = accountA.uid
        gateway.failWith = SocialError.NETWORK

        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        val phase = awaitPhase(viewModel) { it is SocialPhase.Offline } as SocialPhase.Offline
        assertNull(phase.profile)
        assertNull(gateway.stored(accountA.uid))
    }

    @Test
    fun `servidor indisponivel vira erro recuperavel, e nao logout`() = runBlocking {
        gateway.currentUid = accountA.uid
        gateway.failWith = SocialError.UNAVAILABLE

        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        val phase = awaitPhase(viewModel) { it is SocialPhase.Error } as SocialPhase.Error
        assertEquals(SocialError.UNAVAILABLE, phase.reason)
    }

    // ------------------------------------------------------------------ troca de conta

    @Test
    fun `trocar de conta invalida o estado social anterior imediatamente`() = runBlocking {
        val auth = FakeAuthGateway(initialAccount = accountA)
        gateway.currentUid = accountA.uid
        val viewModel = viewModel(auth)

        activate(viewModel, "Igor")
        assertEquals("Igor", viewModel.uiState.value.profile?.displayName)

        // Sair: o estado social de A tem de desaparecer no mesmo instante.
        gateway.currentUid = null
        auth.signOut()
        assertEquals(SocialPhase.SignedOut, awaitPhase(viewModel) { it is SocialPhase.SignedOut })
        assertNull(viewModel.uiState.value.profile)

        // Entrar como B: enquanto a resposta de B não chega, a tela **não** mostra o perfil de A.
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        gateway.currentUid = accountB.uid
        auth.nextOutcome = AuthOutcome.Success(accountB)
        auth.signIn(context)

        val duringFlight = viewModel.uiState.value
        assertEquals(SocialPhase.Loading, duringFlight.phase)
        assertNull("o perfil de A não pode reaparecer como se fosse de B", duringFlight.profile)

        gateway.gate = null
        gate.complete(Unit)
        assertEquals(SocialPhase.NotEnabled, awaitPhase(viewModel) { it is SocialPhase.NotEnabled })
    }

    @Test
    fun `resposta de uma conta nao vira estado de outra`() = runBlocking {
        val auth = FakeAuthGateway(initialAccount = accountA)
        gateway.currentUid = accountA.uid
        gateway.seed(
            accountA.uid,
            com.example.domain.social.SocialProfile(
                socialId = "social-A",
                friendCode = "SPK-AAAAAAAA",
                displayName = "Igor",
                status = SocialProfileStatus.ACTIVE,
                privacy = com.example.domain.social.SocialPrivacySettings(),
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        // A leitura de A fica presa no voo.
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val viewModel = viewModel(auth)

        // A conta troca **durante** a requisição de A.
        gateway.currentUid = accountB.uid
        auth.signOut()
        auth.nextOutcome = AuthOutcome.Success(accountB)
        auth.signIn(context)

        gateway.gate = null
        gate.complete(Unit)

        // A resposta de A chega tarde e é descartada: a tela nunca mostra "Igor" como sendo de B.
        val phase = awaitPhase(viewModel) { it !is SocialPhase.Loading }
        assertTrue("fase inesperada: $phase", phase is SocialPhase.NotEnabled)
        assertNull(viewModel.uiState.value.profile)
    }

    // ------------------------------------------------------------------ o núcleo não paga nada

    @Test
    fun `nenhuma operacao social escreve no Room`() = runBlocking {
        // Dado local anterior, que precisa continuar exatamente como estava.
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        database.workoutDao().insertTemplate(
            WorkoutTemplateEntity(name = "Treino A", programId = programId)
        )
        val templatesBefore = database.workoutDao().getAllTemplatesSync().size
        val outboxBefore = database.syncOutboxDao().count()

        val viewModel = signedIn(accountA)
        activate(viewModel, "Igor")
        viewModel.setActivitySharingEnabled(true)
        awaitPhase(viewModel) { it is SocialPhase.Active }
        viewModel.startEditingName()
        viewModel.onDisplayNameChanged("Igor Ribeiro")
        viewModel.confirmDisplayName()
        awaitPhase(viewModel) { it is SocialPhase.Active }
        viewModel.startDisable()
        viewModel.confirmDisable()
        awaitPhase(viewModel) {
            it is SocialPhase.Active && it.profile.status == SocialProfileStatus.DISABLED
        }

        assertEquals("social não cria nem apaga treino", templatesBefore, database.workoutDao().getAllTemplatesSync().size)
        assertEquals("social não entra na Outbox", outboxBefore, database.syncOutboxDao().count())
        assertNull("social não vincula dataset a conta nenhuma", database.cloudDataBindingDao().get())
    }

    @Test
    fun `falha social nao impede o nucleo de funcionar`() = runBlocking {
        gateway.failWith = SocialError.UNAVAILABLE
        val viewModel = signedIn(accountA)
        awaitPhase(viewModel) { it is SocialPhase.Error }

        // O Room continua respondendo normalmente com o social quebrado.
        val programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa"))
        database.workoutDao().insertTemplate(
            WorkoutTemplateEntity(name = "Treino A", programId = programId)
        )

        assertEquals(1, database.workoutDao().getAllTemplatesSync().size)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun signedIn(account: SparkAccount): SocialViewModel {
        gateway.currentUid = account.uid
        val viewModel = viewModel(FakeAuthGateway(initialAccount = account))
        awaitPhase(viewModel) { it !is SocialPhase.Loading }
        return viewModel
    }

    private suspend fun activate(viewModel: SocialViewModel, name: String) {
        viewModel.startActivation()
        viewModel.uiState.first { it.isActivationSheetOpen }
        viewModel.onDisplayNameChanged(name)
        viewModel.confirmActivation()
        awaitPhase(viewModel) { it is SocialPhase.Active }
    }

    private suspend fun awaitPhase(
        viewModel: SocialViewModel,
        predicate: (SocialPhase) -> Boolean
    ): SocialPhase = withTimeout(AWAIT_TIMEOUT_MS) {
        viewModel.uiState.first { predicate(it.phase) }.phase
    }

    private fun viewModel(auth: FakeAuthGateway) =
        SocialViewModel(gateway = gateway, authGateway = auth)
            .also { viewModels.put("social-${viewModelKeys++}", it) }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}
