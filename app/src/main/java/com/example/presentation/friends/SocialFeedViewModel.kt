package com.example.presentation.friends

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.media.SocialMediaCache
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.ReactionType
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A fase de leitura do Feed. */
sealed interface SocialFeedPhase {
    data object Loading : SocialFeedPhase

    /** Carregou. Uma lista vazia é um estado normal — e não um erro (§90). */
    data class Success(val items: List<WorkoutCheckIn>) : SocialFeedPhase

    /** Sem rede. O núcleo do Spark continua funcionando (§91). */
    data object Offline : SocialFeedPhase

    /** É preciso entrar na Conta Spark. */
    data object SignedOut : SocialFeedPhase

    /** A conta não tem perfil social ativo. */
    data object SocialNotEnabled : SocialFeedPhase

    data class Error(val message: String) : SocialFeedPhase
}

data class SocialFeedUiState(
    val phase: SocialFeedPhase = SocialFeedPhase.Loading,
    val isRefreshing: Boolean = false,
    /** O check-in cuja exclusão está em andamento. Ocupação **por alvo**, nunca um `isLoading`. */
    val deletingCheckInId: String? = null,
    /**
     * As fotos já carregadas, em memória (T17.9 §56).
     *
     * `ImageBitmap` e não `Uri`, `File` ou URL: não existe caminho em que a foto de um amigo
     * encoste no disco deste aparelho. O mapa é reconstruído a cada troca de conta, junto com o
     * cache que o alimenta (§57/§145).
     */
    val photos: Map<String, ImageBitmap> = emptyMap(),
    /** Os check-ins cuja reação está em voo. Ocupação **por alvo**, nunca um `isLoading`. */
    val pendingReactions: Set<String> = emptySet(),
    val notice: String? = null
)

/**
 * O Feed social (T17.8, Etapa 4).
 *
 * ## O que ele guarda, e por quanto tempo
 *
 * Memória, e só memória (§111). Não existe entidade Room de check-in, não existe DataStore e não
 * existe cache em disco: uma cópia local continuaria mostrando o que a outra pessoa apagou, o
 * amigo que desfez a amizade e o autor que desativou o Social. Quem decide quem aparece é o
 * servidor, **a cada leitura** — e é isso que faz bloqueio e unfriend serem revogações imediatas
 * (§58) em vez de depender de invalidar cache.
 *
 * ## Troca de conta
 *
 * O estado é limpo **antes** de a requisição da conta nova sair, e a resposta de uma requisição da
 * conta anterior é descartada comparando o `uid` de agora com o de então (§110/§150). Sair da
 * conta limpa o Feed imediatamente (§112).
 *
 * ## O que ele nunca faz
 *
 * Sem polling (§93), sem WebSocket (§94), sem push (§95). O que busca é abrir a tela e o
 * "puxar para atualizar" (§92) — nada roda em background, e nada se agenda.
 */
