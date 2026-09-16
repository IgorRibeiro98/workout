package com.example.presentation.coach

import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.ai.FakeAiCapabilitiesGateway
import com.example.domain.ai.model.AiCapabilitiesErrorKind
import com.example.domain.ai.model.AiCapabilitiesGatewayResult
import com.example.domain.ai.model.AiCapability
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
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
 * As capabilities de IA no nível do estado da tela (T19.0).
 *
 * O que estes testes protegem:
 *
 * 1. **nada acontece sem conta**, e sem `ensureLoaded()`;
 * 2. **permitido, negado, carregando e falha ao carregar são estados diferentes** — uma falha
 *    nunca vira "negado" nem "permitido";
 * 3. **trocar de conta invalida na hora**, e a resposta de uma consulta da conta anterior nunca
 *    vira o estado da conta nova, mesmo quando chega depois da troca.
 *
 * Este estado é só UX: `ai-entitlement-enforcement.spec.ts`, do lado do servidor, prova que o
 * backend nunca confia nele.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AiCapabilitiesViewModelTest {

    private val accountA = SparkAccount(uid = "uid-A", displayName = "Igor", email = "a@example.com")
    private val accountB = SparkAccount(uid = "uid-B", displayName = "Jonathas", email = "b@example.com")

    private lateinit var gateway: FakeAiCapabilitiesGateway
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        gateway = FakeAiCapabilitiesGateway()
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `sem conta o estado e SignedOut e nenhuma consulta acontece`() = runBlocking {
        val viewModel = viewModel(FakeAuthGateway())

        viewModel.ensureLoaded()

        assertEquals(AiCapabilitiesUiState.SignedOut, viewModel.state.value)
        assertEquals(0, gateway.fetchCount)
    }

    @Test
    fun `com conta, ensureLoaded carrega e reflete o que o servidor liberou`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Success(setOf(AiCapability.AI_ANALYZE_WORKOUT))
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        viewModel.ensureLoaded()

        val state = viewModel.state.value as AiCapabilitiesUiState.Loaded
        assertTrue(state.isAllowed(AiCapability.AI_ANALYZE_WORKOUT))
        assertFalse(state.isAllowed(AiCapability.AI_GENERATE_WORKOUT))
        assertEquals(1, gateway.fetchCount)
    }

    @Test
    fun `capability ausente da resposta e representada como negada, nao como falha`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Success(emptySet())
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        viewModel.ensureLoaded()

        val state = viewModel.state.value
        assertTrue(state is AiCapabilitiesUiState.Loaded)
        assertEquals(
            CoachActionAvailability.DENIED,
            state.availabilityOf(AiCapability.AI_ADAPT_WORKOUT)
        )
    }

    @Test
    fun `falha ao carregar e um estado proprio, diferente de negado`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.UNAVAILABLE)
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        viewModel.ensureLoaded()

        assertEquals(AiCapabilitiesUiState.LoadFailed, viewModel.state.value)
        assertEquals(
            CoachActionAvailability.UNKNOWN,
            viewModel.state.value.availabilityOf(AiCapability.AI_ANALYZE_WORKOUT)
        )
    }

    @Test
    fun `retry depois de uma falha consulta de novo`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Error(AiCapabilitiesErrorKind.NETWORK)
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))
        viewModel.ensureLoaded()
        assertEquals(AiCapabilitiesUiState.LoadFailed, viewModel.state.value)

        gateway.nextResult = AiCapabilitiesGatewayResult.Success(setOf(AiCapability.AI_EXPLAIN))
        viewModel.retry()

        assertTrue(viewModel.state.value is AiCapabilitiesUiState.Loaded)
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun `ensureLoaded chamado de novo depois de carregado nao refaz a consulta`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Success(emptySet())
        val viewModel = viewModel(FakeAuthGateway(initialAccount = accountA))

        viewModel.ensureLoaded()
        viewModel.ensureLoaded()
        viewModel.ensureLoaded()

        assertEquals(1, gateway.fetchCount)
    }

    @Test
    fun `trocar de conta limpa o estado antes de qualquer requisicao nova`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Success(setOf(AiCapability.AI_GENERATE_WORKOUT))
        val auth = FakeAuthGateway(initialAccount = accountA)
        val viewModel = viewModel(auth)
        viewModel.ensureLoaded()
        assertTrue(viewModel.state.value is AiCapabilitiesUiState.Loaded)

        auth.signOut()

        assertEquals(AiCapabilitiesUiState.SignedOut, viewModel.state.value)
    }

    @Test
    fun `a resposta da conta anterior nao vira estado da conta nova`() = runBlocking {
        gateway.nextResult = AiCapabilitiesGatewayResult.Success(setOf(AiCapability.AI_GENERATE_WORKOUT))
        val auth = FakeAuthGateway(initialAccount = accountA)
        val viewModel = viewModel(auth)

        // A consulta de A fica presa no ar.
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        viewModel.ensureLoaded()
        assertEquals(AiCapabilitiesUiState.Loading, viewModel.state.value)

        // B entra no meio do voo.
        auth.signOut()
        auth.nextOutcome = AuthOutcome.Success(accountB)
        auth.signIn(mockContext())

        // Só agora a consulta de A termina.
        gateway.gate = null
        gate.complete(Unit)

        // O `uid` capturado antes da chamada não vale depois dela: a resposta de A é descartada,
        // e a tela continua mostrando o estado (ainda não carregado) da conta B.
        assertEquals(AiCapabilitiesUiState.Idle, viewModel.state.value)
    }

    private fun viewModel(auth: FakeAuthGateway): AiCapabilitiesViewModel =
        AiCapabilitiesViewModel(gateway, auth)
            .also { viewModels.put("ai-capabilities-${viewModelKeys++}", it) }

    private fun mockContext(): android.content.Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
}
