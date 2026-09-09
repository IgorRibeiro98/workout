package com.example.presentation.friends

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.domain.social.SocialCheckInAuthor
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.StubWorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
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
 * O Feed social (T17.8 §148/§150).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialFeedViewModelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var gateway: FakeFeedGateway
    private lateinit var authGateway: FakeAuthGateway
    private lateinit var viewModel: SocialFeedViewModel

    /**
     * Guarda a ViewModel para que o `viewModelScope` seja **cancelado** no fim do teste.
     *
     * Sem isso, a corrotina de `init` que observa a sessão continua viva depois do teste, e o
     * `kotlinx-coroutines-test` reporta o vazamento na **próxima** classe da suíte — um problema
     * local aparecendo como falha sem relação aparente.
     */
    private lateinit var viewModelStore: ViewModelStore

    private val ownCheckIn = WorkoutCheckIn(
        checkInId = "checkin-proprio",
        author = SocialCheckInAuthor("social-a", "Alice"),
        publishedAt = 1_800_000_000_000L,
        isCurrentUser = true
    )
    private val friendCheckIn = WorkoutCheckIn(
        checkInId = "checkin-amigo",
        author = SocialCheckInAuthor("social-b", "Bob"),
        publishedAt = 1_799_999_000_000L,
        isCurrentUser = false
    )

    private class FakeFeedGateway : StubWorkoutCheckInGateway() {
        var feedResult: WorkoutCheckInOutcome<List<WorkoutCheckIn>> =
            WorkoutCheckInOutcome.Success(emptyList())
        var deleteResult: WorkoutCheckInOutcome<Unit> = WorkoutCheckInOutcome.Success(Unit)
        var feedCalls: Int = 0
        var deletedIds = mutableListOf<String>()

        /** Quando presente, a leitura fica suspensa até ser liberada — simula resposta em voo. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun feed(limit: Int?): WorkoutCheckInOutcome<List<WorkoutCheckIn>> {
            feedCalls++
            // A resposta é capturada **na chamada**, e não depois da espera: é isso que permite a
            // um teste dar respostas diferentes para duas leituras em voo ao mesmo tempo.
            val answer = feedResult
            gate?.await()
            return answer
        }

        override suspend fun deleteCheckIn(checkInId: String): WorkoutCheckInOutcome<Unit> {
            deletedIds += checkInId
            return deleteResult
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        gateway = FakeFeedGateway()
        authGateway = FakeAuthGateway(initialAccount = SparkAccount("uid-a", "Alice"))
        viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SocialFeedViewModel(gateway = gateway, authGateway = authGateway) as T
        }
        viewModel = ViewModelProvider(viewModelStore, factory)[SocialFeedViewModel::class.java]
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ §148 estados

    @Test
    fun `carrega e mostra o proprio check-in e o do amigo`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(ownCheckIn, friendCheckIn))
        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase
        assertTrue(phase is SocialFeedPhase.Success)
        val items = (phase as SocialFeedPhase.Success).items
        assertEquals(listOf("checkin-proprio", "checkin-amigo"), items.map { it.checkInId })
        assertTrue(items[0].isCurrentUser)
        assertFalse(items[1].isCurrentUser)
    }

    @Test
    fun `lista vazia e um estado normal, e nao um erro`() = runTest(testDispatcher) {
        advanceUntilIdle()

        val phase = viewModel.uiState.value.phase
        assertTrue(phase is SocialFeedPhase.Success)
        assertTrue((phase as SocialFeedPhase.Success).items.isEmpty())
    }

    @Test
    fun `enquanto carrega a fase e Loading`() = runTest(testDispatcher) {
        gateway.gate = CompletableDeferred()
        viewModel.refresh()

        assertTrue(viewModel.uiState.value.isRefreshing)

        gateway.gate?.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `sem rede o Feed fica indisponivel — e nada mais quebra`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
        advanceUntilIdle()

        assertEquals(SocialFeedPhase.Offline, viewModel.uiState.value.phase)
    }

    @Test
    fun `servidor fora vira erro recuperavel`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.UNAVAILABLE)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.phase is SocialFeedPhase.Error)
    }

    @Test
    fun `social desativado tem estado proprio`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.SOCIAL_NOT_ENABLED)
        advanceUntilIdle()

        assertEquals(SocialFeedPhase.SocialNotEnabled, viewModel.uiState.value.phase)
    }

    @Test
    fun `excluir a propria publicacao remove da lista`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(ownCheckIn, friendCheckIn))
        advanceUntilIdle()

        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(friendCheckIn))
        viewModel.deleteCheckIn("checkin-proprio")
        advanceUntilIdle()

        assertEquals(listOf("checkin-proprio"), gateway.deletedIds)
        val phase = viewModel.uiState.value.phase as SocialFeedPhase.Success
        assertEquals(listOf("checkin-amigo"), phase.items.map { it.checkInId })
    }

    @Test
    fun `falha ao excluir avisa sem remover da lista`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(ownCheckIn))
        advanceUntilIdle()

        gateway.deleteResult = WorkoutCheckInOutcome.Failure(WorkoutCheckInError.NETWORK)
        viewModel.deleteCheckIn("checkin-proprio")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, (state.phase as SocialFeedPhase.Success).items.size)
        assertTrue(state.notice!!.contains("não foi excluída"))
    }

    // ------------------------------------------------------------------ §150 troca de conta

    @Test
    fun `sair da conta limpa o Feed imediatamente`() = runTest(testDispatcher) {
        gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(ownCheckIn))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.phase is SocialFeedPhase.Success)

        authGateway.signOut()
        advanceUntilIdle()

        assertEquals(SocialFeedPhase.SignedOut, viewModel.uiState.value.phase)
    }

    @Test
    fun `resposta da conta anterior chegando depois da troca e descartada`() =
        runTest(testDispatcher) {
            // A leitura de A fica em voo, carregando o check-in de Alice.
            val gate = CompletableDeferred<Unit>()
            gateway.gate = gate
            gateway.feedResult = WorkoutCheckInOutcome.Success(listOf(ownCheckIn))
            viewModel.refresh()
            advanceUntilIdle()

            // A conta troca para B enquanto a resposta de A não voltou. O Feed de B está vazio —
            // é o que torna as duas respostas distinguíveis quando as duas voltarem juntas.
            authGateway.signOut()
            authGateway.nextOutcome = AuthOutcome.Success(SparkAccount("uid-b", "Bob"))
            authGateway.signIn(context)
            gateway.feedResult = WorkoutCheckInOutcome.Success(emptyList())
            advanceUntilIdle()

            // Agora as duas respostas chegam.
            gateway.gate = null
            gate.complete(Unit)
            advanceUntilIdle()

            // O check-in de Alice **não** pode estar na tela de Bob.
            val phase = viewModel.uiState.value.phase
            val items = (phase as? SocialFeedPhase.Success)?.items.orEmpty()
            assertTrue(
                "o Feed de B não pode conter a publicação lida pela conta A",
                items.none { it.checkInId == "checkin-proprio" }
            )
        }
}
