package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.BlockError
import com.example.domain.social.BlockGateway
import com.example.domain.social.BlockOutcome
import com.example.domain.social.BlockedUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BlockedUsersPhase {
    data object Idle : BlockedUsersPhase
    data object Loading : BlockedUsersPhase
    data class Ready(val users: List<BlockedUser>) : BlockedUsersPhase
    data object Offline : BlockedUsersPhase
    data class Error(val error: BlockError) : BlockedUsersPhase
}

data class BlockedUsersUiState(
    val phase: BlockedUsersPhase = BlockedUsersPhase.Idle,
    val pendingUnblockSocialIds: Set<String> = emptySet(),
    val notice: String? = null,
    /** O "↻" está relendo, com a lista na tela (T19.H3). */
    val isRefreshing: Boolean = false
)

/**
 * ViewModel da tela de usuários bloqueados (T17.6).
 *
 * Gerencia a lista e ação de desbloqueio.
 * Invalida na troca de conta e não retém estado entre sessões.
 */
class BlockedUsersViewModel(
    private val blockGateway: BlockGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockedUsersUiState())
    val uiState: StateFlow<BlockedUsersUiState> = _uiState.asStateFlow()

    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid != currentUid) {
                    currentUid = newUid
                    _uiState.value = BlockedUsersUiState()
                }
            }
        }
    }

    fun open() {
        if (_uiState.value.phase is BlockedUsersPhase.Idle) {
            refresh()
        }
    }

    fun refresh() {
        if (!blockGateway.isConfigured) {
            _uiState.value = _uiState.value.copy(
                phase = BlockedUsersPhase.Error(BlockError.NOT_CONFIGURED)
            )
            return
        }

        // Sem conta não há lista de ninguém para ler (antes da T19.H3 a leitura saía com `null`).
        val requestUid = currentUid ?: return
        val state = _uiState.value
        // Um toque durante uma leitura em voo não abre outra.
        if (state.phase is BlockedUsersPhase.Loading || state.isRefreshing) return

        // Com a lista na tela, ela fica enquanto a releitura voa (T19.H3 §45).
        val showing = state.phase is BlockedUsersPhase.Ready
        _uiState.value = if (showing) {
            state.copy(isRefreshing = true, notice = null)
        } else {
            state.copy(phase = BlockedUsersPhase.Loading, notice = null)
        }

        viewModelScope.launch {
            val outcome = blockGateway.listBlockedUsers()
            if (currentUid != requestUid) return@launch
            _uiState.value = when {
                outcome is BlockOutcome.Success -> _uiState.value.copy(
                    isRefreshing = false,
                    phase = BlockedUsersPhase.Ready(outcome.value)
                )
                showing && (outcome as BlockOutcome.Failure).error.let {
                    it == BlockError.NETWORK || it == BlockError.UNAVAILABLE ||
                        it == BlockError.RATE_LIMITED
                } -> _uiState.value.copy(
                    isRefreshing = false,
                    notice = "Não foi possível atualizar agora — mostrando a última atualização."
                )
                else -> _uiState.value.copy(
                    isRefreshing = false,
                    phase = if ((outcome as BlockOutcome.Failure).error == BlockError.NETWORK) {
                        BlockedUsersPhase.Offline
                    } else {
                        BlockedUsersPhase.Error(outcome.error)
                    }
                )
            }
        }
    }

    fun unblockUser(socialId: String) {
        if (socialId in _uiState.value.pendingUnblockSocialIds) return

        _uiState.value = _uiState.value.copy(
            pendingUnblockSocialIds = _uiState.value.pendingUnblockSocialIds + socialId,
            notice = null
        )
        val requestUid = currentUid

        viewModelScope.launch {
            val outcome = blockGateway.unblockUser(socialId)
            if (currentUid != requestUid) return@launch

            _uiState.value = _uiState.value.copy(
                pendingUnblockSocialIds = _uiState.value.pendingUnblockSocialIds - socialId
            )

            when (outcome) {
                is BlockOutcome.Success -> {
                    val currentPhase = _uiState.value.phase
                    if (currentPhase is BlockedUsersPhase.Ready) {
                        _uiState.value = _uiState.value.copy(
                            phase = BlockedUsersPhase.Ready(
                                currentPhase.users.filterNot { it.socialId == socialId }
                            ),
                            notice = "Usuário desbloqueado."
                        )
                    }
                }
                is BlockOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        notice = "Não foi possível desbloquear o usuário."
                    )
                }
            }
        }
    }
}
