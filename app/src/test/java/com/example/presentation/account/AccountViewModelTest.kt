package com.example.presentation.account

import android.app.Application
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.auth.AuthError
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.AuthState
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

/**
 * A conta Spark, no nível do estado.
 *
 * Todos os testes rodam offline: nenhum toca Firebase, Google, seletor de contas ou rede.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AccountViewModelTest {

    private val host: Application get() = ApplicationProvider.getApplicationContext()

    /**
     * Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado.
     *
     * `AccountViewModel` coleta `authGateway.state` no `init`, e um `StateFlow` não termina: sem
     * cancelar, cada teste deixa uma corrotina viva em `Dispatchers.Main`. O `setMain`/`resetMain`
     * seguinte encontra alguém lendo o dispatcher no meio da troca, e a falha aparece em outro
     * método — ou em outra classe.
     */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    private fun accountViewModel(gateway: FakeAuthGateway) =
        AccountViewModel(gateway).also { viewModels.put("account-${viewModelKeys++}", it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Encerra quem ainda coleta antes de devolver o `Dispatchers.Main`.
        viewModels.clear()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------------------- estado

    @Test
    fun `sem usuario no Firebase o estado e SignedOut`() = runTest {
        val viewModel = accountViewModel(FakeAuthGateway())

        assertEquals(AuthState.SignedOut, viewModel.uiState.value.authState)
        assertFalse(viewModel.uiState.value.isSignedIn)
        assertNull(viewModel.uiState.value.account)
    }

    @Test
    fun `sessao existente e restaurada sem abrir seletor de contas`() = runTest {
        val existing = SparkAccount(uid = "uid-restaurado", displayName = "Ana")
        val gateway = FakeAuthGateway(initialAccount = existing)

        // Recriar o ViewModel é o que acontece quando o app reabre.
        val viewModel = accountViewModel(gateway)

        assertEquals(AuthState.SignedIn(existing), viewModel.uiState.value.authState)
        assertEquals(
            "restaurar sessão não pode iniciar autenticação",
            0,
            gateway.flowsStarted
        )
    }

    @Test
    fun `criar o ViewModel e observar o estado nao inicia autenticacao`() = runTest {
        val gateway = FakeAuthGateway()

        val viewModel = accountViewModel(gateway)
        repeat(5) { viewModel.uiState.value }

        assertEquals(0, gateway.signInCalls)
        assertEquals(0, gateway.flowsStarted)
    }

    // ------------------------------------------------------------------------------ entrar

    @Test
    fun `login bem-sucedido leva a SignedIn com o uid do provider`() = runTest {
        val account = SparkAccount(uid = "uid-1", displayName = "João", email = "joao@example.com")
        val gateway = FakeAuthGateway().apply { nextOutcome = AuthOutcome.Success(account) }
        val viewModel = accountViewModel(gateway)

        viewModel.signIn(host)

        assertEquals(AuthState.SignedIn(account), viewModel.uiState.value.authState)
        assertEquals(account, viewModel.uiState.value.account)
    }

    @Test
    fun `cancelamento volta para SignedOut e nao vira erro`() = runTest {
        val gateway = FakeAuthGateway().apply { nextOutcome = AuthOutcome.Cancelled }
        val viewModel = accountViewModel(gateway)

        viewModel.signIn(host)

        assertEquals(AuthState.SignedOut, viewModel.uiState.value.authState)
        assertNull("fechar o seletor não é falha", viewModel.uiState.value.error)
    }

    @Test
    fun `falha de rede vira erro recuperavel, sem apagar nada`() = runTest {
        val gateway = FakeAuthGateway().apply {
            nextOutcome = AuthOutcome.Failure(AuthError.NETWORK)
        }
        val viewModel = accountViewModel(gateway)

        viewModel.signIn(host)

        assertEquals(AuthError.NETWORK, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSignedIn)

        // O usuário pode tentar de novo, e a segunda tentativa é um fluxo normal.
        gateway.nextOutcome = AuthOutcome.Success(FakeAuthGateway.DEFAULT_ACCOUNT)
        viewModel.signIn(host)
        assertTrue(viewModel.uiState.value.isSignedIn)
    }

    @Test
    fun `dez toques rapidos iniciam um unico fluxo de autenticacao`() = runTest {
        val gateway = FakeAuthGateway().apply { holdNextSignIn = true }
        val viewModel = accountViewModel(gateway)

        repeat(10) { viewModel.signIn(host) }

        assertEquals(AuthState.SigningIn, viewModel.uiState.value.authState)
        assertTrue(viewModel.uiState.value.isBusy)
        assertEquals("dez toques não podem abrir dez seletores", 1, gateway.flowsStarted)

        gateway.release(AuthOutcome.Success(FakeAuthGateway.DEFAULT_ACCOUNT))
        assertTrue(viewModel.uiState.value.isSignedIn)
        assertEquals(1, gateway.flowsStarted)
    }

    @Test
    fun `SigningIn e um estado explicito enquanto o fluxo esta aberto`() = runTest {
        val gateway = FakeAuthGateway().apply { holdNextSignIn = true }
        val viewModel = accountViewModel(gateway)

        viewModel.signIn(host)

        assertEquals(AuthState.SigningIn, viewModel.uiState.value.authState)

        gateway.release(AuthOutcome.Cancelled)
        assertEquals(AuthState.SignedOut, viewModel.uiState.value.authState)
    }

    @Test
    fun `sem configuracao a entrada de login e marcada como indisponivel`() = runTest {
        val viewModel = accountViewModel(FakeAuthGateway(isSignInAvailable = false))

        assertFalse(viewModel.uiState.value.isSignInAvailable)
    }

    // -------------------------------------------------------------------------------- sair

    @Test
    fun `logout leva a SignedOut`() = runTest {
        val gateway = FakeAuthGateway(initialAccount = FakeAuthGateway.DEFAULT_ACCOUNT)
        val viewModel = accountViewModel(gateway)

        viewModel.signOut()

        assertEquals(AuthState.SignedOut, viewModel.uiState.value.authState)
        assertEquals(1, gateway.signOutCalls)
    }

    @Test
    fun `sair nao inicia login e entrar de novo exige acao explicita`() = runTest {
        val gateway = FakeAuthGateway(initialAccount = FakeAuthGateway.DEFAULT_ACCOUNT)
        val viewModel = accountViewModel(gateway)

        viewModel.signOut()

        assertEquals(0, gateway.flowsStarted)
        assertEquals(AuthState.SignedOut, viewModel.uiState.value.authState)
    }

    @Test
    fun `troca de conta nao mistura identidades`() = runTest {
        val userA = SparkAccount(uid = "uid-A", displayName = "A")
        val userB = SparkAccount(uid = "uid-B", displayName = "B")
        val gateway = FakeAuthGateway(initialAccount = userA)
        val viewModel = accountViewModel(gateway)

        viewModel.signOut()
        gateway.nextOutcome = AuthOutcome.Success(userB)
        viewModel.signIn(host)

        assertEquals(userB, viewModel.uiState.value.account)
        assertEquals("uid-B", viewModel.uiState.value.account?.uid)
    }

    // ------------------------------------------------------------- verificação no servidor

    @Test
    fun `sem cliente de backend a verificacao reporta nao configurado`() = runTest {
        val viewModel = accountViewModel(FakeAuthGateway(initialAccount = FakeAuthGateway.DEFAULT_ACCOUNT))

        assertFalse(viewModel.canVerifyWithBackend)
        viewModel.verifyWithBackend()

        assertEquals(BackendIdentityCheck.NotConfigured, viewModel.uiState.value.backendCheck)
    }

    @Test
    fun `sair limpa uma verificacao anterior`() = runTest {
        val gateway = FakeAuthGateway(initialAccount = FakeAuthGateway.DEFAULT_ACCOUNT)
        val viewModel = accountViewModel(gateway)
        viewModel.verifyWithBackend()

        viewModel.signOut()

        assertEquals(BackendIdentityCheck.Idle, viewModel.uiState.value.backendCheck)
    }
}
