package com.example.presentation.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkBackendResult
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A conta Spark, do ponto de vista da UI.
 *
 * ```text
 * ProfileScreen -> AccountViewModel -> AuthGateway -> FirebaseAuth + Credential Manager
 * ```
 *
 * A tela não conhece `FirebaseAuth`, `CredentialManager` nem `GoogleIdTokenCredential`: ela emite
 * intenções e renderiza [AccountUiState].
 *
 * Nenhuma autenticação começa sozinha. Não há chamada em `init`, em recomposição ou ao abrir a
 * tela — a mesma regra de custo que o Coach IA segue (PROJECT_RULES §13). O que acontece ao
 * observar o estado é o oposto disso: uma sessão que **já existe** é refletida automaticamente.
 */
class AccountViewModel(
    private val authGateway: AuthGateway,
    /** `null` quando não há backend configurado neste build. */
    private val backendClient: SparkBackendClient? = null,
    /** Gateway de exclusão de conta (T17.6). */
    private val accountDeletionGateway: com.example.domain.account.AccountDeletionGateway? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AccountUiState(isSignInAvailable = authGateway.isSignInAvailable)
    )
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    /** `true` quando existe endereço de backend para a verificação de ponta a ponta. */
    val canVerifyWithBackend: Boolean get() = backendClient?.isConfigured == true

    init {
        viewModelScope.launch {
            // Observar o Firebase Auth restaura a sessão existente sozinho. Observar não abre
            // seletor de contas: isso só acontece em `signIn`.
            authGateway.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    authState = state,
                    isSignInAvailable = authGateway.isSignInAvailable,
                    backendCheck = if (state is AuthState.SignedIn) {
                        _uiState.value.backendCheck
                    } else {
                        // Trocou de conta ou saiu: uma verificação antiga não descreve mais nada.
                        BackendIdentityCheck.Idle
                    }
                )
            }
        }
    }

    /**
     * "Continuar com Google". Só a partir de toque explícito.
     *
     * Toque repetido enquanto o fluxo está em andamento é ignorado aqui e, de novo, no gateway:
     * dez toques rápidos abrem um seletor de contas, não dez.
     */
    fun signIn(host: Context) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch { authGateway.signIn(host) }
    }

    /**
     * "Sair da conta".
     *
     * Desconecta a identidade online e limpa o estado de credencial. Não apaga treino, sessão,
     * histórico, medida, preferência ou gamificação — nada disso é acessível a partir daqui.
     */
    fun signOut() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch { authGateway.signOut() }
    }

    /**
     * Pergunta ao Spark Backend qual uid ele deriva do token — a validação de ponta a ponta.
     *
     * O resultado nunca muda o estado da conta: um backend fora do ar produz
     * [BackendIdentityCheck.Unavailable] e o usuário continua [AuthState.SignedIn].
     */
    fun verifyWithBackend() {
        val client = backendClient ?: run {
            _uiState.value = _uiState.value.copy(backendCheck = BackendIdentityCheck.NotConfigured)
            return
        }
        val localUid = _uiState.value.account?.uid ?: return
        if (_uiState.value.backendCheck is BackendIdentityCheck.Running) return

        _uiState.value = _uiState.value.copy(backendCheck = BackendIdentityCheck.Running)
        viewModelScope.launch {
            val check = when (val result = client.me()) {
                is SparkBackendResult.Success ->
                    if (result.value == localUid) {
                        BackendIdentityCheck.Verified(result.value)
                    } else {
                        BackendIdentityCheck.Diverged(result.value)
                    }
                is SparkBackendResult.Unauthenticated -> BackendIdentityCheck.Unauthenticated
                is SparkBackendResult.Unavailable -> BackendIdentityCheck.Unavailable
                is SparkBackendResult.NotConfigured -> BackendIdentityCheck.NotConfigured
                is SparkBackendResult.Failure -> BackendIdentityCheck.Failed(result.status)
            }
            _uiState.value = _uiState.value.copy(backendCheck = check)
        }
    }

    /**
     * Exclusão de Conta Spark (T17.6).
     *
     * Remove todos os dados na nuvem (backups, perfil social, amigos, desafios).
     * Preserva dados locais de treinos no Room (local-first).
     */
    fun deleteAccount(
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val gateway = accountDeletionGateway ?: run {
            val err = "Exclusão de conta não disponível neste build."
            _uiState.value = _uiState.value.copy(deletionError = err)
            onError(err)
            return
        }
        if (_uiState.value.isBusy) return

        _uiState.value = _uiState.value.copy(isDeletingAccount = true, deletionError = null)
        viewModelScope.launch {
            when (val outcome = gateway.deleteAccount()) {
                is com.example.domain.account.AccountDeletionOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(isDeletingAccount = false, deletionError = null)
                    onSuccess()
                }
                is com.example.domain.account.AccountDeletionOutcome.Failure -> {
                    val err = when (outcome.error) {
                        com.example.domain.account.AccountDeletionError.NOT_CONFIGURED ->
                            "Servidor não configurado."
                        com.example.domain.account.AccountDeletionError.AUTH_REQUIRED ->
                            "Sessão expirada. Entre novamente para excluir."
                        com.example.domain.account.AccountDeletionError.RATE_LIMITED ->
                            "Muitas tentativas. Tente mais tarde."
                        com.example.domain.account.AccountDeletionError.NETWORK ->
                            "Sem conexão com a internet."
                        com.example.domain.account.AccountDeletionError.UNAVAILABLE ->
                            "Servidor indisponível no momento."
                        com.example.domain.account.AccountDeletionError.REJECTED ->
                            "Não foi possível excluir a conta agora."
                    }
                    _uiState.value = _uiState.value.copy(isDeletingAccount = false, deletionError = err)
                    onError(err)
                }
            }
        }
    }
}
