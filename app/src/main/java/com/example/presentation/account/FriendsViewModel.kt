package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.social.FriendshipContract
import com.example.data.social.QrScan
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.Friend
import com.example.domain.social.FriendError
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendLookup
import com.example.domain.social.FriendOutcome
import com.example.domain.social.FriendRequestSent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * O grafo social, do ponto de vista da UI (T17.1).
 *
 * ```text
 * Perfil / Amigos / Solicitações → FriendsViewModel → FriendGateway → Spark Backend
 *                                                                          │
 *                                                                     autoridade
 * ```
 *
 * ## Nada acontece sozinho
 *
 * Não há requisição em `init`, em recomposição ou no listener de login. A primeira leitura sai de
 * [open], que a tela chama quando o usuário chega nela — abrir a seção de amigos é um ato
 * explícito, e ler não cria nem altera relação nenhuma.
 *
 * ## Troca de conta invalida na hora
 *
 * `A logado → amigos de A carregados → logout → login B` não pode, em nenhum instante, mostrar a
 * lista de A como se fosse de B. O estado é descartado **antes** de qualquer requisição de B
 * sair, e a resposta de uma requisição iniciada por A é descartada se a conta tiver mudado no meio
 * do voo — a mesma lição da T16.7.1: o `uid` capturado antes da chamada não vale depois dela.
 *
 * ## Offline não finge
 *
 * Sem internet, adicionar/aceitar/recusar/cancelar/remover **não acontece**: não vai para a
 * Outbox, não fica pendente e não é reenviado depois. A tela diz que nada foi enviado. E nada
 * disso toca Room, treino, histórico, backup, sincronização ou gamificação.
 *
 * ## Um toque, uma mutação
 *
 * Cada ação marca o **alvo** como ocupado ([FriendsUiState.pendingRequestIds],
 * [FriendsUiState.pendingFriendIds]) e ignora toques repetidos naquele alvo. O servidor repete a
 * proteção sendo idempotente: dois aceites produzem uma amizade, não duas.
 */
