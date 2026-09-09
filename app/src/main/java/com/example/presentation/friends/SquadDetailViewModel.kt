package com.example.presentation.friends

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.media.SocialMediaCache
import com.example.data.social.SocialGroupContract
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.Friend
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendOutcome
import com.example.domain.social.InteractionContext
import com.example.domain.social.ReactionType
import com.example.domain.social.SocialGroupDetail
import com.example.domain.social.SocialGroupError
import com.example.domain.social.SocialGroupFeedItem
import com.example.domain.social.SocialGroupGateway
import com.example.domain.social.SocialGroupMember
import com.example.domain.social.SocialGroupOutcome
import com.example.domain.social.WorkoutCheckIn
import com.example.domain.social.WorkoutCheckInError
import com.example.domain.social.WorkoutCheckInGateway
import com.example.domain.social.WorkoutCheckInOutcome
import com.example.domain.social.interactionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A fase de leitura do detalhe de um Squad. */
sealed interface SquadDetailPhase {
    data object Loading : SquadDetailPhase

    data class Success(
        val detail: SocialGroupDetail,
        val feed: List<SocialGroupFeedItem>,
        val members: List<SocialGroupMember>
    ) : SquadDetailPhase

    data object Offline : SquadDetailPhase
    data object SignedOut : SquadDetailPhase
    data object SocialNotEnabled : SquadDetailPhase

    /**
     * O Squad não existe, foi excluído, ou o usuário não é (mais) membro.
     *
     * Os três chegam aqui pelo mesmo `404` do servidor (§59/§60), e a tela diz a mesma coisa para
     * os três — distinguir seria inventar informação que ninguém tem.
     */
    data object Unavailable : SquadDetailPhase

    data class Error(val message: String) : SquadDetailPhase
}

/** Qual aba do detalhe está aberta (§135). */
enum class SquadDetailTab { FEED, MEMBERS }

data class SquadDetailUiState(
    val phase: SquadDetailPhase = SquadDetailPhase.Loading,
    val tab: SquadDetailTab = SquadDetailTab.FEED,
    val isRefreshing: Boolean = false,
    /** As fotos já carregadas, em memória (T17.9 §56; T17.11 §113). */
    val photos: Map<String, ImageBitmap> = emptyMap(),
    /** Os amigos elegíveis para convite — a lista local, filtrada (§137). */
    val invitableFriends: List<Friend> = emptyList(),
    /** O membro/convite/ação em voo. Ocupação **por alvo**, nunca um `isLoading` global. */
    val busyTargetId: String? = null,
    /**
     * As publicações cuja reação está em voo **neste Squad** (T17.12 §61).
     *
     * A chave é `(audiência, checkInId)` — [interactionKey] —, e nunca o `checkInId` sozinho: a
     * mesma publicação está aberta no Feed de amigos e possivelmente em outro Squad, e uma chave
     * sem audiência travaria o botão dos outros lugares enquanto esta requisição corre.
     */
    val pendingReactions: Set<String> = emptySet(),
    /** `true` quando a saída ou a exclusão concluiu: a tela volta para a lista. */
    val closed: Boolean = false,
    val notice: String? = null
) {
    val detail: SocialGroupDetail? get() = (phase as? SquadDetailPhase.Success)?.detail
    val isOwner: Boolean get() = detail?.isOwner == true
}

