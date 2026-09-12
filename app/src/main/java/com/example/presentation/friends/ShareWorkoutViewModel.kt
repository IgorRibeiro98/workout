package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.social.Friend
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendOutcome
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ShareWorkoutUiState(
    val isLoadingFriends: Boolean = true,
    val friends: List<Friend> = emptyList(),
    val selectedFriendId: String? = null,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    /** Vira verdadeiro uma vez, quando o servidor confirmou o envio. */
    val isSent: Boolean = false
)

/**
 * O estado do diálogo "Compartilhar treino" (T17.7).
 *
 * Ele existe porque o diálogo carregava a lista de amigos num `LaunchedEffect` e enviava o treino
 * num `rememberCoroutineScope`: fechar o diálogo (ou uma mudança de configuração) cancelava a
 * requisição em voo, e o resultado nunca chegava. Pior, uma falha ao **listar** amigos só
 * desligava o carregando — quem estava sem internet lia "Você ainda não possui amigos
 * adicionados.", que é uma afirmação falsa sobre a conta dele.
 *
 * A fronteira do social continua a mesma: aqui não há Room, DAO, Outbox nem HTTP — só os gateways.
 */
class ShareWorkoutViewModel(
    private val friendGateway: FriendGateway,
    private val shareGateway: WorkoutShareGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareWorkoutUiState())
    val uiState: StateFlow<ShareWorkoutUiState> = _uiState.asStateFlow()

    /** Carrega a lista de amigos. Chamar de novo recomeça do zero — é o "tentar de novo". */
    fun loadFriends() {
        _uiState.update { it.copy(isLoadingFriends = true, errorMessage = null) }
        viewModelScope.launch {
            when (val outcome = friendGateway.friends()) {
                is FriendOutcome.Success -> _uiState.update {
                    it.copy(isLoadingFriends = false, friends = outcome.value.items)
                }
                is FriendOutcome.Failure -> _uiState.update {
                    // Falhar ao listar não é "não tenho amigos": a diferença é o que separa
                    // "tente de novo" de "vá adicionar alguém".
                    it.copy(isLoadingFriends = false, errorMessage = messageFor(outcome.error))
                }
            }
        }
    }

    fun selectFriend(socialId: String) {
        _uiState.update { it.copy(selectedFriendId = socialId) }
    }

    /** Envia o snapshot ao amigo selecionado. Um toque repetido durante o envio não faz nada. */
    fun share(snapshot: SharedWorkoutSnapshot) {
        val state = _uiState.value
        val target = state.selectedFriendId ?: return
        if (state.isSending) return
        _uiState.update { it.copy(isSending = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = shareGateway.createShare(
                recipientSocialId = target,
                clientRequestId = UUID.randomUUID().toString(),
                snapshot = snapshot
            )
            when (outcome) {
                is WorkoutShareOutcome.Success ->
                    _uiState.update { it.copy(isSending = false, isSent = true) }
                is WorkoutShareOutcome.Failure ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            errorMessage = "Falha ao enviar treino. Tente novamente."
                        )
                    }
            }
        }
    }

    /** Devolve o diálogo ao estado inicial quando ele é fechado e reaberto. */
    fun reset() {
        _uiState.value = ShareWorkoutUiState()
    }
}