class FriendsViewModel(
    private val gateway: FriendGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FriendsUiState(
            phase = if (gateway.isConfigured) FriendsPhase.SignedOut else FriendsPhase.NotConfigured
        )
    )
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    /** A conta da qual o estado atual fala. `null` = nenhuma. */
    private var currentUid: String? = null

    /** O leitor de QR, instalado pela tela — que é quem tem `Context`. */
    private var scanner: (suspend () -> QrScan)? = null

    init {
        viewModelScope.launch {
            // Observar a sessão é leitura: não abre seletor de contas, não ativa Social e não
            // carrega lista nenhuma. O que ela faz é **invalidar** quando a conta muda.
            authGateway.state.collect { state ->
                onAccountChanged((state as? AuthState.SignedIn)?.account?.uid)
            }
        }
    }

    /**
     * A conta mudou (ou foi observada pela primeira vez).
     *
     * A invalidação vem **antes** de qualquer requisição: entre o logout de A e a resposta de B a
     * tela mostra vazio, nunca os amigos de A.
     */
    private fun onAccountChanged(uid: String?) {
        if (!gateway.isConfigured) {
            _uiState.value = FriendsUiState(phase = FriendsPhase.NotConfigured)
            return
        }
        if (uid == currentUid) return

        currentUid = uid
        // Estado novo por completo — listas, contadores, busca em andamento, folhas abertas e o
        // texto digitado. Tudo pertencia à conta anterior.
        _uiState.value = FriendsUiState(
            phase = if (uid == null) FriendsPhase.SignedOut else FriendsPhase.Idle
        )
    }

    /** A tela informa qual leitor de QR usar. Trocá-lo não dispara nada. */
    fun attachScanner(scan: suspend () -> QrScan) {
        scanner = scan
    }

    // --------------------------------------------------------------------------- carregamento

    /**
     * Carrega o que a seção precisa, se ainda não estiver carregado.
     *
     * Chamado ao abrir uma tela do grafo. Idempotente: reabrir a tela não refaz as requisições, e
     * é [refresh] que força a releitura.
     */
    fun open() {
        if (_uiState.value.phase is FriendsPhase.Ready) return
        if (_uiState.value.phase is FriendsPhase.Loading) return
        refresh()
    }

    /** Relê tudo no servidor. Leitura pura: não cria e não altera nada. */
    fun refresh() {
        val uid = currentUid ?: return
        if (_uiState.value.phase is FriendsPhase.Loading) return

        _uiState.value = _uiState.value.copy(phase = FriendsPhase.Loading, notice = null)
        viewModelScope.launch { load(uid) }
    }

    private suspend fun load(uid: String) {
        val friendsResult = gateway.friends()
        val incomingResult = gateway.incomingRequests()
        val outgoingResult = gateway.outgoingRequests()
        if (currentUid != uid) return

        val failure = listOf(friendsResult, incomingResult, outgoingResult)
            .firstNotNullOfOrNull { it as? FriendOutcome.Failure }
        if (failure != null) {
            _uiState.value = _uiState.value.copy(phase = phaseFor(failure.error))
            return
        }

        val friends = (friendsResult as FriendOutcome.Success).value
        val incoming = (incomingResult as FriendOutcome.Success).value
        val outgoing = (outgoingResult as FriendOutcome.Success).value

        _uiState.value = _uiState.value.copy(
            phase = FriendsPhase.Ready,
            friends = friends.items,
            friendCount = friends.total,
            incoming = incoming.items,
            incomingCount = incoming.total,
            outgoing = outgoing.items
        )
    }

    // --------------------------------------------------------------------------- adicionar

    /** Abre "Adicionar amigo". Não faz requisição nenhuma. */
    fun startAddFriend() {
        if (_uiState.value.isAddFriendOpen) return
        _uiState.value = _uiState.value.copy(
            isAddFriendOpen = true,
            codeInput = "",
            isCodeAcceptable = false,
            lookup = LookupState.Empty
        )
    }

    /** Fecha. Nada foi enviado — a busca sozinha nunca envia pedido. */
    fun cancelAddFriend() {
        _uiState.value = _uiState.value.copy(
            isAddFriendOpen = false,
            codeInput = "",
            isCodeAcceptable = false,
            lookup = LookupState.Empty,
            action = FriendsAction.NONE
        )
    }

    /**
     * O texto do campo de código.
     *
     * A validação local só liga o botão. Ela **não** decide se o código existe: quem responde isso
     * é o servidor, e um código bem formado inexistente recebe a mesma resposta de um malformado.
     */
    fun onCodeChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            codeInput = value,
            isCodeAcceptable = FriendshipContract.isFriendCodeAcceptable(value),
            // Digitar de novo invalida o resultado anterior: manter o preview antigo enquanto o
            // campo mudou faria "Enviar solicitação" apontar para outra pessoa.
            lookup = LookupState.Empty
        )
    }

    /**
     * Procura o código digitado.
     *
     * **Nunca envia pedido.** O envio é [sendRequest], e exige outro toque, depois de o usuário
     * ver quem apareceu — é isso que impede um erro de digitação de virar convite.
     */
    fun lookup() {
        val uid = currentUid ?: return
        val state = _uiState.value
        if (!state.canLookup) return

        val code = state.codeInput
        _uiState.value = state.copy(action = FriendsAction.LOOKING_UP, lookup = LookupState.Empty)

        viewModelScope.launch {
            val outcome = gateway.lookup(code)
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                action = FriendsAction.NONE,
                lookup = when (outcome) {
                    is FriendOutcome.Success -> when (val result = outcome.value) {
                        is FriendLookup.Found -> LookupState.Found(
                            profile = result.profile,
                            relationship = result.relationship,
                            canSendFriendRequest = result.canSendFriendRequest
                        )
                        FriendLookup.Self -> LookupState.Self
                        FriendLookup.NotFound -> LookupState.NotFound
                    }
                    is FriendOutcome.Failure -> LookupState.Failed(outcome.error)
                }
            )
        }
    }

    /**
     * Abre o leitor de QR e trata o que voltar.
     *
     * O conteúdo lido **não é executado**: ele já chega como [QrScan], resultado de
     * `SparkFriendQr.parse`. Um QR válido preenche o campo e dispara a busca — e a busca continua
     * não enviando pedido nenhum.
     */
    fun scanQrCode() {
        val uid = currentUid ?: return
        val scan = scanner ?: run {
            _uiState.value = _uiState.value.copy(lookup = LookupState.ScannerUnavailable)
            return
        }
        if (_uiState.value.action != FriendsAction.NONE) return

        _uiState.value = _uiState.value.copy(action = FriendsAction.SCANNING, lookup = LookupState.Empty)

        viewModelScope.launch {
            val result = scan()
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(action = FriendsAction.NONE)
            when (result) {
                is QrScan.FriendCode -> {
                    onCodeChanged(result.value)
                    lookup()
                }
                else -> {
                    result.toLookupState()?.let { state ->
                        _uiState.value = _uiState.value.copy(lookup = state)
                    }
                }
            }
        }
    }

    /**
     * Envia o pedido para quem a busca encontrou.
     *
     * Toque repetido é ignorado aqui, e o servidor repete a proteção sendo idempotente: dez
     * toques produzem um pedido, não dez.
     */
    fun sendRequest() {
        val uid = currentUid ?: return
        val state = _uiState.value
        val found = state.lookup as? LookupState.Found ?: return
        if (state.action != FriendsAction.NONE) return

        _uiState.value = state.copy(action = FriendsAction.SENDING_REQUEST)

        viewModelScope.launch {
            val outcome = gateway.sendRequest(found.profile.socialId)
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                action = FriendsAction.NONE,
                lookup = when (outcome) {
                    is FriendOutcome.Success -> when (val sent = outcome.value) {
                        is FriendRequestSent.Created ->
                            LookupState.RequestSent(found.profile, sent.request.requestId)
                        is FriendRequestSent.AlreadyPending ->
                            LookupState.RequestSent(found.profile, sent.request.requestId)
                        is FriendRequestSent.BecameFriends -> LookupState.BecameFriends(sent.friend)
                    }
                    is FriendOutcome.Failure -> LookupState.Failed(outcome.error)
                }
            )
            // O servidor mudou de estado: as listas precisam refletir isso, e não a suposição da
            // tela sobre o que deve ter acontecido.
            if (outcome is FriendOutcome.Success) reload(uid)
        }
    }

    // --------------------------------------------------------------------------- pedidos

    fun acceptRequest(requestId: String) = mutateRequest(requestId) { gateway.acceptRequest(it) }

    fun rejectRequest(requestId: String) = mutateRequest(requestId) { gateway.rejectRequest(it) }

    fun cancelRequest(requestId: String) = mutateRequest(requestId) { gateway.cancelRequest(it) }

    /**
     * Uma mutação sobre **um** pedido.
     *
     * Enquanto ela estiver em voo, aquele pedido fica ocupado e novos toques nele são ignorados —
     * mas o resto da tela continua funcionando. Aceitar o pedido do Igor não deveria impedir de
     * responder ao do João.
     */
    private fun mutateRequest(
        requestId: String,
        operation: suspend (String) -> FriendOutcome<*>
    ) {
        val uid = currentUid ?: return
        if (_uiState.value.isRequestBusy(requestId)) return

        _uiState.value = _uiState.value.copy(
            pendingRequestIds = _uiState.value.pendingRequestIds + requestId,
            notice = null
        )

        viewModelScope.launch {
            val outcome = operation(requestId)
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                pendingRequestIds = _uiState.value.pendingRequestIds - requestId,
                notice = (outcome as? FriendOutcome.Failure)?.error
            )
            // Sucesso **e** conflito recarregam: se o outro lado cancelou no meio, o certo é
            // mostrar o estado real do servidor, não a suposição da tela.
            if (outcome is FriendOutcome.Success || isStale(outcome)) reload(uid)
        }
    }

    // --------------------------------------------------------------------------- amizade

    /** Abre a confirmação de remoção. Ainda não removeu nada. */
    fun startRemoveFriend(friend: Friend) {
        if (_uiState.value.isFriendBusy(friend.socialId)) return
        _uiState.value = _uiState.value.copy(friendPendingRemoval = friend)
    }

    fun cancelRemoveFriend() {
        _uiState.value = _uiState.value.copy(friendPendingRemoval = null)
    }

    /**
     * Desfaz a amizade.
     *
     * Remove uma relação, e nada mais: treino, histórico, backup, sincronização, gamificação e o
     * perfil social das duas pessoas continuam exatamente como estavam. Também não bloqueia — os
     * dois podem se adicionar de novo depois.
     */
    fun confirmRemoveFriend() {
        val uid = currentUid ?: return
        val friend = _uiState.value.friendPendingRemoval ?: return
        if (_uiState.value.isFriendBusy(friend.socialId)) return

        _uiState.value = _uiState.value.copy(
            friendPendingRemoval = null,
            pendingFriendIds = _uiState.value.pendingFriendIds + friend.socialId,
            notice = null
        )

        viewModelScope.launch {
            val outcome = gateway.removeFriend(friend.socialId)
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                pendingFriendIds = _uiState.value.pendingFriendIds - friend.socialId,
                notice = (outcome as? FriendOutcome.Failure)?.error
            )
            if (outcome is FriendOutcome.Success || isStale(outcome)) reload(uid)
        }
    }

    /** Descarta o aviso pontual da última ação. */
    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    // --------------------------------------------------------------------------- comum

    /**
     * Releitura silenciosa depois de uma mutação.
     *
     * Sem passar por [FriendsPhase.Loading]: a lista já está na tela e sumir por meio segundo a
     * cada aceite seria pior do que atualizar no lugar. Uma falha aqui também não derruba a tela
     * — o que ela mostra continua sendo a última leitura boa.
     */
    private suspend fun reload(uid: String) {
        val friendsResult = gateway.friends()
        val incomingResult = gateway.incomingRequests()
        val outgoingResult = gateway.outgoingRequests()
        if (currentUid != uid) return

        val friends = (friendsResult as? FriendOutcome.Success)?.value
        val incoming = (incomingResult as? FriendOutcome.Success)?.value
        val outgoing = (outgoingResult as? FriendOutcome.Success)?.value

        _uiState.value = _uiState.value.copy(
            phase = if (friends != null) FriendsPhase.Ready else _uiState.value.phase,
            friends = friends?.items ?: _uiState.value.friends,
            friendCount = friends?.total ?: _uiState.value.friendCount,
            incoming = incoming?.items ?: _uiState.value.incoming,
            incomingCount = incoming?.total ?: _uiState.value.incomingCount,
            outgoing = outgoing?.items ?: _uiState.value.outgoing
        )
    }

    /** O estado local ficou velho: outro aparelho ou a outra pessoa mudou algo antes. */
    private fun isStale(outcome: FriendOutcome<*>): Boolean {
        val error = (outcome as? FriendOutcome.Failure)?.error ?: return false
        return error == FriendError.REQUEST_NOT_PENDING ||
            error == FriendError.REQUEST_NOT_FOUND ||
            error == FriendError.ALREADY_FRIENDS ||
            error == FriendError.FRIENDSHIP_NOT_FOUND
    }

    private fun phaseFor(error: FriendError): FriendsPhase = when (error) {
        FriendError.NOT_CONFIGURED -> FriendsPhase.NotConfigured
        // Falhar uma operação social **não** é sair da conta: quem decide isso é o Firebase Auth
        // local, e ele continua dizendo que a sessão existe.
        FriendError.AUTH_REQUIRED -> FriendsPhase.Error(FriendError.AUTH_REQUIRED)
        FriendError.SOCIAL_NOT_ENABLED -> FriendsPhase.SocialUnavailable(disabled = false)
        FriendError.SOCIAL_DISABLED -> FriendsPhase.SocialUnavailable(disabled = true)
        FriendError.NETWORK -> FriendsPhase.Offline
        else -> FriendsPhase.Error(error)
    }
}