/**
 * O detalhe de um Squad: feed, membros, administração e compartilhamento (T17.11, Etapa E).
 *
 * ## O que esta tela pode oferecer, e quem decide
 *
 * A tela desenha as ações a partir de `role` e de `canInteract`, que **vêm do servidor**. Isso não
 * é controle de acesso (§72/§83): é para não oferecer um botão que sempre falha. Toda ação daqui é
 * revalidada no servidor, e uma tela desatualizada não amplia nada.
 *
 * ## Nada aqui é persistido (§113/§114)
 *
 * Memória, escopo de conta, e o cache de fotos trocado **antes** de qualquer requisição sair. Sem
 * Room, sem DataStore, sem Outbox.
 *
 * ## O convite passa pela lista local de amigos, e o servidor revalida (§137)
 *
 * O seletor mostra amigos atuais porque só eles podem ser convidados (§23). Filtrar aqui é
 * conveniência — o servidor confere a amizade e o bloqueio de novo, no envio e no aceite (§25/§29),
 * e é ele quem recusa um `socialId` que o app não deveria ter oferecido.
 *
 * ## Reagir aqui é reagir **neste Squad** (T17.12 §12/§14)
 *
 * Até a T17.11 esta tela não oferecia reação nem comentário: um mesmo check-in em dois Squads e no
 * Feed de amigos teria uma conversa com três audiências sobrepostas, e a fase preferiu leitura a
 * vazamento (T17.11 §70/§71). A T17.12 resolve a causa — a interação passa a pertencer a uma
 * audiência —, e por isso toda ação daqui viaja com [InteractionContext.Group] deste `groupId`.
 * Ela não toca a reação que a pessoa deixou no Feed de amigos nem a de outro Squad: são estados
 * independentes do mesmo check-in.
 *
 * O `groupId` é **proposta**, e não autorização (§7/§67): o servidor revalida o compartilhamento,
 * a participação ativa e o bloqueio a cada requisição, e recusa com `404` em vez de rebaixar para
 * o Feed de amigos.
 */
