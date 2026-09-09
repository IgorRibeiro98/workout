package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SocialNotificationGateway
import com.example.domain.social.SocialNotificationPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NotificationPreferencesUiState {
    data object Loading : NotificationPreferencesUiState
    /** Ninguém conectado: não há preferência de conta nenhuma para mostrar (T17.10 §103). */
    data object SignedOut : NotificationPreferencesUiState
    data class Loaded(
        val preferences: SocialNotificationPreferences,
        val isUpdating: Boolean = false,
        val errorMessage: String? = null
    ) : NotificationPreferencesUiState
    data class Error(val message: String) : NotificationPreferencesUiState
}

/**
 * As preferências de notificação da **conta ativa** (T17.5), com escopo de conta (T17.10 §103/§104).
 *
 * ## Por que a conta entrou aqui
 *
 * Esta era a única ViewModel social sem disciplina de troca de conta. Duas consequências, e as
 * duas apareceriam como "a conta B recebeu dados de A":
 *
 * 1. o estado carregado como A **permanecia na tela** depois de B entrar — e as preferências de
 *    notificação de outra pessoa são exatamente o tipo de configuração que alguém mudaria sem
 *    perceber que está mudando a de outra conta;
 * 2. uma resposta iniciada como A e concluída **depois** do login de B era escrita no estado de
 *    B. O toque seguinte enviaria, como B, os valores que vieram de A.
 *
 * A disciplina é a mesma do resto do social: limpar vem **antes** de a requisição da conta nova
 * sair, e toda resposta confere o `uid` de origem antes de tocar no estado.
 */
class NotificationPreferencesViewModel(
    private val gateway: SocialNotificationGateway,
    private val authGateway: AuthGateway,
    private val onPushEnabled: (suspend () -> Unit)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<NotificationPreferencesUiState>(NotificationPreferencesUiState.Loading)
    val uiState: StateFlow<NotificationPreferencesUiState> = _uiState.asStateFlow()

    private var observedUid: String? = null

    /**
     * A primeira emissão precisa ser processada mesmo quando o `uid` é `null`.
     *
     * Sem esta marca, `observedUid` nasceria `null` e a comparação `newUid == observedUid`
     * descartaria justamente a emissão que diz "ninguém conectado" — a tela ficaria em `Loading`
     * para sempre em vez de dizer que não há conta.
     */
    private var hasObservedSession = false

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (hasObservedSession && newUid == observedUid) return@collect
                hasObservedSession = true
                observedUid = newUid

                // Limpar primeiro, sempre: a preferência da conta anterior não pode ficar na tela
                // enquanto a leitura da conta nova corre.
                _uiState.value = if (newUid == null) {
                    NotificationPreferencesUiState.SignedOut
                } else {
                    NotificationPreferencesUiState.Loading
                }
                if (newUid != null) loadPreferences()
            }
        }
    }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid

    fun loadPreferences() {
        val uid = currentUid() ?: run {
            _uiState.value = NotificationPreferencesUiState.SignedOut
            return
        }
        viewModelScope.launch {
            _uiState.value = NotificationPreferencesUiState.Loading
            val result = gateway.getPreferences()
            // A resposta de uma requisição da conta anterior é descartada (§104).
            if (currentUid() != uid) return@launch
            result
                .onSuccess { prefs ->
                    _uiState.value = NotificationPreferencesUiState.Loaded(preferences = prefs)
                }
                .onFailure { error ->
                    _uiState.value = NotificationPreferencesUiState.Error(
                        error.message ?: "Não foi possível carregar as preferências"
                    )
                }
        }
    }

    fun onPermissionDenied(message: String) {
        val current = (_uiState.value as? NotificationPreferencesUiState.Loaded) ?: return
        _uiState.value = current.copy(
            errorMessage = message,
            isUpdating = false
        )
    }

    fun togglePushEnabled(enabled: Boolean) {
        updatePreferences(pushEnabled = enabled)
    }

    fun toggleFriendRequestReceived(enabled: Boolean) {
        updatePreferences(friendRequestReceived = enabled)
    }

    fun toggleFriendRequestAccepted(enabled: Boolean) {
        updatePreferences(friendRequestAccepted = enabled)
    }

    fun toggleChallengeInvitationReceived(enabled: Boolean) {
        updatePreferences(challengeInvitationReceived = enabled)
    }

    fun toggleChallengeStartingSoon(enabled: Boolean) {
        updatePreferences(challengeStartingSoon = enabled)
    }

    fun toggleChallengeEnded(enabled: Boolean) {
        updatePreferences(challengeEnded = enabled)
    }

    private fun updatePreferences(
        pushEnabled: Boolean? = null,
        friendRequestReceived: Boolean? = null,
        friendRequestAccepted: Boolean? = null,
        challengeInvitationReceived: Boolean? = null,
        challengeStartingSoon: Boolean? = null,
        challengeEnded: Boolean? = null
    ) {
        val current = (_uiState.value as? NotificationPreferencesUiState.Loaded) ?: return
        val uid = currentUid() ?: run {
            _uiState.value = NotificationPreferencesUiState.SignedOut
            return
        }

        viewModelScope.launch {
            _uiState.value = current.copy(isUpdating = true, errorMessage = null)

            val result = gateway.updatePreferences(
                pushEnabled = pushEnabled,
                friendRequestReceived = friendRequestReceived,
                friendRequestAccepted = friendRequestAccepted,
                challengeInvitationReceived = challengeInvitationReceived,
                challengeStartingSoon = challengeStartingSoon,
                challengeEnded = challengeEnded
            )
            if (currentUid() != uid) return@launch
            result.onSuccess { updated ->
                _uiState.value = NotificationPreferencesUiState.Loaded(
                    preferences = updated,
                    isUpdating = false
                )
                if (pushEnabled == true && updated.pushEnabled) {
                    onPushEnabled?.invoke()
                }
            }.onFailure { error ->
                _uiState.value = current.copy(
                    isUpdating = false,
                    errorMessage = error.message ?: "Erro ao atualizar preferência"
                )
            }
        }
    }
}
