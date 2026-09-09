package com.example.presentation.friends

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.InteractionContext
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialCheckInAuthor
import com.example.domain.social.SocialGroupDetail
import com.example.domain.social.SocialGroupError
import com.example.domain.social.SocialGroupFeedItem
import com.example.domain.social.SocialGroupOutcome
import com.example.domain.social.SocialGroupRole
import com.example.domain.social.StubSocialGroupGateway
import com.example.domain.social.StubWorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInOutcome
import com.example.domain.social.interactionKey
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Reagir dentro de um Squad (T17.12 §12/§14/§61/§90).
 *
 * ## O que estes casos protegem
 *
 * Que a interação nascida no feed de um Squad pertença **àquele** Squad, e a mais nada. Até a
 * T17.11 esta tela não oferecia reação nem comentário justamente porque não havia como dizer onde
 * a conversa acontecia (T17.11 §70/§71); a T17.12 nomeou a audiência, e o que sobra para o app
 * garantir é que ela viaje em toda chamada e que nenhum estado de tela seja indexado só pelo
 * `checkInId` — dois Squads mostrando a mesma publicação compartilhariam a ocupação do botão.
 *
 * O servidor revalida tudo de qualquer forma (§67/§69): nada aqui é autorização.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SquadDetailViewModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var groups: FakeGroupGateway
    private lateinit var checkIns: FakeCheckInGateway
    private lateinit var authGateway: FakeAuthGateway

    /**
     * Guarda a ViewModel para que o `viewModelScope` seja **cancelado** no fim do teste.
     *
     * Sem isso, a corrotina de `init` que observa a sessão continua viva depois do teste, e o
     * `kotlinx-coroutines-test` reporta o vazamento na **próxima** classe da suíte.
     */
    private lateinit var viewModelStore: ViewModelStore

    private val now = 1_800_000_000_000L
    private val groupId = "grupo-a"
    private val squad = InteractionContext.Group(groupId)

    private val detail = SocialGroupDetail(
        groupId = groupId,
        name = "Os Monstros",
        memberCount = 4,
        role = SocialGroupRole.MEMBER,
        createdAt = now,
        pendingInvitationCount = null
    )

    private class FakeGroupGateway : StubSocialGroupGateway() {
        var detailResult: SocialGroupOutcome<SocialGroupDetail> =
            SocialGroupOutcome.Failure(SocialGroupError.GROUP_NOT_FOUND)
        var feedResult: SocialGroupOutcome<List<SocialGroupFeedItem>> =
            SocialGroupOutcome.Success(emptyList())

        override suspend fun group(groupId: String) = detailResult

        override suspend fun feed(groupId: String, limit: Int?) = feedResult
    }

    private class FakeCheckInGateway : StubWorkoutCheckInGateway() {
        var reactionResult: WorkoutCheckInOutcome<WorkoutCheckIn>? = null
        val reactionCalls = mutableListOf<Triple<String, ReactionType?, InteractionContext>>()

        /** Quando presente, a reação fica em voo até ser liberada — é como se testa a corrida. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun putReaction(
            checkInId: String,
            type: ReactionType,
            context: InteractionContext
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            reactionCalls += Triple(checkInId, type, context)
            // A resposta é capturada **na chamada**, e não depois da espera: é o que permite ao
            // teste trocar de conta enquanto esta requisição continua no ar.
            val answer = reactionResult
            gate?.await()
            return answer
                ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
        }

        override suspend fun removeReaction(
            checkInId: String,
            context: InteractionContext
        ): WorkoutCheckInOutcome<WorkoutCheckIn> {
            reactionCalls += Triple(checkInId, null, context)
            return reactionResult
                ?: WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        groups = FakeGroupGateway()
        checkIns = FakeCheckInGateway()
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("uid-a", "Alice"))
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private fun checkIn(
        checkInId: String = "checkin-1",
        reactions: Map<ReactionType, Int> = emptyMap(),
        current: ReactionType? = null,
        canInteract: Boolean = true
    ) = WorkoutCheckIn(
        checkInId = checkInId,
        author = SocialCheckInAuthor("social-b", "Bruno"),
        publishedAt = now,
        reactions = reactions,
        currentUserReaction = current,
        canInteract = canInteract
    )

    private fun viewModel(): SquadDetailViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SquadDetailViewModel(
                    groupId = groupId,
                    gateway = groups,
                    authGateway = authGateway,
                    checkInGateway = checkIns
                ) as T
        }
        return ViewModelProvider(viewModelStore, factory)[SquadDetailViewModel::class.java]
    }

    private fun givenFeed(vararg items: WorkoutCheckIn) {
        groups.detailResult = SocialGroupOutcome.Success(detail)
        groups.feedResult = SocialGroupOutcome.Success(
            items.map { SocialGroupFeedItem(checkIn = it, sharedToGroupAt = now) }
        )
    }

    private fun feedItems(viewModel: SquadDetailViewModel) =
        (viewModel.uiState.value.phase as SquadDetailPhase.Success).feed

    // =====================================================================
    // §12/§14 — a reação nasce na audiência deste Squad
    // =====================================================================

    @Test
    fun `reagir no feed do squad viaja com a audiencia daquele squad`() =
        runTest(testDispatcher) {
            givenFeed(checkIn())
            val viewModel = viewModel()
            advanceUntilIdle()

            checkIns.reactionResult = WorkoutCheckInOutcome.Success(
                checkIn(reactions = mapOf(ReactionType.FIRE to 3), current = ReactionType.FIRE)
            )
            viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
            advanceUntilIdle()

            // §7 — o `groupId` é o Squad em que a tela está, e ele vai em **toda** chamada. Sem
            // ele o servidor trataria a reação como do Feed de amigos (§68), e a pessoa acabaria
            // reagindo publicamente a partir de um grupo privado.
            assertEquals(
                listOf(Triple("checkin-1", ReactionType.FIRE, squad as InteractionContext)),
                checkIns.reactionCalls
            )
        }

    @Test
    fun `tocar na reacao atual de novo remove — na mesma audiencia`() = runTest(testDispatcher) {
        givenFeed(
            checkIn(reactions = mapOf(ReactionType.FIRE to 2), current = ReactionType.FIRE)
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        checkIns.reactionResult = WorkoutCheckInOutcome.Success(checkIn())
        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
        advanceUntilIdle()

        // §16 — desfazer precisa alcançar a mesma audiência que recebeu a reação; do contrário
        // apagaria a que a pessoa deixou no Feed de amigos.
        assertEquals(
            listOf(Triple("checkin-1", null as ReactionType?, squad as InteractionContext)),
            checkIns.reactionCalls
        )
    }

    @Test
    fun `a reacao e otimista e reconcilia com a resposta do servidor`() = runTest(testDispatcher) {
        givenFeed(checkIn())
        val viewModel = viewModel()
        advanceUntilIdle()

        checkIns.reactionResult = WorkoutCheckInOutcome.Success(
            checkIn(reactions = mapOf(ReactionType.MUSCLE to 9), current = ReactionType.MUSCLE)
        )
        viewModel.toggleReaction("checkin-1", ReactionType.MUSCLE)

        val optimistic = feedItems(viewModel).first().checkIn
        assertEquals(ReactionType.MUSCLE, optimistic.currentUserReaction)
        assertEquals(1, optimistic.reactions[ReactionType.MUSCLE])

        advanceUntilIdle()

        // A contagem é filtrada por viewer no servidor (T17.9 §69): somar aqui recolocaria na
        // conta gente que o bloqueio tirou.
        assertEquals(9, feedItems(viewModel).first().checkIn.reactions[ReactionType.MUSCLE])
    }

    @Test
    fun `reacao que falha volta ao estado que veio do servidor`() = runTest(testDispatcher) {
        val original = checkIn(reactions = mapOf(ReactionType.FIRE to 1))
        givenFeed(original)
        val viewModel = viewModel()
        advanceUntilIdle()

        checkIns.reactionResult =
            WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
        viewModel.toggleReaction("checkin-1", ReactionType.CLAP)
        advanceUntilIdle()

        // O rollback é o estado guardado, e nunca um decremento (T17.9 §121). E o offline **falha
        // visivelmente**: não existe fila social, e uma mutação que não aconteceu não aconteceu.
        assertEquals(original, feedItems(viewModel).first().checkIn)
        assertTrue(viewModel.uiState.value.notice!!.contains("Sem conexão"))
    }

    @Test
    fun `publicacao sem canInteract nao gera requisicao`() = runTest(testDispatcher) {
        givenFeed(checkIn(canInteract = false))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
        advanceUntilIdle()

        // T17.11 §72/§144 — esconder o botão nunca foi controle de acesso, e o servidor recusa de
        // qualquer forma; oferecer o que sempre falha é que é pior do que não oferecer.
        assertEquals(emptyList<Triple<String, ReactionType?, InteractionContext>>(),
            checkIns.reactionCalls)
    }

    // =====================================================================
    // §61 — a ocupação é por (audiência, publicação)
    // =====================================================================

    @Test
    fun `a reacao em voo e marcada pela audiencia, e nao so pelo check-in`() =
        runTest(testDispatcher) {
            givenFeed(checkIn())
            val viewModel = viewModel()
            advanceUntilIdle()

            checkIns.reactionResult = WorkoutCheckInOutcome.Success(checkIn())
            viewModel.toggleReaction("checkin-1", ReactionType.FIRE)

            // A chave carrega o Squad. Uma chave só com o `checkInId` travaria o botão da mesma
            // publicação no Feed de amigos e em outro Squad enquanto esta requisição corre.
            assertEquals(
                setOf(interactionKey("checkin-1", squad)),
                viewModel.uiState.value.pendingReactions
            )
            assertTrue("checkin-1" !in viewModel.uiState.value.pendingReactions)

            advanceUntilIdle()
            assertEquals(emptySet<String>(), viewModel.uiState.value.pendingReactions)
        }

    @Test
    fun `duas reacoes seguidas na mesma publicacao nao viram duas requisicoes`() =
        runTest(testDispatcher) {
            givenFeed(checkIn())
            val viewModel = viewModel()
            advanceUntilIdle()

            checkIns.reactionResult = WorkoutCheckInOutcome.Success(checkIn())
            viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
            viewModel.toggleReaction("checkin-1", ReactionType.CLAP)
            advanceUntilIdle()

            // Uma ação explícita do usuário produz no máximo uma requisição em voo por alvo.
            assertEquals(1, checkIns.reactionCalls.size)
        }

    // =====================================================================
    // §90 — troca de conta
    // =====================================================================

    @Test
    fun `trocar de conta nao deixa feed nem reacao em voo para tras`() = runTest(testDispatcher) {
        givenFeed(checkIn())
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.phase is SquadDetailPhase.Success)

        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)

        // A conta nova não participa deste Squad: o servidor responde o mesmo `404` de "não
        // existe" (T17.11 §59/§60).
        groups.detailResult = SocialGroupOutcome.Failure(SocialGroupError.GROUP_NOT_FOUND)
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bruno"))
        authGateway.signIn(context)
        advanceUntilIdle()

        // Um card do Squad de A desenhado na sessão de B é vazamento, e não atraso (T17.11 §171).
        assertEquals(SquadDetailPhase.Unavailable, viewModel.uiState.value.phase)
        assertEquals(emptySet<String>(), viewModel.uiState.value.pendingReactions)
    }

    @Test
    fun `resposta de reacao da conta anterior nao e aplicada`() = runTest(testDispatcher) {
        givenFeed(checkIn())
        val viewModel = viewModel()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        checkIns.gate = gate
        checkIns.reactionResult = WorkoutCheckInOutcome.Success(
            checkIn(reactions = mapOf(ReactionType.FIRE to 42), current = ReactionType.FIRE)
        )
        viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
        advanceUntilIdle()

        // A conta troca **enquanto** a reação está no ar.
        authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bruno"))
        authGateway.signIn(context)
        advanceUntilIdle()

        // Só agora a resposta da conta anterior chega.
        gate.complete(Unit)
        advanceUntilIdle()

        // Renderizá-la mostraria a B uma contagem que pertence à sessão de A. O número de A não
        // encosta na tela de B, e a ocupação do botão não fica presa.
        val item = feedItems(viewModel).first().checkIn
        assertNull(item.currentUserReaction)
        assertNull(item.reactions[ReactionType.FIRE])
        assertEquals(emptySet<String>(), viewModel.uiState.value.pendingReactions)
    }

    // =====================================================================
    // §116 — um build sem backend continua completo
    // =====================================================================

    @Test
    fun `sem gateway de check-in a tela nao oferece reacao e nada quebra`() =
        runTest(testDispatcher) {
            givenFeed(checkIn())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SquadDetailViewModel(
                        groupId = groupId,
                        gateway = groups,
                        authGateway = authGateway
                    ) as T
            }
            val viewModel =
                ViewModelProvider(viewModelStore, factory)[SquadDetailViewModel::class.java]
            advanceUntilIdle()

            viewModel.toggleReaction("checkin-1", ReactionType.FIRE)
            advanceUntilIdle()

            // T17.11 §116 — sem Spark Backend não há Squads, e a ausência não vira exceção: o
            // feed continua desenhado e a ação simplesmente não existe.
            assertTrue(viewModel.uiState.value.phase is SquadDetailPhase.Success)
            assertEquals(emptySet<String>(), viewModel.uiState.value.pendingReactions)
        }
}