class SquadDetailViewModel(
    private val groupId: String,
    private val gateway: SocialGroupGateway,
    private val authGateway: AuthGateway,
    /** A lista de amigos, para o seletor de convite (§137). Opcional: sem ela, não se convida. */
    private val friends: FriendGateway? = null,
    /** O cache de fotos, em memória e com escopo de conta (T17.9 §56/§57). */
    private val mediaCache: SocialMediaCache? = null,
    /**
     * A fronteira do check-in, para reagir dentro deste Squad (T17.12 §12).
     *
     * Opcional pela mesma razão dos outros: um build sem Spark Backend não tem Squads (T17.11
     * §116), e sem ela a tela continua completa — o card simplesmente não oferece reação.
     */
    private val checkInGateway: WorkoutCheckInGateway? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SquadDetailUiState())
    val uiState: StateFlow<SquadDetailUiState> = _uiState.asStateFlow()

    private var observedUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid == observedUid) return@collect
                observedUid = newUid

                // O cache de fotos é trocado **antes** de qualquer requisição sair (§112): uma
                // imagem da conta anterior desenhada para a nova é vazamento, não atraso.
                mediaCache?.switchAccount(newUid)
                _uiState.value = SquadDetailUiState(
                    phase = if (newUid == null) {
                        SquadDetailPhase.SignedOut
                    } else {
                        SquadDetailPhase.Loading
                    }
                )
                if (newUid != null) load(newUid, refreshing = false)
            }
        }
    }

    fun open() {
        val uid = currentUid() ?: run {
            _uiState.value = SquadDetailUiState(phase = SquadDetailPhase.SignedOut)
            return
        }
        if (_uiState.value.isRefreshing) return
        load(uid, refreshing = false)
    }

    fun refresh() {
        val uid = currentUid() ?: return
        if (_uiState.value.isRefreshing) return
        load(uid, refreshing = true)
    }

    fun selectTab(tab: SquadDetailTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    // ------------------------------------------------------------------ convites

    /** Carrega os amigos elegíveis (§137). Só o dono convida, então só para ele isso é chamado. */
    fun loadInvitableFriends() {
        val uid = currentUid() ?: return
        val gatewayForFriends = friends ?: return

        viewModelScope.launch {
            val outcome = gatewayForFriends.friends()
            if (currentUid() != uid) return@launch
            val list = (outcome as? FriendOutcome.Success)?.value?.items ?: return@launch

            val memberSocialIds = (_uiState.value.phase as? SquadDetailPhase.Success)
                ?.members
                ?.mapNotNull { it.socialId }
                ?.toSet()
                .orEmpty()

            _uiState.update {
                it.copy(invitableFriends = list.filterNot { friend ->
                    friend.socialId in memberSocialIds
                })
            }
        }
    }

    /** Convida um amigo (§22/§137). O `clientRequestId` nasce por tentativa, e não por retry. */
    fun invite(socialId: String) {
        val uid = currentUid() ?: return
        if (_uiState.value.busyTargetId != null) return

        _uiState.update { it.copy(busyTargetId = socialId, notice = null) }
        val clientRequestId = UUID.randomUUID().toString()

        viewModelScope.launch {
            val outcome = gateway.invite(groupId, socialId, clientRequestId)
            if (currentUid() != uid) return@launch

            _uiState.update {
                it.copy(
                    busyTargetId = null,
                    notice = when (outcome) {
                        is SocialGroupOutcome.Success -> "Convite enviado."
                        is SocialGroupOutcome.Failure -> noticeFor(outcome.error)
                    }
                )
            }
            if (outcome is SocialGroupOutcome.Success) load(uid, refreshing = true)
        }
    }

    // ------------------------------------------------------------------ administração

    /** Sai do Squad (§38). O dono não chega aqui: a tela não oferece (§39), e o servidor recusa. */
    fun leave() {
        act(groupId) { gateway.leaveGroup(groupId) }
    }

    /** Remove um participante pelo `membershipId` — nunca por identidade (§36/§42). */
    fun removeMember(membershipId: String) {
        act(membershipId) { gateway.removeMember(groupId, membershipId) }
    }

    /** Transfere a posse (§40/§41). O alvo sai da lista de membros que a tela mostrou. */
    fun transferOwnership(membershipId: String) {
        act(membershipId, closeOnSuccess = false) {
            gateway.transferOwnership(groupId, membershipId)
        }
    }

    /** Exclui o Squad (§46). Só o dono, e a tela pede confirmação antes de chamar. */
    fun deleteGroup() {
        act(groupId) { gateway.deleteGroup(groupId) }
    }

    // ------------------------------------------------------------------ feed

    /**
     * Desfaz o compartilhamento de um check-in **próprio** (§128/§130).
     *
     * A publicação continua existindo: some o vínculo com este Squad, e só ele. A tela deixa isso
     * explícito no texto do menu, porque "remover" perto de um post costuma significar apagar.
     */
    fun unshare(checkInId: String) {
        val uid = currentUid() ?: return
        if (_uiState.value.busyTargetId != null) return

        _uiState.update { it.copy(busyTargetId = checkInId, notice = null) }

        viewModelScope.launch {
            val outcome = gateway.unshareCheckIn(groupId, checkInId)
            if (currentUid() != uid) return@launch

            when (outcome) {
                is SocialGroupOutcome.Success -> {
                    _uiState.update { state ->
                        val phase = state.phase
                        state.copy(
                            busyTargetId = null,
                            notice = "Removido do squad. A publicação continua no seu feed.",
                            phase = if (phase is SquadDetailPhase.Success) {
                                phase.copy(
                                    feed = phase.feed.filterNot {
                                        it.checkIn.checkInId == checkInId
                                    }
                                )
                            } else {
                                phase
                            }
                        )
                    }
                    load(uid, refreshing = true)
                }

                is SocialGroupOutcome.Failure -> _uiState.update {
                    it.copy(busyTargetId = null, notice = noticeFor(outcome.error))
                }
            }
        }
    }

    /**
     * Reagir, trocar de reação ou remover — **dentro deste Squad** (T17.12 §12/§14).
     *
     * Otimista com rollback, como no Feed de amigos (T17.9 §121): a reação é reversível e barata,
     * então a tela responde na hora e reconcilia com a resposta do servidor. O rollback é o
     * **estado que veio do servidor**, e não uma contagem recalculada — a contagem é filtrada por
     * viewer lá (§69), e refazê-la aqui recolocaria na conta quem o bloqueio tirou.
     *
     * A audiência é `GROUP(groupId)`, sempre. A mesma pessoa pode ter 🔥 aqui e 💪 no Feed de
     * amigos sobre o mesmo check-in, e as duas são independentes (§5).
     */
    fun toggleReaction(checkInId: String, type: ReactionType) {
        val uid = currentUid() ?: return
        val gatewayForCheckIns = checkInGateway ?: return
        val phase = _uiState.value.phase as? SquadDetailPhase.Success ?: return

        val context = InteractionContext.Group(groupId)
        val pendingKey = interactionKey(checkInId, context)
        if (pendingKey in _uiState.value.pendingReactions) return

        val item = phase.feed.firstOrNull { it.checkIn.checkInId == checkInId } ?: return
        // §144 — o servidor recusa de qualquer forma; não oferecer o que vai falhar é a razão de
        // olhar `canInteract` aqui.
        if (!item.checkIn.canInteract) return

        val before = item.checkIn
        val removing = before.currentUserReaction == type

        _uiState.update { state ->
            state.copy(
                pendingReactions = state.pendingReactions + pendingKey,
                phase = replaceCheckIn(state.phase, checkInId) {
                    optimistic(before, type, removing)
                }
            )
        }

        viewModelScope.launch {
            val outcome = if (removing) {
                gatewayForCheckIns.removeReaction(checkInId, context)
            } else {
                gatewayForCheckIns.putReaction(checkInId, type, context)
            }
            if (currentUid() != uid) return@launch

            _uiState.update { state ->
                when (outcome) {
                    is WorkoutCheckInOutcome.Success -> state.copy(
                        pendingReactions = state.pendingReactions - pendingKey,
                        phase = replaceCheckIn(state.phase, checkInId) { outcome.data }
                    )

                    is WorkoutCheckInOutcome.Failure -> state.copy(
                        pendingReactions = state.pendingReactions - pendingKey,
                        phase = replaceCheckIn(state.phase, checkInId) { before },
                        notice = when (outcome.error) {
                            WorkoutCheckInError.NETWORK ->
                                "Sem conexão. Sua reação não foi registrada."
                            WorkoutCheckInError.CHECKIN_NOT_FOUND ->
                                "Esta publicação não está mais disponível neste squad."
                            WorkoutCheckInError.RATE_LIMITED ->
                                "Muitas reações seguidas. Tente em instantes."
                            else -> "Não foi possível registrar sua reação agora."
                        }
                    )
                }
            }
        }
    }

    /**
     * Carrega a foto de um item, se ainda não estiver em memória (T17.9 §56).
     *
     * Chamado pela tela quando o card entra em composição. O `uid` esperado é conferido dentro do
     * cache: a resposta de uma requisição da conta anterior é descartada, e nunca vira pixel.
     */
    fun loadPhoto(mediaId: String) {
        val cache = mediaCache ?: return
        val uid = currentUid() ?: return
        if (_uiState.value.photos.containsKey(mediaId)) return

        viewModelScope.launch {
            val bitmap = cache.load(mediaId, uid) ?: return@launch
            if (currentUid() != uid) return@launch
            _uiState.update { it.copy(photos = it.photos + (mediaId to bitmap)) }
        }
    }

    // ------------------------------------------------------------------ internas

    private fun act(
        targetId: String,
        closeOnSuccess: Boolean = true,
        action: suspend () -> SocialGroupOutcome<*>
    ) {
        val uid = currentUid() ?: return
        if (_uiState.value.busyTargetId != null) return

        _uiState.update { it.copy(busyTargetId = targetId, notice = null) }

        viewModelScope.launch {
            val outcome = action()
            if (currentUid() != uid) return@launch

            when (outcome) {
                is SocialGroupOutcome.Success -> {
                    if (closeOnSuccess) {
                        _uiState.update { it.copy(busyTargetId = null, closed = true) }
                    } else {
                        _uiState.update { it.copy(busyTargetId = null) }
                        load(uid, refreshing = true)
                    }
                }

                is SocialGroupOutcome.Failure -> _uiState.update {
                    it.copy(busyTargetId = null, notice = noticeFor(outcome.error))
                }
            }
        }
    }

    /**
     * Uma leitura busca as três coisas do detalhe.
     *
     * Três requisições, e não uma: cabeçalho, feed e membros respondem a autorizações diferentes no
     * servidor, e juntá-las em uma rota só faria a mais restrita decidir pelas outras. A tela
     * espera as três antes de trocar a fase, para não desenhar meio Squad.
     *
     * A **primeira** falha decide a fase, e as seguintes nem são feitas: se o cabeçalho respondeu
     * `404`, pedir o feed do mesmo grupo só produziria um segundo `404`.
     */
    private fun load(uid: String, refreshing: Boolean) {
        _uiState.update {
            it.copy(
                isRefreshing = refreshing,
                phase = if (refreshing) it.phase else SquadDetailPhase.Loading
            )
        }

        viewModelScope.launch {
            val detailOutcome = gateway.group(groupId)
            if (currentUid() != uid) return@launch
            if (detailOutcome is SocialGroupOutcome.Failure) {
                _uiState.update { it.copy(isRefreshing = false, phase = phaseFor(detailOutcome.error)) }
                return@launch
            }

            val feedOutcome = gateway.feed(groupId)
            if (currentUid() != uid) return@launch
            if (feedOutcome is SocialGroupOutcome.Failure) {
                _uiState.update { it.copy(isRefreshing = false, phase = phaseFor(feedOutcome.error)) }
                return@launch
            }

            val membersOutcome = gateway.members(groupId)
            if (currentUid() != uid) return@launch
            if (membersOutcome is SocialGroupOutcome.Failure) {
                _uiState.update {
                    it.copy(isRefreshing = false, phase = phaseFor(membersOutcome.error))
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    phase = SquadDetailPhase.Success(
                        detail = (detailOutcome as SocialGroupOutcome.Success).data,
                        feed = (feedOutcome as SocialGroupOutcome.Success).data,
                        members = (membersOutcome as SocialGroupOutcome.Success).data
                    )
                )
            }
        }
    }

    /** Substitui o check-in de um item do feed preservando a ordem e o `sharedToGroupAt`. */
    private fun replaceCheckIn(
        phase: SquadDetailPhase,
        checkInId: String,
        transform: (WorkoutCheckIn) -> WorkoutCheckIn
    ): SquadDetailPhase {
        if (phase !is SquadDetailPhase.Success) return phase
        return phase.copy(
            feed = phase.feed.map { item ->
                if (item.checkIn.checkInId == checkInId) {
                    item.copy(checkIn = transform(item.checkIn))
                } else {
                    item
                }
            }
        )
    }

    /**
     * A projeção otimista de uma reação — a mesma do Feed de amigos (T17.9 §121).
     *
     * Ela mexe só no que a ação determina: a reação do usuário e a contagem do tipo tocado (mais a
     * do tipo anterior, quando é troca). Nenhum outro número é tocado, porque a resposta do
     * servidor chega logo em seguida e substitui tudo.
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

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid

    private fun phaseFor(error: SocialGroupError): SquadDetailPhase = when (error) {
        SocialGroupError.NETWORK -> SquadDetailPhase.Offline
        SocialGroupError.AUTH_REQUIRED -> SquadDetailPhase.SignedOut
        SocialGroupError.SOCIAL_NOT_ENABLED -> SquadDetailPhase.SocialNotEnabled
        SocialGroupError.GROUP_NOT_FOUND, SocialGroupError.NOT_CONFIGURED ->
            SquadDetailPhase.Unavailable
        else -> SquadDetailPhase.Error("Não foi possível carregar este squad agora.")
    }

    private fun noticeFor(error: SocialGroupError): String = when (error) {
        SocialGroupError.NETWORK -> "Sem conexão. Nada foi enviado."
        SocialGroupError.AUTH_REQUIRED -> "Entre na Conta Spark para usar os Squads."
        SocialGroupError.SOCIAL_NOT_ENABLED -> "Ative seu perfil social para usar os Squads."
        SocialGroupError.FORBIDDEN -> "Só o dono do squad pode fazer isso."
        SocialGroupError.OWNER_ACTION_REQUIRED ->
            "Transfira a posse ou exclua o squad antes de sair."
        SocialGroupError.GROUP_FULL ->
            "Este squad já tem ${SocialGroupContract.Limits.MAX_MEMBERS} participantes."
        SocialGroupError.INVITE_NOT_ALLOWED -> "Não é possível convidar esta pessoa."
        SocialGroupError.ALREADY_MEMBER -> "Esta pessoa já participa do squad."
        SocialGroupError.INVITE_LIMIT_REACHED -> "Já há convites demais pendentes neste squad."
        SocialGroupError.MEMBER_NOT_FOUND -> "Este participante não está mais no squad."
        SocialGroupError.GROUP_NOT_FOUND -> "Este squad não está mais disponível."
        SocialGroupError.CHECKIN_NOT_FOUND -> "Esta publicação não está mais disponível."
        SocialGroupError.SHARE_LIMIT_REACHED ->
            "Um check-in pode entrar em no máximo " +
                "${SocialGroupContract.Limits.MAX_SHARES_PER_CHECKIN} squads."
        SocialGroupError.RATE_LIMITED -> "Muitas ações seguidas. Tente em instantes."
        SocialGroupError.UNAVAILABLE -> "O servidor está indisponível. Tente novamente."
        else -> "Não foi possível concluir agora."
    }
}
