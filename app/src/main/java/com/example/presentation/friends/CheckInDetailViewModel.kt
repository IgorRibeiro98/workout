package com.example.presentation.friends

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.media.SocialMediaCache
import com.example.data.social.WorkoutCheckInContract
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.CheckInComment
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialReportTarget
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A fase de leitura do detalhe. */
sealed interface CheckInDetailPhase {
    data object Loading : CheckInDetailPhase

    data class Success(
        val checkIn: WorkoutCheckIn,
        val comments: List<CheckInComment>
    ) : CheckInDetailPhase

    /** Sem rede. O núcleo do Spark continua funcionando. */
    data object Offline : CheckInDetailPhase

    data object SignedOut : CheckInDetailPhase

    /**
     * A publicação não existe mais, ou o viewer perdeu o acesso a ela.
     *
     * Uma resposta só para "foi excluída", "vocês deixaram de ser amigos", "há bloqueio" e "o autor
     * desativou o Social" — o servidor não distingue os quatro (§51), e a tela não inventa uma
     * distinção que ela não tem como saber.
     */
    data object Unavailable : CheckInDetailPhase

    data class Error(val message: String) : CheckInDetailPhase
}

data class CheckInDetailUiState(
    val checkInId: String = "",
    val phase: CheckInDetailPhase = CheckInDetailPhase.Loading,
    val photo: ImageBitmap? = null,
    val draft: String = "",
    val isSendingComment: Boolean = false,
    val isReacting: Boolean = false,
    /** O comentário cuja exclusão está em andamento. Ocupação **por alvo**. */
    val deletingCommentId: String? = null,
    val notice: String? = null
) {
    val draftRemaining: Int
        get() = WorkoutCheckInContract.Limits.MAX_COMMENT_LENGTH -
            draft.codePointCount(0, draft.length)

    /** `true` quando o comentário tem conteúdo e cabe no limite. */
    val canSendComment: Boolean
        get() = !isSendingComment && draft.isNotBlank() && draftRemaining >= 0
}

/**
 * O detalhe de um check-in: foto, legenda, reações e comentários (T17.9 §118).
 *
 * ## Por que ela existe além do Feed
 *
 * Porque comentários são uma conversa, e conversa não cabe em um card de lista: o Feed mostra
 * quantos existem, e esta tela mostra quais são. **Não** é um segundo tipo de publicação (§5): ela
 * lê o mesmo agregado, pela mesma rota, com a mesma política de visibilidade — o servidor devolve
 * `404` para quem não pode ver, e essa é a única autorização que existe (§129).
 *
 * ## O que ela guarda
 *
 * Memória, e só memória — como o Feed (T17.8 §111, T17.9 §56). Nada de Room, nada de DataStore,
 * nada de cache em disco. A foto vem do [SocialMediaCache], que é account-scoped e limpo **antes**
 * de a primeira requisição da conta nova sair (§57/§145).
 *
 * ## Reação é otimista; comentário não é
 *
 * A reação é reversível e barata, então a tela responde na hora e reconcilia com a resposta do
 * servidor (§121). O comentário espera o servidor (§122): ele carrega texto que a pessoa escreveu,
 * e um que aparece e some faz quem escreveu acreditar que a outra pessoa leu.
 */
