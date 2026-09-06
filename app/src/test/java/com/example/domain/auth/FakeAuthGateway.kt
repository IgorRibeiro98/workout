package com.example.domain.auth

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dublê do [AuthGateway] para os testes.
 *
 * Existe porque a autenticação real abre o seletor de contas do Google: sem ele, nenhum teste de
 * ViewModel ou de tela poderia rodar offline, no CI, sem conta real e sem projeto Firebase.
 *
 * Vive em `src/test` — não há como o app de produção usá-lo, e não existe flag que o habilite.
 *
 * Como o gateway real, ele **não conhece Room, DAO nem repositório**: é essa ausência que os
 * testes de isolamento de dados locais exercitam.
 */
class FakeAuthGateway(
    initialAccount: SparkAccount? = null,
    override val isSignInAvailable: Boolean = true
) : AuthGateway, AuthTokenProvider {

    private val _state = MutableStateFlow<AuthState>(
        initialAccount?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut
    )
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Quantas vezes `signIn` foi chamado, inclusive as chamadas recusadas por concorrência. */
    var signInCalls: Int = 0
        private set

    /** Quantos fluxos de autenticação **realmente** começaram (seletor de contas aberto). */
    var flowsStarted: Int = 0
        private set

    var signOutCalls: Int = 0
        private set

    /** O desfecho da próxima autenticação, quando não estiver usando [holdNextSignIn]. */
    var nextOutcome: AuthOutcome = AuthOutcome.Success(DEFAULT_ACCOUNT)

    /** `true` faz `signIn` ficar suspenso até [release] — é como se testa toque duplicado. */
    var holdNextSignIn: Boolean = false

    /** O resultado que o provider de token devolve enquanto há conta conectada. */
    var tokenResult: AuthTokenResult? = null

    private val inProgress = AtomicBoolean(false)
    private var pending: CompletableDeferred<AuthOutcome>? = null

    override suspend fun signIn(host: Context): AuthOutcome {
        signInCalls++
        if (!inProgress.compareAndSet(false, true)) {
            return AuthOutcome.AlreadyInProgress
        }

        return try {
            flowsStarted++
            _state.value = AuthState.SigningIn
            val outcome = if (holdNextSignIn) {
                CompletableDeferred<AuthOutcome>().also { pending = it }.await()
            } else {
                nextOutcome
            }
            settle(outcome)
            outcome
        } finally {
            pending = null
            inProgress.set(false)
        }
    }

    /** Encerra um `signIn` que estava suspenso, com o desfecho informado. */
    fun release(outcome: AuthOutcome) {
        pending?.complete(outcome)
    }

    override suspend fun signOut() {
        signOutCalls++
        _state.value = AuthState.SigningOut
        _state.value = AuthState.SignedOut
    }

    override suspend fun currentToken(forceRefresh: Boolean): AuthTokenResult {
        tokenResult?.let { return it }
        return when (val current = _state.value) {
            is AuthState.SignedIn -> AuthTokenResult.Token("id-token-de-${current.account.uid}")
            else -> AuthTokenResult.SignedOut
        }
    }

    private fun settle(outcome: AuthOutcome) {
        _state.value = when (outcome) {
            is AuthOutcome.Success -> AuthState.SignedIn(outcome.account)
            is AuthOutcome.Failure -> AuthState.Error(outcome.error)
            // Cancelar é uma escolha do usuário, não um erro.
            is AuthOutcome.Cancelled -> AuthState.SignedOut
            is AuthOutcome.AlreadyInProgress -> _state.value
        }
    }

    companion object {
        val DEFAULT_ACCOUNT = SparkAccount(
            uid = "uid-atleta",
            displayName = "João",
            email = "joao@example.com",
            photoUrl = null
        )
    }
}
