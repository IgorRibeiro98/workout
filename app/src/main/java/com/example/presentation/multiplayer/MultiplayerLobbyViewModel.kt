package com.example.presentation.multiplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.multiplayer.MultiplayerStartResult
import com.example.data.multiplayer.MultiplayerWorkoutStarter
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.multiplayer.MultiplayerError
import com.example.domain.multiplayer.MultiplayerGateway
import com.example.domain.multiplayer.MultiplayerInvitation
import com.example.domain.multiplayer.MultiplayerOutcome
import com.example.domain.multiplayer.MultiplayerRoom
import com.example.domain.multiplayer.MultiplayerRoomStatus
import com.example.domain.social.Friend
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MultiplayerLobbyUiState(
    val isSignedIn: Boolean = false,
    val isConfigured: Boolean = true,
    val isLoadingFriends: Boolean = false,
    val friends: List<Friend> = emptyList(),
    val friendsError: String? = null,
    val isLoadingInvitations: Boolean = false,
    val invitations: List<MultiplayerInvitation> = emptyList(),
    val invitationsError: String? = null,
    /** A sala que este aparelho criou nesta visita à tela (host), acompanhada por polling. */
    val createdRoom: MultiplayerRoom? = null,
    val isCreating: Boolean = false,
    val isStarting: Boolean = false,
    /** O nome do treino de hoje, quando ele pode ir para a sala; ver [templateBlockedReason]. */
    val templateName: String? = null,
    /** Por que o treino de hoje não pode ser levado (exercício personalizado, sem exercícios). */
    val templateBlockedReason: String? = null,
    val notice: String? = null
) {
    val peerHasJoined: Boolean
        get() = createdRoom?.status == MultiplayerRoomStatus.ACTIVE
}

sealed interface MultiplayerLobbyEvent {
    data object WorkoutStarted : MultiplayerLobbyEvent
}

/**
 * A tela de "Treinar em dupla à distância" (T19.5): convidar um amigo ou aceitar um convite.
 *
 * ## Escopo de conta
 *
 * Como toda tela social: o `uid` com que uma leitura começou é comparado ao atual quando ela
 * volta, e uma resposta da conta anterior é descartada. Trocar de conta zera o estado **antes** de
 * qualquer requisição da conta nova sair — e a sala criada pela conta anterior nunca reaparece.
 *
 * ## O que ela não faz
 *
 * Executar o treino. Ela cria/aceita a sala e pede ao [MultiplayerWorkoutStarter] a sessão local;
 * a partir daí a execução é a tela de sempre, e a coordenação é do `MultiplayerSessionCoordinator`.
 * Nada aqui toca Room diretamente.
 */