class SocialFeedViewModel(
    private val gateway: WorkoutCheckInGateway,
    private val authGateway: AuthGateway,
    /**
     * O cache de fotos, em memória e com escopo de conta (T17.9 §56/§57).
     *
     * Opcional para que a ViewModel continue montável em um teste que não se importa com imagem —
     * e porque um Feed sem fotos é um Feed completo: toda publicação da T17.8 é assim.
     */
    private val mediaCache: SocialMediaCache? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SocialFeedUiState())
    val uiState: StateFlow<SocialFeedUiState> = _uiState.asStateFlow()

    private var observedUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid == observedUid) return@collect
                observedUid = newUid

                // Limpar vem primeiro, sempre: o Feed da conta anterior não pode ficar na tela
                // enquanto a leitura da conta nova corre. Desde a T17.9 isso inclui as **fotos**,
                // e o cache é trocado **antes** de qualquer requisição sair (§57/§145): uma
                // imagem da conta anterior desenhada para a nova é o defeito que §195 lista como
                // bloqueante.
                mediaCache?.switchAccount(newUid)
                _uiState.value = SocialFeedUiState(
                    phase = if (newUid == null) SocialFeedPhase.SignedOut else SocialFeedPhase.Loading
                )
                if (newUid != null) load(newUid, refreshing = false)
            }
        }
    }

    /** Abrir a tela busca. Chamar de novo enquanto uma leitura corre não abre outra. */
    fun open() {
        val uid = currentUid() ?: run {
            _uiState.value = SocialFeedUiState(phase = SocialFeedPhase.SignedOut)
            return
        }
        if (_uiState.value.isRefreshing) return
        load(uid, refreshing = false)
    }

    /** Puxar para atualizar (§92). */
    fun refresh() {
        val uid = currentUid() ?: run {
            _uiState.value = SocialFeedUiState(phase = SocialFeedPhase.SignedOut)
            return
        }
        if (_uiState.value.isRefreshing) return
        load(uid, refreshing = true)
    }

    /** Excluir a própria publicação (§87). O servidor recusa a de qualquer outra pessoa. */
    fun deleteCheckIn(checkInId: String) {
        val uid = currentUid() ?: return
        if (_uiState.value.deletingCheckInId != null) return

        _uiState.update { it.copy(deletingCheckInId = checkInId, notice = null) }

        viewModelScope.launch {
            val outcome = gateway.deleteCheckIn(checkInId)
            if (currentUid() != uid) return@launch

            when (outcome) {
                is WorkoutCheckInOutcome.Success -> {
                    // Remoção local imediata **mais** releitura: a lista some sem piscar, e a
                    // verdade continua vindo do servidor.
                    _uiState.update { state ->
                        val phase = state.phase
                        state.copy(
                            deletingCheckInId = null,
                            notice = "Publicação excluída.",
                            phase = if (phase is SocialFeedPhase.Success) {
                                SocialFeedPhase.Success(
                                    phase.items.filterNot { it.checkInId == checkInId }
                                )
                            } else {
                                phase
                            }
                        )
                    }
                    load(uid, refreshing = true)
                }

                is WorkoutCheckInOutcome.Failure -> _uiState.update {
                    it.copy(
                        deletingCheckInId = null,
                        notice = when (outcome.error) {
                            WorkoutCheckInError.NETWORK ->
                                "Sem conexão. A publicação não foi excluída."
                            WorkoutCheckInError.CHECKIN_NOT_FOUND ->
                                "Esta publicação não existe mais."
                            else -> "Não foi possível excluir a publicação agora."
                        }
                    )
                }
            }
        }
    }

    /**
     * Reagir, trocar de reação ou remover (T17.9 §120/§121).
     *
     * ## Otimista, com rollback — e por que só aqui
     *
     * A reação é **reversível e barata**: se o servidor recusar, desfazer não perde nada que o
     * usuário tenha escrito. Por isso a tela responde na hora e reconcilia depois (§121). Um
     * comentário não recebe o mesmo tratamento (§122): ele carrega texto que a pessoa digitou, e um
     * comentário que aparece e some faz quem escreveu acreditar que a outra pessoa leu.
     *
     * O rollback é o **estado anterior guardado**, e não um decremento: subtrair 1 da contagem
     * assumiria que nada mais mudou nela desde a leitura, e a contagem é filtrada por viewer no
     * servidor (§69) — recalculá-la aqui recolocaria na conta gente que o bloqueio tirou.
     *
     * Tocar na reação atual de novo a remove (§120), que é o comportamento que a tela promete.
     */
    fun toggleReaction(checkInId: String, type: ReactionType) {
        val uid = currentUid() ?: return
        val state = _uiState.value
        val phase = state.phase as? SocialFeedPhase.Success ?: return
        if (checkInId in state.pendingReactions) return

        val current = phase.items.firstOrNull { it.checkInId == checkInId } ?: return
        val removing = current.currentUserReaction == type

        _uiState.update {
            it.copy(
                pendingReactions = it.pendingReactions + checkInId,
                phase = SocialFeedPhase.Success(
                    phase.items.map { item ->
                        if (item.checkInId == checkInId) optimistic(item, type, removing) else item
                    }
                )
            )
        }

        viewModelScope.launch {
            val outcome = if (removing) {
                gateway.removeReaction(checkInId)
            } else {
                gateway.putReaction(checkInId, type)
            }
            if (currentUid() != uid) return@launch

            when (outcome) {
                is WorkoutCheckInOutcome.Success -> replaceItem(checkInId) { outcome.data }
                is WorkoutCheckInOutcome.Failure -> {
                    // Rollback para o **estado que veio do servidor**, e não para uma conta
                    // recalculada: nunca deixar estado local falso (§121).
                    replaceItem(checkInId) { current }
                    _uiState.update {
                        it.copy(
                            notice = when (outcome.error) {
                                WorkoutCheckInError.NETWORK ->
                                    "Sem conexão. Sua reação não foi registrada."
                                WorkoutCheckInError.CHECKIN_NOT_FOUND ->
                                    "Esta publicação não está mais disponível."
                                WorkoutCheckInError.RATE_LIMITED ->
                                    "Muitas reações seguidas. Tente em instantes."
                                else -> "Não foi possível registrar sua reação agora."
                            }
                        )
                    }
                }
            }
            _uiState.update { it.copy(pendingReactions = it.pendingReactions - checkInId) }
        }
    }

    /**
     * Carrega a foto de um check-in, se ainda não estiver em memória (T17.9 §56).
     *
     * Chamado pela tela quando o card entra em composição. `expectedUid` é conferido dentro do
     * cache: a resposta de uma requisição da conta anterior é descartada, e nunca vira pixel.
     */
    fun loadPhoto(mediaId: String) {
        val cache = mediaCache ?: return
        val uid = currentUid() ?: return
        if (_uiState.value.photos.containsKey(mediaId)) return

        viewModelScope.launch {
            val image = cache.load(mediaId, uid) ?: return@launch
            if (currentUid() != uid) return@launch
            _uiState.update { it.copy(photos = it.photos + (mediaId to image)) }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /**
     * A projeção otimista de uma reação.
     *
     * Ela mexe **só** no que a ação do usuário determina: a reação dele e a contagem do tipo que
     * ele tocou (mais a do tipo anterior, quando é uma troca). Nenhum outro número é tocado — a
     * resposta do servidor chega logo em seguida e substitui tudo.
     */
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

    /** Substitui um item da lista preservando a ordem. */
    private fun replaceItem(checkInId: String, transform: (WorkoutCheckIn) -> WorkoutCheckIn) {
        _uiState.update { state ->
            val phase = state.phase
            if (phase !is SocialFeedPhase.Success) return@update state
            state.copy(
                phase = SocialFeedPhase.Success(
                    phase.items.map { if (it.checkInId == checkInId) transform(it) else it }
                )
            )
        }
    }

    private fun load(expectedUid: String, refreshing: Boolean) {
        _uiState.update {
            it.copy(
                isRefreshing = true,
                phase = if (refreshing) it.phase else SocialFeedPhase.Loading
            )
        }

        viewModelScope.launch {
            val outcome = gateway.feed()
            // A conta pode ter trocado durante o voo. Renderizar isto seria mostrar a alguém o
            // Feed de outra pessoa (§110).
            if (currentUid() != expectedUid) return@launch

            _uiState.update { state ->
                state.copy(
                    isRefreshing = false,
                    phase = when (outcome) {
                        is WorkoutCheckInOutcome.Success ->
                            SocialFeedPhase.Success(outcome.data)

                        is WorkoutCheckInOutcome.Failure -> when (outcome.error) {
                            WorkoutCheckInError.NETWORK -> SocialFeedPhase.Offline
                            WorkoutCheckInError.NOT_CONFIGURED,
                            WorkoutCheckInError.UNAVAILABLE ->
                                SocialFeedPhase.Error("Feed indisponível no momento.")
                            WorkoutCheckInError.AUTH_REQUIRED -> SocialFeedPhase.SignedOut
                            WorkoutCheckInError.SOCIAL_NOT_ENABLED ->
                                SocialFeedPhase.SocialNotEnabled
                            WorkoutCheckInError.RATE_LIMITED ->
                                SocialFeedPhase.Error("Muitas atualizações seguidas. Tente em instantes.")
                            else -> SocialFeedPhase.Error("Não foi possível carregar o Feed.")
                        }
                    }
                )
            }
        }
    }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid
}
