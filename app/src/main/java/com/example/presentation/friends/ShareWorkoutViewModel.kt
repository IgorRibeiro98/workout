package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.social.Friend
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendOutcome
import com.example.domain.social.WorkoutShareContent
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareError
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
 * O estado do diálogo "Compartilhar treino" (T17.7) — e "Compartilhar programa" (T19.3), que é o
 * mesmo diálogo com outro conteúdo.
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

    /**
     * Envia o conteúdo — um treino ou um programa inteiro — ao amigo selecionado. Um toque
     * repetido durante o envio não faz nada; um toque depois de uma falha é uma tentativa nova,
     * com `clientRequestId` novo.
     */
    fun share(content: WorkoutShareContent) {
        val state = _uiState.value
        val target = state.selectedFriendId ?: return
        if (state.isSending) return
        _uiState.update { it.copy(isSending = true, errorMessage = null) }
        viewModelScope.launch {
            val outcome = shareGateway.createShare(
                recipientSocialId = target,
                clientRequestId = UUID.randomUUID().toString(),
                content = content
            )
            when (outcome) {
                is WorkoutShareOutcome.Success ->
                    _uiState.update { it.copy(isSending = false, isSent = true) }
                is WorkoutShareOutcome.Failure ->
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            errorMessage = shareFailureMessage(content, outcome.error)
                        )
                    }
            }
        }
    }

    /** Devolve o diálogo ao estado inicial quando ele é fechado e reaberto. */
    fun reset() {
        _uiState.value = ShareWorkoutUiState()
    }

    private fun shareFailureMessage(content: WorkoutShareContent, error: WorkoutShareError): String {
        val what = if (content is WorkoutShareContent.Program) "programa" else "treino"
        return when (error) {
            WorkoutShareError.NETWORK -> "Sem conexão. O $what não foi enviado — tente novamente com internet."
            WorkoutShareError.AUTH_REQUIRED -> "Entre na sua Conta Spark para compartilhar."
            WorkoutShareError.SOCIAL_NOT_ENABLED -> "Ative o Social no seu perfil para compartilhar."
            WorkoutShareError.FRIENDSHIP_REQUIRED -> "Só é possível compartilhar com amigos."
            WorkoutShareError.BLOCKED_USER -> "Não é possível compartilhar com este usuário."
            WorkoutShareError.RATE_LIMITED -> "Você atingiu o limite de compartilhamentos por agora. Tente mais tarde."
            WorkoutShareError.INVALID_SNAPSHOT -> "O $what não pôde ser compartilhado: o servidor recusou o conteúdo."
            else -> "Falha ao enviar $what. Tente novamente."
        }
    }
}