class MultiplayerLobbyViewModel(
    private val gateway: MultiplayerGateway,
    private val starter: MultiplayerWorkoutStarter,
    private val friendGateway: FriendGateway,
    private val authGateway: AuthGateway,
    private val roomPollIntervalMs: Long = 3_000L
) : ViewModel() {

    private val _uiState = MutableStateFlow(MultiplayerLobbyUiState(isConfigured = gateway.isConfigured))
    val uiState: StateFlow<MultiplayerLobbyUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MultiplayerLobbyEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MultiplayerLobbyEvent> = _events.asSharedFlow()

    private var currentUid: String? = null
    private var roomWatcher: Job? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid != currentUid) {
                    currentUid = newUid
                    roomWatcher?.cancel()
                    roomWatcher = null
                    _uiState.value = MultiplayerLobbyUiState(isSignedIn = newUid != null, isConfigured = gateway.isConfigured)
                    if (newUid != null) refresh()
                }
            }
        }
    }

    /** O treino de hoje, para a seção de convite: nome quando é portável, motivo quando não é. */
    fun loadTemplate(templateId: Long?) {
        if (templateId == null) return
        viewModelScope.launch {
            val result = starter.blueprintFor(templateId)
            _uiState.update {
                result.fold(
                    onSuccess = { blueprint -> it.copy(templateName = blueprint.name, templateBlockedReason = null) },
                    onFailure = { failure -> it.copy(templateName = null, templateBlockedReason = failure.message) }
                )
            }
        }
    }

    fun refresh() {
        val uid = currentUid ?: return
        if (!gateway.isConfigured) return
        _uiState.update { it.copy(isLoadingFriends = true, isLoadingInvitations = true, friendsError = null, invitationsError = null) }
        viewModelScope.launch {
            val friends = friendGateway.friends()
            if (currentUid != uid) return@launch
            _uiState.update {
                when (friends) {
                    is FriendOutcome.Success -> it.copy(isLoadingFriends = false, friends = friends.value.items)
                    is FriendOutcome.Failure -> it.copy(isLoadingFriends = false, friendsError = "Não foi possível carregar seus amigos.")
                }
            }
        }
        viewModelScope.launch {
            val invitations = gateway.listInvitations()
            if (currentUid != uid) return@launch
            _uiState.update {
                when (invitations) {
                    is MultiplayerOutcome.Success -> it.copy(isLoadingInvitations = false, invitations = invitations.data)
                    is MultiplayerOutcome.Failure -> it.copy(
                        isLoadingInvitations = false,
                        invitationsError = messageFor(invitations.error, "Não foi possível carregar os convites.")
                    )
                }
            }
        }
    }

    /** Host: cria a sala para o amigo com o treino de hoje. Um toque repetido durante a criação não faz nada. */
    fun createRoom(templateId: Long, inviteeSocialId: String) {
        val uid = currentUid ?: return
        if (_uiState.value.isCreating) return
        _uiState.update { it.copy(isCreating = true, notice = null) }
        viewModelScope.launch {
            val result = try {
                starter.createRoom(templateId, inviteeSocialId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MultiplayerStartResult.Blocked(listOf(e.message ?: "Não foi possível criar a sala."))
            }
            if (currentUid != uid) return@launch
            when (result) {
                is MultiplayerStartResult.Started -> {
                    _uiState.update { it.copy(isCreating = false, createdRoom = result.room) }
                    watchRoom(uid, result.room.roomId)
                }
                else -> _uiState.update { it.copy(isCreating = false, notice = noticeFor(result)) }
            }
        }
    }

    /** Host: inicia o treino local vinculado à sala criada — antes ou depois de o convidado entrar. */
    fun startAsHost(templateId: Long) {
        val uid = currentUid ?: return
        val room = _uiState.value.createdRoom ?: return
        if (_uiState.value.isStarting) return
        _uiState.update { it.copy(isStarting = true, notice = null) }
        viewModelScope.launch {
            val result = runStart { starter.startAsHost(room, templateId, uid) }
            if (currentUid != uid) return@launch
            settle(result)
        }
    }

    /** Convidado: aceita o convite, cria a cópia local do treino e inicia. */
    fun joinAndStart(roomId: String) {
        val uid = currentUid ?: return
        if (_uiState.value.isStarting) return
        _uiState.update { it.copy(isStarting = true, notice = null) }
        viewModelScope.launch {
            val result = runStart { starter.joinAndStart(roomId, uid) }
            if (currentUid != uid) return@launch
            settle(result)
        }
    }

    /** Host: cancela a sala que criou e ainda não começou. */
    fun cancelCreatedRoom() {
        val uid = currentUid ?: return
        val room = _uiState.value.createdRoom ?: return
        roomWatcher?.cancel()
        roomWatcher = null
        _uiState.update { it.copy(createdRoom = null) }
        viewModelScope.launch {
            gateway.close(room.roomId)
            if (currentUid == uid) refresh()
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private suspend fun runStart(block: suspend () -> MultiplayerStartResult): MultiplayerStartResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MultiplayerStartResult.Blocked(listOf(e.message ?: "Não foi possível iniciar o treino."))
        }

    private fun settle(result: MultiplayerStartResult) {
        when (result) {
            is MultiplayerStartResult.Started -> {
                roomWatcher?.cancel()
                roomWatcher = null
                _uiState.update { it.copy(isStarting = false, createdRoom = null) }
                _events.tryEmit(MultiplayerLobbyEvent.WorkoutStarted)
            }
            else -> _uiState.update { it.copy(isStarting = false, notice = noticeFor(result)) }
        }
    }

    /**
     * Enquanto a sala criada espera, a tela pergunta ao servidor de tempos em tempos se o
     * convidado entrou. É polling curto e só nesta tela: o long-poll é do treino, não do lobby.
     */
    private fun watchRoom(uid: String, roomId: String) {
        roomWatcher?.cancel()
        roomWatcher = viewModelScope.launch {
            while (isActive) {
                delay(roomPollIntervalMs)
                if (currentUid != uid) return@launch
                when (val outcome = gateway.getRoom(roomId)) {
                    is MultiplayerOutcome.Success -> {
                        _uiState.update { it.copy(createdRoom = outcome.data) }
                        if (!outcome.data.isOpen) {
                            _uiState.update {
                                it.copy(createdRoom = null, notice = "A sala foi encerrada.")
                            }
                            return@launch
                        }
                    }
                    is MultiplayerOutcome.Failure -> if (outcome.error.isTerminal) {
                        _uiState.update { it.copy(createdRoom = null, notice = messageFor(outcome.error, "A sala não está mais disponível.")) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun noticeFor(result: MultiplayerStartResult): String = when (result) {
        is MultiplayerStartResult.Started -> ""
        is MultiplayerStartResult.Blocked -> result.reasons.joinToString(" ")
        is MultiplayerStartResult.MissingExercises ->
            "Este treino usa exercícios que não estão no seu catálogo: ${result.missingCanonicalIds.joinToString(", ")}."
        MultiplayerStartResult.WorkoutAlreadyInProgress ->
            "Você já tem um treino em andamento. Conclua ou cancele antes de treinar em dupla."
        is MultiplayerStartResult.Rejected -> messageFor(result.error, "Não foi possível entrar na sala.")
    }

    private fun messageFor(error: MultiplayerError, fallback: String): String = when (error) {
        MultiplayerError.NOT_CONFIGURED -> "Treino em dupla à distância indisponível neste build."
        MultiplayerError.AUTH_REQUIRED -> "Entre na sua conta para treinar em dupla à distância."
        MultiplayerError.NETWORK -> "Sem conexão. Nada foi enviado."
        MultiplayerError.UNAVAILABLE -> "O servidor está indisponível no momento."
        MultiplayerError.RATE_LIMITED -> "Muitas tentativas. Aguarde um pouco."
        MultiplayerError.SOCIAL_NOT_ENABLED -> "Ative seu perfil social no Perfil para treinar em dupla à distância."
        MultiplayerError.FRIENDSHIP_REQUIRED -> "Só é possível treinar à distância com um amigo."
        MultiplayerError.CANNOT_INVITE_SELF -> "Não é possível convidar a si mesmo."
        MultiplayerError.ROOM_NOT_FOUND -> "Esta sala não está mais disponível."
        MultiplayerError.ROOM_CLOSED -> "Esta sala foi encerrada."
        MultiplayerError.ROOM_EXPIRED -> "Este convite expirou."
        MultiplayerError.MEMBER_LEFT -> "Você já saiu desta sala."
        MultiplayerError.NOT_A_MEMBER, MultiplayerError.NOT_HOST, MultiplayerError.INVALID_REQUEST, MultiplayerError.REJECTED -> fallback
    }
}