class CheckInDetailViewModel(
    private val gateway: WorkoutCheckInGateway,
    private val authGateway: AuthGateway,
    private val mediaCache: SocialMediaCache? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckInDetailUiState())
    val uiState: StateFlow<CheckInDetailUiState> = _uiState.asStateFlow()

    private var observedUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid == observedUid) return@collect
                observedUid = newUid

                // Limpar vem primeiro, sempre — inclusive a foto (§57/§145).
                mediaCache?.switchAccount(newUid)
                val checkInId = _uiState.value.checkInId
                _uiState.value = CheckInDetailUiState(
                    checkInId = checkInId,
                    phase = if (newUid == null) {
                        CheckInDetailPhase.SignedOut
                    } else {
                        CheckInDetailPhase.Loading
                    }
                )
                if (newUid != null && checkInId.isNotBlank()) load(checkInId, newUid)
            }
        }
    }

    /** Abrir a tela busca a publicação e os comentários. */
    fun open(checkInId: String) {
        val uid = currentUid() ?: run {
            _uiState.value = CheckInDetailUiState(
                checkInId = checkInId,
                phase = CheckInDetailPhase.SignedOut
            )
            return
        }
        if (_uiState.value.checkInId != checkInId) {
            _uiState.value = CheckInDetailUiState(checkInId = checkInId)
        }
        load(checkInId, uid)
    }

    fun refresh() {
        val uid = currentUid() ?: return
        val checkInId = _uiState.value.checkInId.takeIf { it.isNotBlank() } ?: return
        load(checkInId, uid)
    }

    /** Reagir, trocar ou remover (§120/§121). Tocar na reação atual de novo a remove. */
    fun toggleReaction(type: ReactionType) {
        val uid = currentUid() ?: return
        val phase = _uiState.value.phase as? CheckInDetailPhase.Success ?: return
        if (_uiState.value.isReacting) return

        val before = phase.checkIn
        val removing = before.currentUserReaction == type

        _uiState.update {
            it.copy(
                isReacting = true,
                phase = phase.copy(checkIn = optimistic(before, type, removing))
            )
        }

        viewModelScope.launch {
            val outcome = if (removing) {
                gateway.removeReaction(before.checkInId)
            } else {
                gateway.putReaction(before.checkInId, type)
            }
            if (currentUid() != uid) return@launch

            _uiState.update { state ->
                val current = state.phase as? CheckInDetailPhase.Success ?: return@update state
                when (outcome) {
                    is WorkoutCheckInOutcome.Success ->
                        state.copy(isReacting = false, phase = current.copy(checkIn = outcome.data))

                    is WorkoutCheckInOutcome.Failure -> state.copy(
                        isReacting = false,
                        // Rollback para o estado que veio do servidor (§121).
                        phase = current.copy(checkIn = before),
                        notice = when (outcome.error) {
                            WorkoutCheckInError.NETWORK ->
                                "Sem conexão. Sua reação não foi registrada."
                            WorkoutCheckInError.CHECKIN_NOT_FOUND ->
                                "Esta publicação não está mais disponível."
                            else -> "Não foi possível registrar sua reação agora."
                        }
                    )
                }
            }
        }
    }

    fun onDraftChanged(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    /**
     * Envia o comentário (§81/§122).
     *
     * Server-authoritative: a lista só recebe o item **depois** da resposta, com o identificador
     * que o servidor gerou. É o oposto da reação de propósito.
     */
    fun sendComment() {
        val uid = currentUid() ?: return
        val state = _uiState.value
        val phase = state.phase as? CheckInDetailPhase.Success ?: return
        if (!state.canSendComment) return

        val body = state.draft.trim()
        _uiState.update { it.copy(isSendingComment = true) }

        viewModelScope.launch {
            val outcome = gateway.createComment(phase.checkIn.checkInId, body)
            if (currentUid() != uid) return@launch

            when (outcome) {
                is WorkoutCheckInOutcome.Success -> _uiState.update { current ->
                    val success = current.phase as? CheckInDetailPhase.Success
                        ?: return@update current.copy(isSendingComment = false, draft = "")
                    current.copy(
                        isSendingComment = false,
                        draft = "",
                        phase = success.copy(
                            comments = success.comments + outcome.data,
                            checkIn = success.checkIn.copy(
                                commentCount = success.checkIn.commentCount + 1
                            )
                        )
                    )
                }

                is WorkoutCheckInOutcome.Failure -> _uiState.update {
                    it.copy(
                        isSendingComment = false,
                        // O rascunho **permanece**: perder o que a pessoa escreveu porque a rede
                        // caiu é o pior desfecho desta tela.
                        notice = when (outcome.error) {
                            WorkoutCheckInError.NETWORK ->
                                "Sem conexão. Seu comentário não foi enviado."
                            WorkoutCheckInError.INVALID_CONTENT ->
                                "Esse comentário tem caracteres que não podemos publicar."
                            WorkoutCheckInError.RATE_LIMITED ->
                                "Muitos comentários seguidos. Tente em instantes."
                            WorkoutCheckInError.CHECKIN_NOT_FOUND ->
                                "Esta publicação não está mais disponível."
                            else -> "Não foi possível enviar seu comentário agora."
                        }
                    )
                }
            }
        }
    }

    /** Apaga um comentário (§93/§94). O servidor recusa quem não pode — `canDelete` é só a tela. */
    fun deleteComment(commentId: String) {
        val uid = currentUid() ?: return
        val phase = _uiState.value.phase as? CheckInDetailPhase.Success ?: return
        if (_uiState.value.deletingCommentId != null) return

        _uiState.update { it.copy(deletingCommentId = commentId) }

        viewModelScope.launch {
            val outcome = gateway.deleteComment(phase.checkIn.checkInId, commentId)
            if (currentUid() != uid) return@launch

            _uiState.update { state ->
                val current = state.phase as? CheckInDetailPhase.Success
                    ?: return@update state.copy(deletingCommentId = null)
                when (outcome) {
                    is WorkoutCheckInOutcome.Success -> state.copy(
                        deletingCommentId = null,
                        phase = current.copy(
                            comments = current.comments.filterNot { it.commentId == commentId },
                            checkIn = current.checkIn.copy(
                                commentCount = (current.checkIn.commentCount - 1).coerceAtLeast(0)
                            )
                        )
                    )

                    is WorkoutCheckInOutcome.Failure -> state.copy(
                        deletingCommentId = null,
                        notice = when (outcome.error) {
                            WorkoutCheckInError.NETWORK ->
                                "Sem conexão. O comentário não foi apagado."
                            else -> "Não foi possível apagar o comentário agora."
                        }
                    )
                }
            }
        }
    }

    /** Denuncia a publicação ou um comentário (§101–§106). O servidor resolve o autor (§103). */
    fun report(target: SocialReportTarget, targetId: String, reason: String) {
        val uid = currentUid() ?: return

        viewModelScope.launch {
            val outcome = gateway.reportContent(target, targetId, reason)
            if (currentUid() != uid) return@launch

            _uiState.update {
                it.copy(
                    notice = when (outcome) {
                        is WorkoutCheckInOutcome.Success ->
                            "Denúncia enviada. Nossa equipe vai revisar."
                        is WorkoutCheckInOutcome.Failure -> when (outcome.error) {
                            WorkoutCheckInError.NETWORK ->
                                "Sem conexão. A denúncia não foi enviada."
                            WorkoutCheckInError.RATE_LIMITED ->
                                "Você já enviou muitas denúncias hoje."
                            else -> "Não foi possível enviar a denúncia agora."
                        }
                    }
                )
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------ internas

    private fun load(checkInId: String, expectedUid: String) {
        viewModelScope.launch {
            val checkIn = gateway.checkIn(checkInId)
            if (currentUid() != expectedUid) return@launch

            when (checkIn) {
                is WorkoutCheckInOutcome.Success -> {
                    val comments = gateway.comments(checkInId)
                    if (currentUid() != expectedUid) return@launch

                    val items = (comments as? WorkoutCheckInOutcome.Success)?.data ?: emptyList()
                    _uiState.update {
                        it.copy(
                            checkInId = checkInId,
                            phase = CheckInDetailPhase.Success(checkIn.data, items)
                        )
                    }
                    checkIn.data.media?.let { media -> loadPhoto(media.mediaId, expectedUid) }
                }

                is WorkoutCheckInOutcome.Failure -> _uiState.update {
                    it.copy(
                        checkInId = checkInId,
                        phase = when (checkIn.error) {
                            WorkoutCheckInError.NETWORK -> CheckInDetailPhase.Offline
                            WorkoutCheckInError.AUTH_REQUIRED -> CheckInDetailPhase.SignedOut
                            WorkoutCheckInError.CHECKIN_NOT_FOUND,
                            WorkoutCheckInError.SOCIAL_NOT_ENABLED ->
                                CheckInDetailPhase.Unavailable
                            else -> CheckInDetailPhase.Error("Não foi possível carregar a publicação.")
                        }
                    )
                }
            }
        }
    }

    private fun loadPhoto(mediaId: String, expectedUid: String) {
        val cache = mediaCache ?: return
        viewModelScope.launch {
            val image = cache.load(mediaId, expectedUid) ?: return@launch
            if (currentUid() != expectedUid) return@launch
            _uiState.update { it.copy(photo = image) }
        }
    }

    private fun optimistic(
        item: WorkoutCheckIn,
        type: ReactionType,
        removing: Boolean
    ): WorkoutCheckIn {
        val counts = item.reactions.toMutableMap()
        item.currentUserReaction?.let { previous ->
            val next = (counts[previous] ?: 1) - 1
            if (next <= 0) counts.remove(previous) else counts[previous] = next
        }
        if (!removing) {
            counts[type] = (counts[type] ?: 0) + 1
        }
        return item.copy(
            reactions = counts,
            currentUserReaction = if (removing) null else type
        )
    }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid
}
