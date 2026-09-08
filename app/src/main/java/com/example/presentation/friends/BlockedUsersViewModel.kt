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
    val notice: String? = null
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

        _uiState.value = _uiState.value.copy(phase = BlockedUsersPhase.Loading, notice = null)
        val requestUid = currentUid

        viewModelScope.launch {
            when (val outcome = blockGateway.listBlockedUsers()) {
                is BlockOutcome.Success -> {
                    if (currentUid == requestUid) {
                        _uiState.value = _uiState.value.copy(
                            phase = BlockedUsersPhase.Ready(outcome.value)
                        )
                    }
                }
                is BlockOutcome.Failure -> {
                    if (currentUid == requestUid) {
                        val phase = if (outcome.error == BlockError.NETWORK) {
                            BlockedUsersPhase.Offline
                        } else {
                            BlockedUsersPhase.Error(outcome.error)
                        }
                        _uiState.value = _uiState.value.copy(phase = phase)
                    }
                }
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
