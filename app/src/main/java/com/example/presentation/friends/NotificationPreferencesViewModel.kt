package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.social.SocialNotificationGateway
import com.example.domain.social.SocialNotificationPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NotificationPreferencesUiState {
    data object Loading : NotificationPreferencesUiState
    data class Loaded(
        val preferences: SocialNotificationPreferences,
        val isUpdating: Boolean = false,
        val errorMessage: String? = null
    ) : NotificationPreferencesUiState
    data class Error(val message: String) : NotificationPreferencesUiState
}

class NotificationPreferencesViewModel(
    private val gateway: SocialNotificationGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow<NotificationPreferencesUiState>(NotificationPreferencesUiState.Loading)
    val uiState: StateFlow<NotificationPreferencesUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    fun loadPreferences() {
        viewModelScope.launch {
            _uiState.value = NotificationPreferencesUiState.Loading
            gateway.getPreferences()
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

        viewModelScope.launch {
            _uiState.value = current.copy(isUpdating = true, errorMessage = null)

            gateway.updatePreferences(
                pushEnabled = pushEnabled,
                friendRequestReceived = friendRequestReceived,
                friendRequestAccepted = friendRequestAccepted,
                challengeInvitationReceived = challengeInvitationReceived,
                challengeStartingSoon = challengeStartingSoon,
                challengeEnded = challengeEnded
            ).onSuccess { updated ->
                _uiState.value = NotificationPreferencesUiState.Loaded(
                    preferences = updated,
                    isUpdating = false
                )
            }.onFailure { error ->
                _uiState.value = current.copy(
                    isUpdating = false,
                    errorMessage = error.message ?: "Erro ao atualizar preferência"
                )
            }
        }
    }
}
