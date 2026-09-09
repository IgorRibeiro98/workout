package com.example.presentation.friends

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SocialGroup
import com.example.domain.social.SocialGroupError
import com.example.domain.social.SocialGroupInvitation
import com.example.domain.social.SocialGroupOutcome
import com.example.domain.social.SocialGroupRole
import com.example.domain.social.StubSocialGroupGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A lista de Squads (T17.11 §112/§113/§163/§164).
 *
 * O que estes casos protegem, além dos estados de tela, é o **isolamento entre contas**: um Squad
 * da conta A desenhado na sessão de B é vazamento, e não atraso — §171 lista isso como bloqueante.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SquadsViewModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var gateway: FakeGroupGateway
    private lateinit var authGateway: FakeAuthGateway
    private lateinit var viewModel: SquadsViewModel

    /**
     * Guarda a ViewModel para que o `viewModelScope` seja **cancelado** no fim do teste.
     *
     * Sem isso, a corrotina de `init` que observa a sessão continua viva depois do teste, e o
     * `kotlinx-coroutines-test` reporta o vazamento na **próxima** classe da suíte — um problema
     * local aparecendo como falha sem relação aparente.
     */
    private lateinit var viewModelStore: ViewModelStore

    private val squadA = SocialGroup(
        groupId = "grupo-de-a",
        name = "Os Monstros",
        memberCount = 7,
        role = SocialGroupRole.OWNER,
        createdAt = 1_800_000_000_000L
    )
    private val squadB = SocialGroup(
        groupId = "grupo-de-b",
        name = "Time da Bruna",
        memberCount = 3,
        role = SocialGroupRole.MEMBER,
        createdAt = 1_700_000_000_000L
    )
    private val invitation = SocialGroupInvitation(
        invitationId = "convite-1",
        groupId = "grupo-de-c",
        groupName = "Squad da Carla",
        memberCount = 5,
        inviterSocialId = "social-c",
        inviterDisplayName = "Carla",
        createdAt = 1_800_000_000_000L,
        expiresAt = 1_900_000_000_000L
    )

    private class FakeGroupGateway : StubSocialGroupGateway() {
        var groupsResult: SocialGroupOutcome<List<SocialGroup>> =
            SocialGroupOutcome.Success(emptyList())
        var invitationsResult: SocialGroupOutcome<List<SocialGroupInvitation>> =
            SocialGroupOutcome.Success(emptyList())
        var createResult: SocialGroupOutcome<SocialGroup> =
            SocialGroupOutcome.Failure(SocialGroupError.REJECTED)
        var acceptResult: SocialGroupOutcome<SocialGroup> =
            SocialGroupOutcome.Failure(SocialGroupError.INVITATION_NOT_AVAILABLE)

        var groupsCalls = 0
        val createdNames = mutableListOf<String>()
        val createdRequestIds = mutableListOf<String>()
        val acceptedIds = mutableListOf<String>()
        val declinedIds = mutableListOf<String>()

        /** Quando presente, a leitura fica suspensa até ser liberada — simula resposta em voo. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun groups(): SocialGroupOutcome<List<SocialGroup>> {
            groupsCalls++
            // A resposta é capturada **na chamada**, e não depois da espera: é isso que permite a
            // um teste dar respostas diferentes para duas leituras em voo ao mesmo tempo.
            val answer = groupsResult
            gate?.await()
            return answer
        }

        override suspend fun invitations(): SocialGroupOutcome<List<SocialGroupInvitation>> =
            invitationsResult

        override suspend fun createGroup(
            name: String,
            clientRequestId: String
        ): SocialGroupOutcome<SocialGroup> {
            createdNames += name
            createdRequestIds += clientRequestId
            return createResult
        }

        override suspend fun acceptInvitation(
            invitationId: String
        ): SocialGroupOutcome<SocialGroup> {
            acceptedIds += invitationId
            return acceptResult
        }

        override suspend fun declineInvitation(invitationId: String): SocialGroupOutcome<Unit> {
            declinedIds += invitationId
            return SocialGroupOutcome.Success(Unit)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        gateway = FakeGroupGateway()
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("uid-a", "Alice"))
        viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SquadsViewModel(gateway = gateway, authGateway = authGateway) as T
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[SquadsViewModel::class.java]
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ estados

    @Test
    fun `carrega squads e convites juntos`() = runTest(testDispatcher) {
        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadA, squadB))
        gateway.invitationsResult = SocialGroupOutcome.Success(listOf(invitation))
        viewModel.refresh()
        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase
        assertTrue(phase is SquadsPhase.Success)
        val success = phase as SquadsPhase.Success
        assertEquals(listOf("grupo-de-a", "grupo-de-b"), success.groups.map { it.groupId })
        assertEquals(listOf("convite-1"), success.invitations.map { it.invitationId })
    }

    @Test
    fun `lista vazia e um estado normal, e nao um erro`() = runTest(testDispatcher) {
        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase
        assertTrue(phase is SquadsPhase.Success)
        assertTrue((phase as SquadsPhase.Success).groups.isEmpty())
    }

    @Test
    fun `sem rede os squads ficam indisponiveis — e nada mais quebra`() = runTest(testDispatcher) {
        gateway.groupsResult = SocialGroupOutcome.Failure(SocialGroupError.NETWORK)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(SquadsPhase.Offline, viewModel.uiState.value.phase)
    }

    @Test
    fun `social desativado tem estado proprio`() = runTest(testDispatcher) {
        gateway.groupsResult = SocialGroupOutcome.Failure(SocialGroupError.SOCIAL_NOT_ENABLED)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(SquadsPhase.SocialNotEnabled, viewModel.uiState.value.phase)
    }

    // ------------------------------------------------------------------ §145/§146 criação

    @Test
    fun `criar envia o nome aparado e um clientRequestId por tentativa`() =
        runTest(testDispatcher) {
            gateway.createResult = SocialGroupOutcome.Success(squadA)
            advanceUntilIdle()

            viewModel.createGroup("  Os Monstros  ")
            advanceUntilIdle()
            viewModel.createGroup("Outro Squad")
            advanceUntilIdle()

            assertEquals(listOf("Os Monstros", "Outro Squad"), gateway.createdNames)
            // §146 — duas tentativas do usuário são duas intenções, e por isso dois identificadores.
            assertEquals(2, gateway.createdRequestIds.distinct().size)
        }

    @Test
    fun `nome invalido nao chega ao servidor`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.createGroup("ab")
        advanceUntilIdle()

        assertTrue(gateway.createdNames.isEmpty())
        assertTrue(viewModel.uiState.value.notice != null)
    }

    @Test
    fun `o teto de squads criados desabilita a criacao na tela`() = runTest(testDispatcher) {
        // §18 — cinco Squads como dono. A tela para de oferecer; o servidor recusa de qualquer
        // forma, e é ele quem decide.
        gateway.groupsResult = SocialGroupOutcome.Success(
            (1..5).map { squadA.copy(groupId = "grupo-$it") }
        )
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canCreateGroup)
    }

    @Test
    fun `participar de muitos squads nao bloqueia criar`() = runTest(testDispatcher) {
        // O teto de §18 conta **posse**, e não participação: dez Squads como MEMBER não impedem
        // criar o primeiro próprio.
        gateway.groupsResult = SocialGroupOutcome.Success(
            (1..10).map { squadB.copy(groupId = "grupo-$it") }
        )
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canCreateGroup)
    }

    // ------------------------------------------------------------------ convites

    @Test
    fun `aceitar remove o convite da lista e relê`() = runTest(testDispatcher) {
        gateway.invitationsResult = SocialGroupOutcome.Success(listOf(invitation))
        gateway.acceptResult = SocialGroupOutcome.Success(squadA)
        viewModel.refresh()
        advanceUntilIdle()

        gateway.invitationsResult = SocialGroupOutcome.Success(emptyList())
        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadA))
        viewModel.acceptInvitation("convite-1")
        advanceUntilIdle()

        assertEquals(listOf("convite-1"), gateway.acceptedIds)
        val phase = viewModel.uiState.value.phase as SquadsPhase.Success
        assertTrue(phase.invitations.isEmpty())
        assertEquals(listOf("grupo-de-a"), phase.groups.map { it.groupId })
    }

    @Test
    fun `convite indisponivel vira aviso, e nao derruba a tela`() = runTest(testDispatcher) {
        gateway.invitationsResult = SocialGroupOutcome.Success(listOf(invitation))
        gateway.acceptResult =
            SocialGroupOutcome.Failure(SocialGroupError.INVITATION_NOT_AVAILABLE)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.acceptInvitation("convite-1")
        advanceUntilIdle()

        assertEquals("Este convite não está mais disponível.", viewModel.uiState.value.notice)
        assertTrue(viewModel.uiState.value.phase is SquadsPhase.Success)
    }

    @Test
    fun `recusar chama o gateway`() = runTest(testDispatcher) {
        gateway.invitationsResult = SocialGroupOutcome.Success(listOf(invitation))
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.declineInvitation("convite-1")
        advanceUntilIdle()

        assertEquals(listOf("convite-1"), gateway.declinedIds)
    }

    // ------------------------------------------------------------------ §163/§164 troca de conta

    @Test
    fun `sair da conta limpa a lista imediatamente`() = runTest(testDispatcher) {
        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadA))
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.groups.isNotEmpty())

        authGateway.signOut()
        advanceUntilIdle()

        assertEquals(SquadsPhase.SignedOut, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.groups.isEmpty())
    }

    @Test
    fun `trocar de conta nao deixa o squad da conta anterior na tela`() = runTest(testDispatcher) {
        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadA))
        viewModel.refresh()
        advanceUntilIdle()

        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadB))
        signInAs("uid-b", "Bruno")
        advanceUntilIdle()

        assertEquals(listOf("grupo-de-b"), viewModel.uiState.value.groups.map { it.groupId })
    }

    /**
     * §164 — uma resposta iniciada para A que chega **depois** do login de B é descartada.
     *
     * O portão segura a leitura da conta A. Enquanto ela está em voo, B entra e a leitura dele
     * completa. Ao liberar o portão, a resposta de A chega — e não pode encostar no estado, ou o
     * Squad de A apareceria na sessão de B.
     */
    @Test
    fun `resposta em voo da conta anterior e descartada`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadA))
        viewModel.refresh()

        gateway.gate = null
        gateway.groupsResult = SocialGroupOutcome.Success(listOf(squadB))
        signInAs("uid-b", "Bruno")
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("grupo-de-b"), viewModel.uiState.value.groups.map { it.groupId })
    }

    /** Troca a sessão pelo caminho real do dublê: sair e entrar de novo, com outra conta. */
    private suspend fun signInAs(uid: String, name: String) {
        authGateway.signOut()
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount(uid, name))
        authGateway.signIn(context)
    }
}
