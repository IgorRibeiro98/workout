package com.example.presentation.account

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.social.FakeChallengeGateway
import com.example.data.social.FakeFriendGateway
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.ChallengeError
import com.example.domain.social.ChallengeStatus
import com.example.domain.social.ChallengeType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Os desafios no nível do estado da tela (T17.3 §226–§231).
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **o app nunca envia pontuação.** Nem por engano: não há parâmetro. O que ele envia é
 *    intenção, e o teste prova campo a campo o que sai;
 * 2. **nada acontece sozinho.** Criar o ViewModel não pede nada, e abrir um desafio não dispara
 *    sincronização de treino;
 * 3. **offline não finge.** Sem servidor, a ação **não acontece** e nada fica pendente;
 * 4. **trocar de conta invalida na hora.** O desafio que A estava vendo nunca aparece para C —
 *    nem por meio segundo, nem quando a resposta de A chega depois do login de C;
 * 5. **o Room não muda.** Nenhuma operação de desafio escreve uma linha no banco do aparelho —
 *    nem na Outbox. O núcleo de treino continua intocado.
 *
 * Room de verdade (para provar que ele **não** muda) e servidor dublê. Nenhum teste abre socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ChallengeViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var gateway: FakeChallengeGateway
    private lateinit var friends: FakeFriendGateway
    private lateinit var context: Context

    /** Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado no fim. */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    private val today = LocalDate.of(2026, 9, 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        gateway = FakeChallengeGateway()
        friends = FakeFriendGateway()
    }

    @After
    fun tearDown() {
        // Encerrar o ViewModel é obrigatório: um `viewModelScope` vivo entre testes é a causa
        // conhecida de flakiness nesta suíte (a lição de 23d3779). O `ViewModelStore` é o caminho
        // suportado — `ViewModel.clear()` é interno e não está na superfície pública.
        viewModels.clear()
        database.close()
        Dispatchers.resetMain()
    }

    /** O ViewModel de uma conta já conectada — o estado normal de quem abre a área Social. */
    private fun signedIn(uid: String): ChallengeViewModel {
        gateway.currentUid = uid
        friends.currentUid = uid
        val auth = FakeAuthGateway(initialAccount = accountOf(uid))
        authGateway = auth
        return ChallengeViewModel(
            gateway = gateway,
            friendGateway = friends,
            authGateway = auth,
            deviceTimeZoneId = { "America/Sao_Paulo" },
            today = { today },
            newClientRequestId = { "req-fixo" }
        ).also { viewModels.put("challenge-${viewModelKeys++}", it) }
    }

    /** Sai da conta atual e entra em outra — o caminho real de troca de conta. */
    private suspend fun switchTo(uid: String) {
        gateway.currentUid = uid
        friends.currentUid = uid
        authGateway.signOut()
        authGateway.nextOutcome = AuthOutcome.Success(accountOf(uid))
        authGateway.signIn(context)
    }

    private lateinit var authGateway: FakeAuthGateway

    private fun accountOf(uid: String) =
        SparkAccount(uid = uid, displayName = "Pessoa $uid", email = null)

    /** Registra um amigo e a amizade, pelas duas chamadas que o dublê expõe. */
    private fun seedFriend(ownerUid: String, socialId: String, displayName: String) {
        val friendUid = "uid-of-$socialId"
        friends.register(
            FakeFriendGateway.Account(
                uid = friendUid,
                socialId = socialId,
                friendCode = "SPK-${socialId.takeLast(8).uppercase()}",
                displayName = displayName
            )
        )
        friends.seedFriendship(ownerUid, friendUid)
    }

    // --------------------------------------------------------------------------- nada sozinho

    @Test
    fun `criar o ViewModel nao pede nada ao servidor`() = runBlocking {
        val vm = signedIn("uid-a")

        assertEquals(0, gateway.requestCount)
    }

    @Test
    fun `abrir a tela carrega a lista e os convites, e so isso`() = runBlocking {
        gateway.seedChallenge("uid-a", FakeChallengeGateway.challenge())
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite())

        val vm = signedIn("uid-a")
        vm.open()

        val state = vm.uiState.value
        assertTrue(state.listPhase is ChallengeListPhase.Ready)
        assertEquals(1, (state.listPhase as ChallengeListPhase.Ready).challenges.size)
        assertEquals(1, state.invites.size)
        // Duas requisições: a lista e os convites. Nenhuma a mais — em particular, nenhuma por
        // linha da lista (o placar é lido só ao abrir um desafio).
        assertEquals(2, gateway.requestCount)
    }

    @Test
    fun `abrir a tela de novo nao repete a leitura`() = runBlocking {
        gateway.seedChallenge("uid-a", FakeChallengeGateway.challenge())

        val vm = signedIn("uid-a")
        vm.open()
        val after = gateway.requestCount
        vm.open()

        assertEquals(after, gateway.requestCount)
    }

    // --------------------------------------------------------------------------- placar

    @Test
    fun `o placar vem do servidor, com empate e a propria linha marcada`() = runBlocking {
        val challenge = FakeChallengeGateway.challenge()
        gateway.seedChallenge(
            "uid-a",
            challenge,
            FakeChallengeGateway.leaderboard(
                challenge = challenge,
                viewerSocialId = "social-joao",
                scores = listOf(
                    Triple("social-igor", "Igor", 8),
                    Triple("social-joao", "João", 8),
                    Triple("social-jonathas", "Jonathas", 6)
                )
            )
        )

        val vm = signedIn("uid-a")
        vm.openChallenge(challenge.challengeId)

        val detail = (vm.uiState.value.detailPhase as ChallengeDetailPhase.Ready).detail
        // Empate permanece empate: 1, 1, 3.
        assertEquals(listOf(1, 1, 3), detail.participants.map { it.rank })
        assertEquals(listOf(8, 8, 6), detail.participants.map { it.score })
        // A própria linha é identificável (§170/§229).
        assertEquals(listOf("João"), detail.participants.filter { it.isViewer }.map { it.displayName })
    }

    @Test
    fun `pontuacao acima da meta chega inteira, e goalReached e separado da lideranca`() =
        runBlocking {
            val challenge = FakeChallengeGateway.challenge(target = 12)
            gateway.seedChallenge(
                "uid-a",
                challenge,
                FakeChallengeGateway.leaderboard(
                    challenge = challenge,
                    scores = listOf(
                        Triple("social-igor", "Igor", 15),
                        Triple("social-joao", "João", 13),
                        Triple("social-jonathas", "Jonathas", 12)
                    )
                )
            )

            val vm = signedIn("uid-a")
            vm.openChallenge(challenge.challengeId)

            val detail = (vm.uiState.value.detailPhase as ChallengeDetailPhase.Ready).detail
            // 15 de 12 chega como 15: o número não é truncado na meta.
            assertEquals(listOf(15, 13, 12), detail.participants.map { it.score })
            // Todos bateram a meta, e só um lidera.
            assertTrue(detail.participants.all { it.goalReached })
            assertEquals(listOf(1, 2, 3), detail.participants.map { it.rank })
        }

    @Test
    fun `um desafio de que eu nao participo responde indisponivel`() = runBlocking {
        gateway.seedChallenge("uid-b", FakeChallengeGateway.challenge(id = "challenge-de-b"))

        val vm = signedIn("uid-a")
        vm.openChallenge("challenge-de-b")

        assertEquals(ChallengeDetailPhase.NotAvailable, vm.uiState.value.detailPhase)
    }

    @Test
    fun `desafio encerrado que ainda pode convergir carrega o aviso`() = runBlocking {
        val challenge = FakeChallengeGateway.challenge(status = ChallengeStatus.ENDED)
        gateway.seedChallenge(
            "uid-a",
            challenge,
            FakeChallengeGateway.leaderboard(challenge = challenge, resultMayStillChange = true)
        )

        val vm = signedIn("uid-a")
        vm.openChallenge(challenge.challengeId)

        val detail = (vm.uiState.value.detailPhase as ChallengeDetailPhase.Ready).detail
        assertEquals(ChallengeStatus.ENDED, detail.challenge.status)
        // A tela precisa poder dizer a verdade em vez de prometer resultado irrevogável.
        assertTrue(detail.resultMayStillChange)
    }

    // --------------------------------------------------------------------------- criação

    @Test
    fun `o rascunho nasce comecando amanha e valido`() = runBlocking {
        val vm = signedIn("uid-a")
        vm.startCreation()

        val draft = vm.uiState.value.draft
        // Hoje é 8 de setembro; o primeiro dia aceito é 9.
        assertEquals("2026-09-09", draft.startDate)
        assertEquals("America/Sao_Paulo", draft.timeZoneId)
        // Ainda falta o nome e pelo menos um amigo — nascer inválido por isso é correto.
        assertFalse(vm.isDraftValid())
    }

    @Test
    fun `criar envia intencao — e nenhum campo de pontuacao ou identidade`() = runBlocking {
        seedFriend("uid-a", socialId = "social-joao", displayName = "João")

        val vm = signedIn("uid-a")
        vm.startCreation()
        vm.updateDraftName("12 treinos")
        vm.updateDraftTarget(12)
        vm.toggleInvited("social-joao")
        vm.submitCreation()

        val sent = gateway.lastCreateArguments!!
        assertEquals(
            setOf(
                "clientRequestId", "name", "type", "target",
                "startDate", "endDate", "timeZoneId", "invitedSocialIds"
            ),
            sent.keys
        )
        assertEquals("12 treinos", sent["name"])
        assertEquals("WORKOUTS_COMPLETED", sent["type"])
        assertEquals(12, sent["target"])
        assertEquals(listOf("social-joao"), sent["invitedSocialIds"])

        // O bloqueante da tarefa, provado no nível da chamada: nada de pontuação, nada de
        // identidade, nada de ciclo de vida. Não é disciplina — o parâmetro não existe.
        for (forbidden in listOf(
            "score", "progress", "rank", "winner", "goalReached",
            "creatorUid", "ownerUid", "uid", "participantUids", "status",
            "startsAt", "endsAtExclusive"
        )) {
            assertFalse("o app não pode enviar '$forbidden'", sent.containsKey(forbidden))
        }
    }

    @Test
    fun `toque duplo em criar nao cria dois desafios`() = runBlocking {
        seedFriend("uid-a", socialId = "social-joao", displayName = "João")

        val gate = CompletableDeferred<Unit>()
        val vm = signedIn("uid-a")
        vm.startCreation()
        vm.updateDraftName("12 treinos")
        vm.toggleInvited("social-joao")

        gateway.gate = gate
        vm.submitCreation()
        // O segundo toque, com a primeira criação ainda em voo.
        vm.submitCreation()
        gate.complete(Unit)

        // Uma chamada só: a fase `Submitting` bloqueia a segunda antes de ela sair.
        assertEquals(1, gateway.createCallCount)
    }

    @Test
    fun `o retry depois de uma falha reusa o mesmo clientRequestId`() = runBlocking {
        seedFriend("uid-a", socialId = "social-joao", displayName = "João")

        val vm = signedIn("uid-a")
        vm.startCreation()
        vm.updateDraftName("12 treinos")
        vm.toggleInvited("social-joao")

        gateway.failWith = ChallengeError.NETWORK
        vm.submitCreation()

        // Falhou: o rascunho continua na tela, e nada foi criado.
        assertEquals(ChallengeCreationPhase.Editing, vm.uiState.value.creationPhase)
        assertEquals(ChallengeError.NETWORK, vm.uiState.value.notice)
        assertEquals("12 treinos", vm.uiState.value.draft.name)

        gateway.failWith = null
        vm.submitCreation()

        // Duas tentativas, **um** identificador: é ele que faz o servidor devolver o desafio que
        // já existe em vez de criar um segundo (§189).
        assertEquals(2, gateway.createCallCount)
        assertEquals(listOf("req-fixo", "req-fixo"), gateway.seenClientRequestIds)
    }

    @Test
    fun `a meta de dias ativos e limitada pela duracao`() = runBlocking {
        val vm = signedIn("uid-a")
        vm.startCreation()
        vm.updateDraftPeriod("2026-09-10", "2026-09-19")
        vm.updateDraftType(ChallengeType.ACTIVE_DAYS)
        vm.updateDraftTarget(40)

        // Dez dias inclusivos: a meta é cortada em 10, e não vira um desafio impossível.
        assertEquals(10, vm.uiState.value.draft.target)
    }

    @Test
    fun `nao da para convidar mais gente do que o desafio comporta`() = runBlocking {
        repeat(12) { index ->
            seedFriend("uid-a", socialId = "social-$index", displayName = "Amigo $index")
        }

        val vm = signedIn("uid-a")
        vm.startCreation()
        repeat(12) { index -> vm.toggleInvited("social-$index") }

        // Nove convidados mais o criador é o teto de dez.
        assertEquals(9, vm.uiState.value.draft.invitedSocialIds.size)
    }

    // --------------------------------------------------------------------------- convites

    @Test
    fun `aceitar um convite tira ele da lista e traz o desafio`() = runBlocking {
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite())

        val vm = signedIn("uid-a")
        vm.open()
        assertEquals(1, vm.uiState.value.invites.size)

        vm.acceptInvite("invite-1")

        assertEquals(0, vm.uiState.value.invites.size)
        val challenges = (vm.uiState.value.listPhase as ChallengeListPhase.Ready).challenges
        assertEquals(listOf("challenge-1"), challenges.map { it.challengeId })
    }

    @Test
    fun `toque duplo em aceitar dispara uma requisicao so`() = runBlocking {
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite())

        val vm = signedIn("uid-a")
        vm.open()

        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        vm.acceptInvite("invite-1")
        val duringFlight = gateway.requestCount
        vm.acceptInvite("invite-1")

        // O segundo toque não saiu: o alvo está ocupado.
        assertEquals(duringFlight, gateway.requestCount)
        gate.complete(Unit)
        Unit
    }

    @Test
    fun `responder a um convite nao bloqueia a resposta a outro`() = runBlocking {
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite(id = "invite-1"))
        gateway.seedInvite(
            "uid-a",
            FakeChallengeGateway.invite(
                id = "invite-2",
                challenge = FakeChallengeGateway.challenge(id = "challenge-2", name = "10 dias")
            )
        )

        val vm = signedIn("uid-a")
        vm.open()

        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        vm.acceptInvite("invite-1")
        val afterFirst = gateway.requestCount
        // A ocupação é **por alvo**: o segundo convite continua acionável.
        vm.declineInvite("invite-2")

        assertTrue(gateway.requestCount > afterFirst)
        gate.complete(Unit)
        Unit
    }

    // --------------------------------------------------------------------------- sair/cancelar

    @Test
    fun `sair e cancelar sao idempotentes no toque duplo`() = runBlocking {
        gateway.seedChallenge("uid-a", FakeChallengeGateway.challenge())

        val vm = signedIn("uid-a")
        vm.open()

        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        vm.leaveChallenge("challenge-1")
        val afterFirst = gateway.requestCount
        vm.leaveChallenge("challenge-1")
        assertEquals(afterFirst, gateway.requestCount)
        gate.complete(Unit)
        Unit
    }

    // --------------------------------------------------------------------------- offline

    @Test
    fun `offline a lista diz que nada foi alterado`() = runBlocking {
        gateway.failWith = ChallengeError.NETWORK

        val vm = signedIn("uid-a")
        vm.open()

        assertEquals(ChallengeListPhase.Offline, vm.uiState.value.listPhase)
    }

    @Test
    fun `offline aceitar nao acontece e nada fica pendente`() = runBlocking {
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite())

        val vm = signedIn("uid-a")
        vm.open()
        gateway.failWith = ChallengeError.NETWORK
        vm.acceptInvite("invite-1")

        // O aviso explica, o convite continua lá, e **nada** ficou ocupado esperando um reenvio:
        // não há Outbox social.
        assertEquals(ChallengeError.NETWORK, vm.uiState.value.notice)
        assertEquals(1, vm.uiState.value.invites.size)
        assertTrue(vm.uiState.value.pendingInvitationIds.isEmpty())
    }

    @Test
    fun `sem backend social a area de desafios fica indisponivel`() = runBlocking {
        gateway = FakeChallengeGateway(isConfigured = false)
        val vm = signedIn("uid-a")
        vm.open()

        assertEquals(ChallengeListPhase.NotConfigured, vm.uiState.value.listPhase)
        assertEquals(0, gateway.requestCount)
    }

    // --------------------------------------------------------------------------- troca de conta

    @Test
    fun `trocar de conta descarta lista, detalhe, convites e rascunho`() = runBlocking {
        gateway.seedChallenge("uid-a", FakeChallengeGateway.challenge())
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite(id = "invite-de-a"))
        seedFriend("uid-a", socialId = "social-joao", displayName = "João")

        val vm = signedIn("uid-a")
        vm.open()
        vm.openChallenge("challenge-1")
        vm.startCreation()
        vm.updateDraftName("desafio de A")

        assertTrue(vm.uiState.value.invites.isNotEmpty())

        switchTo("uid-c")

        val state = vm.uiState.value
        // Nada de A sobrou — nem a lista, nem o placar, nem os convites, nem o rascunho.
        assertEquals(ChallengeListPhase.Idle, state.listPhase)
        assertEquals(ChallengeDetailPhase.Idle, state.detailPhase)
        assertNull(state.openedChallengeId)
        assertTrue(state.invites.isEmpty())
        assertEquals("", state.draft.name)
        assertTrue(state.selectableFriends.isEmpty())
    }

    @Test
    fun `a resposta da conta anterior e descartada quando ela chega depois do login novo`() =
        runBlocking {
            gateway.seedChallenge("uid-a", FakeChallengeGateway.challenge())

            val gate = CompletableDeferred<Unit>()
            gateway.gate = gate

            val vm = signedIn("uid-a")
            vm.open()

            // A conta muda com a leitura de A ainda no ar.
            switchTo("uid-c")
            gateway.gate = null
            gate.complete(Unit)

            // A resposta de A não pode ter sobrescrito o estado de C.
            val state = vm.uiState.value
            val leaked = (state.listPhase as? ChallengeListPhase.Ready)?.challenges.orEmpty()
            assertTrue("a lista de A vazou para C: $leaked", leaked.isEmpty())
        }

    // --------------------------------------------------------------------------- o núcleo

    @Test
    fun `nenhuma operacao de desafio escreve no Room`() = runBlocking {
        seedFriend("uid-a", socialId = "social-joao", displayName = "João")
        gateway.seedChallenge("uid-a", FakeChallengeGateway.challenge())
        gateway.seedInvite("uid-a", FakeChallengeGateway.invite(id = "invite-1"))

        val dao = database.workoutDao()
        val sessionsBefore: Int = dao.getAllCompletedSessionsWithDetails().size
        val outboxBefore = database.syncOutboxDao().count()

        val vm = signedIn("uid-a")
        vm.open()
        vm.openChallenge("challenge-1")
        vm.startCreation()
        vm.updateDraftName("12 treinos")
        vm.toggleInvited("social-joao")
        vm.submitCreation()
        vm.acceptInvite("invite-1")
        vm.leaveChallenge("challenge-1")
        vm.cancelChallenge("challenge-1")

        // O social é server-authoritative: nada disto vira linha no aparelho, e nada entra na
        // Outbox — que é o que faria um desafio viajar pelo protocolo de sync do treino.
        assertEquals(sessionsBefore, dao.getAllCompletedSessionsWithDetails().size)
        assertEquals(outboxBefore, database.syncOutboxDao().count())
    }
}
