package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.social.ChallengeContract
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.ChallengeDraft
import com.example.domain.social.ChallengeError
import com.example.domain.social.ChallengeGateway
import com.example.domain.social.ChallengeOutcome
import com.example.domain.social.ChallengeType
import com.example.domain.social.FriendGateway
import com.example.domain.social.FriendOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Os desafios, do ponto de vista da UI (T17.3).
 *
 * ```text
 * Social → Desafios → ChallengeViewModel → ChallengeGateway → Spark Backend
 *                                                                   │
 *                                                              autoridade
 * ```
 *
 * ## Nada acontece sozinho
 *
 * Não há requisição em `init`, em recomposição ou no listener de login. A primeira leitura sai de
 * [open], chamada quando o usuário chega na tela. Não há polling, não há background e não há
 * WebSocket: abrir ou puxar para atualizar é o que busca o placar (§152–§155).
 *
 * E **abrir um desafio não dispara sincronização de treino** (§152). O placar é o que o servidor
 * já sabe; o que ele ainda não sabe chega no próximo sync do dono daquele treino, por conta dele.
 *
 * ## Troca de conta invalida na hora
 *
 * `A vendo um desafio → logout → login B` não pode, em nenhum instante, mostrar o desafio de A
 * para B. O estado é descartado **antes** de qualquer requisição de B sair (§183), e a resposta de
 * uma requisição iniciada por A é descartada se a conta mudou no meio do voo (§184) — a mesma
 * lição da T16.7.1: o `uid` capturado antes da chamada não vale depois dela.
 *
 * O rascunho de criação também morre na troca (§183): ele carrega `socialId` de amigos de A.
 *
 * ## Offline não finge
 *
 * Sem internet, criar/aceitar/recusar/sair/cancelar **não acontecem**: não vão para a Outbox, não
 * ficam pendentes e não são reenviados. A tela diz que nada foi enviado. E nada disso toca Room,
 * treino, histórico, backup, sincronização ou gamificação — **treinar continua normal** (§150).
 *
 * ## Um toque, uma mutação
 *
 * Cada ação marca o **alvo** como ocupado e ignora toques repetidos nele. O servidor repete a
 * proteção sendo idempotente: dois aceites produzem uma participação, e o `clientRequestId` faz
 * duas criações produzirem um desafio (§187–§194).
 */
class ChallengeViewModel(
    private val gateway: ChallengeGateway,
    private val friendGateway: FriendGateway,
    private val authGateway: AuthGateway,
    /**
     * O fuso deste aparelho, **sugerido** ao servidor na criação (§10/§164).
     *
     * Ele não é escolha do usuário nesta fase, e não é autoridade: o servidor valida e é ele que
     * decide. Injetado para o teste poder fixá-lo — o padrão é o fuso real do aparelho.
     */
    private val deviceTimeZoneId: () -> String = { ZoneId.systemDefault().id },
    /** "Hoje", para propor o período inicial. Injetado pelo mesmo motivo. */
    private val today: () -> LocalDate = { LocalDate.now() },
    /** O identificador da tentativa de criação. Injetado para o teste poder observá-lo. */
    private val newClientRequestId: () -> String = { java.util.UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChallengeUiState(
            listPhase = if (gateway.isConfigured) {
                ChallengeListPhase.SignedOut
            } else {
                ChallengeListPhase.NotConfigured
            }
        )
    )
    val uiState: StateFlow<ChallengeUiState> = _uiState.asStateFlow()

    /** A conta da qual o estado atual fala. `null` = nenhuma. */
    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            // Observar a sessão é leitura: não abre seletor de contas, não ativa Social e não
            // carrega nada. O que ela faz é **invalidar** quando a conta muda.
            authGateway.state.collect { state ->
                onAccountChanged((state as? AuthState.SignedIn)?.account?.uid)
            }
        }
    }

    /**
     * A conta mudou (ou foi observada pela primeira vez).
     *
     * A invalidação vem **antes** de qualquer requisição (§183): entre o logout de A e a resposta
     * de B, a tela mostra vazio — nunca a lista, o placar, os convites ou o rascunho de A.
     */
    private fun onAccountChanged(uid: String?) {
        if (uid == currentUid) return
        currentUid = uid

        if (!gateway.isConfigured) {
            _uiState.value = ChallengeUiState(listPhase = ChallengeListPhase.NotConfigured)
            return
        }
        // Estado novo por completo. Lista, detalhe, convites, rascunho, amigos selecionáveis e
        // ocupações pertenciam à conta anterior.
        _uiState.value = ChallengeUiState(
            listPhase = if (uid == null) {
                ChallengeListPhase.SignedOut
            } else {
                ChallengeListPhase.Idle
            }
        )
    }

    // --------------------------------------------------------------------------------- lista

    /** A tela chegou. Carrega a lista e os convites, uma vez. */
    fun open() {
        if (_uiState.value.listPhase is ChallengeListPhase.Ready) return
        refresh()
    }

    /** Relê a lista e os convites. É por aqui que o placar e os convites novos aparecem (§153). */
    fun refresh() {
        // Sem endereço de Spark Backend neste build, nada é oferecido e **nada sai**: o gateway
        // recusaria de qualquer forma, e parar aqui evita uma requisição que já se sabe inútil.
        if (_uiState.value.listPhase is ChallengeListPhase.NotConfigured) return
        val uid = currentUid ?: return
        if (_uiState.value.listPhase is ChallengeListPhase.Loading) return

        _uiState.value = _uiState.value.copy(
            listPhase = ChallengeListPhase.Loading,
            notice = null
        )
        viewModelScope.launch {
            val outcome = gateway.list()
            // A conta mudou no voo: a resposta antiga não pode sobrescrever o estado novo (§184).
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                listPhase = when (outcome) {
                    is ChallengeOutcome.Success ->
                        ChallengeListPhase.Ready(outcome.value.challenges)
                    is ChallengeOutcome.Failure -> listPhaseFor(outcome.error)
                }
            )
            loadInvites(uid)
        }
    }

    private suspend fun loadInvites(uid: String) {
        _uiState.value = _uiState.value.copy(isLoadingInvites = true)
        val outcome = gateway.invites()
        if (currentUid != uid) return

        _uiState.value = _uiState.value.copy(
            isLoadingInvites = false,
            // Uma falha ao ler convites **não** apaga os que já estavam na tela: ela só não os
            // atualiza. Esvaziar faria um convite real sumir por causa de uma rede instável.
            invites = when (outcome) {
                is ChallengeOutcome.Success -> outcome.value.invites
                is ChallengeOutcome.Failure -> _uiState.value.invites
            }
        )
    }

    // --------------------------------------------------------------------------------- detalhe

    /**
     * Abre um desafio.
     *
     * Trocar de alvo descarta o anterior **antes** de pedir o novo: meio segundo mostrando o placar
     * de um desafio sob o nome de outro seria uma afirmação errada sobre duas disputas.
     */
    fun openChallenge(challengeId: String) {
        val uid = currentUid ?: return
        val state = _uiState.value
        if (state.openedChallengeId == challengeId &&
            (state.detailPhase is ChallengeDetailPhase.Ready ||
                state.detailPhase is ChallengeDetailPhase.Loading)
        ) {
            return
        }

        _uiState.value = state.copy(
            openedChallengeId = challengeId,
            detailPhase = ChallengeDetailPhase.Loading
        )
        viewModelScope.launch { loadDetail(uid, challengeId) }
    }

    /** Relê o desafio aberto. É por aqui que o placar atualiza (§153). */
    fun refreshChallenge() {
        val uid = currentUid ?: return
        val challengeId = _uiState.value.openedChallengeId ?: return
        if (_uiState.value.detailPhase is ChallengeDetailPhase.Loading) return

        _uiState.value = _uiState.value.copy(detailPhase = ChallengeDetailPhase.Loading)
        viewModelScope.launch { loadDetail(uid, challengeId) }
    }

    /** Fecha o desafio. O que foi lido é descartado: é cache, e a próxima abertura relê. */
    fun closeChallenge() {
        _uiState.value = _uiState.value.copy(
            openedChallengeId = null,
            detailPhase = ChallengeDetailPhase.Idle
        )
    }

    private suspend fun loadDetail(uid: String, challengeId: String) {
        val outcome = gateway.detail(challengeId)
        // A conta mudou, ou a tela já pediu outro desafio: descarta.
        if (currentUid != uid || _uiState.value.openedChallengeId != challengeId) return

        _uiState.value = _uiState.value.copy(
            detailPhase = when (outcome) {
                is ChallengeOutcome.Success -> ChallengeDetailPhase.Ready(outcome.value)
                is ChallengeOutcome.Failure -> detailPhaseFor(outcome.error)
            }
        )
    }

    // --------------------------------------------------------------------------------- convites

    /**
     * Aceita um convite.
     *
     * Aceitar é consentir em compartilhar, **com os participantes daquele desafio**, o nome social
     * e a pontuação dele. A tela diz isso antes do toque (§124/§168) — este método é o que
     * acontece depois de a pessoa ter lido.
     */
    fun acceptInvite(invitationId: String) {
        mutateInvitation(invitationId) { gateway.accept(invitationId) }
    }

    /** Recusa um convite. */
    fun declineInvite(invitationId: String) {
        mutateInvitation(invitationId) { gateway.decline(invitationId) }
    }

    private fun mutateInvitation(
        invitationId: String,
        action: suspend () -> ChallengeOutcome<*>
    ) {
        val uid = currentUid ?: return
        // Toque duplo no **mesmo** convite é ignorado; responder a outro continua possível (§227).
        if (invitationId in _uiState.value.pendingInvitationIds) return

        _uiState.value = _uiState.value.copy(
            pendingInvitationIds = _uiState.value.pendingInvitationIds + invitationId,
            notice = null
        )

        viewModelScope.launch {
            val outcome = action()
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                pendingInvitationIds = _uiState.value.pendingInvitationIds - invitationId,
                notice = (outcome as? ChallengeOutcome.Failure)?.error
            )
            if (outcome is ChallengeOutcome.Success) {
                // O convite saiu da lista e (no aceite) um desafio entrou: as duas coisas são do
                // servidor, e a tela relê em vez de deduzir.
                refresh()
            }
        }
    }

    // --------------------------------------------------------------------------------- membro

    /** Sai do desafio. Só membro; o criador cancela (§62/§175). */
    fun leaveChallenge(challengeId: String) {
        mutateChallenge(challengeId) { gateway.leave(challengeId) }
    }

    /** Cancela o desafio. Só quem criou (§67/§174). */
    fun cancelChallenge(challengeId: String) {
        mutateChallenge(challengeId) { gateway.cancel(challengeId) }
    }

    private fun mutateChallenge(
        challengeId: String,
        action: suspend () -> ChallengeOutcome<Unit>
    ) {
        val uid = currentUid ?: return
        if (challengeId in _uiState.value.pendingChallengeIds) return

        _uiState.value = _uiState.value.copy(
            pendingChallengeIds = _uiState.value.pendingChallengeIds + challengeId,
            notice = null
        )

        viewModelScope.launch {
            val outcome = action()
            if (currentUid != uid) return@launch

            _uiState.value = _uiState.value.copy(
                pendingChallengeIds = _uiState.value.pendingChallengeIds - challengeId,
                notice = (outcome as? ChallengeOutcome.Failure)?.error
            )
            if (outcome is ChallengeOutcome.Success) {
                refresh()
                if (_uiState.value.openedChallengeId == challengeId) {
                    // A tela do desafio continua aberta: ela precisa mostrar o estado novo
                    // (cancelado, ou a própria linha fora do placar), e não o de antes.
                    loadDetail(uid, challengeId)
                }
            }
        }
    }

    // --------------------------------------------------------------------------------- criação

    /**
     * Abre a criação, com um rascunho pré-preenchido.
     *
     * O período nasce **começando amanhã** — que é o primeiro dia que o servidor aceita (§15) — e
     * durando 30 dias. Nascer inválido faria a primeira coisa que a pessoa vê ser um erro.
     */
    fun startCreation() {
        val uid = currentUid ?: return
        val tomorrow = today().plusDays(1)

        _uiState.value = _uiState.value.copy(
            creationPhase = ChallengeCreationPhase.Editing,
            draft = ChallengeDraft(
                name = "",
                type = ChallengeType.WORKOUTS_COMPLETED,
                target = 12,
                startDate = tomorrow.toString(),
                endDate = tomorrow.plusDays(29).toString(),
                timeZoneId = deviceTimeZoneId()
            ),
            notice = null
        )
        viewModelScope.launch { loadSelectableFriends(uid) }
    }

    /**
     * Os amigos que podem ser convidados (§165).
     *
     * A lista de amigos da T17.1 já devolve **só perfis ativos**, então não há filtro extra aqui —
     * e não poderia haver um útil: quem decide se um convidado está disponível é o servidor, na
     * criação (§32). Esta lista é para escolher, não para autorizar.
     */
    private suspend fun loadSelectableFriends(uid: String) {
        val outcome = friendGateway.friends()
        if (currentUid != uid) return
        if (outcome is FriendOutcome.Success) {
            _uiState.value = _uiState.value.copy(selectableFriends = outcome.value.items)
        }
    }

    fun updateDraftName(name: String) = updateDraft { it.copy(name = name) }

    /**
     * Troca o tipo, e ajusta a meta quando ela deixou de fazer sentido.
     *
     * `ACTIVE_DAYS` não aceita meta maior que a duração (§21). Trocar de "12 treinos" para "dias
     * ativos" num desafio de 10 dias deixaria uma meta impossível na tela, e o servidor recusaria
     * na hora de criar — corrigir aqui evita um erro que a pessoa não pediu.
     */
    fun updateDraftType(type: ChallengeType) = updateDraft { draft ->
        val maxTarget = maxTargetFor(type, draft)
        draft.copy(type = type, target = draft.target.coerceIn(1, maxTarget))
    }

    fun updateDraftTarget(target: Int) = updateDraft { draft ->
        draft.copy(target = target.coerceIn(1, maxTargetFor(draft.type, draft)))
    }

    fun updateDraftPeriod(startDate: String, endDate: String) = updateDraft { draft ->
        val updated = draft.copy(startDate = startDate, endDate = endDate)
        updated.copy(target = updated.target.coerceIn(1, maxTargetFor(updated.type, updated)))
    }

    /** Marca ou desmarca um amigo. O teto inclui quem cria (§30/§166). */
    fun toggleInvited(socialId: String) = updateDraft { draft ->
        when {
            socialId in draft.invitedSocialIds ->
                draft.copy(invitedSocialIds = draft.invitedSocialIds - socialId)
            draft.invitedSocialIds.size + 1 >= ChallengeContract.Limits.MAX_PARTICIPANTS ->
                // Já está cheio: o toque não faz nada. O servidor recusaria de qualquer forma, e
                // é ele a autoridade — isto só evita oferecer o que vai falhar.
                draft
            else -> draft.copy(invitedSocialIds = draft.invitedSocialIds + socialId)
        }
    }

    private fun updateDraft(transform: (ChallengeDraft) -> ChallengeDraft) {
        if (_uiState.value.isCreating) return
        _uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft))
    }

    /** Fecha a criação e descarta o rascunho. Nenhum desafio foi criado (§186). */
    fun cancelCreation() {
        _uiState.value = _uiState.value.copy(
            creationPhase = ChallengeCreationPhase.Closed,
            draft = ChallengeDraft(),
            notice = null
        )
    }

    /**
     * Cria o desafio.
     *
     * O `clientRequestId` é gerado **uma vez por envio** e reenviado se a pessoa tentar de novo
     * depois de uma falha de rede: é ele que faz o retry devolver o desafio que já existe em vez
     * de criar um segundo (§188/§189). O botão bloqueado cobre o toque duplo rápido (§187); o
     * identificador cobre o resto.
     */
    fun submitCreation() {
        val uid = currentUid ?: return
        val state = _uiState.value
        if (state.creationPhase !is ChallengeCreationPhase.Editing) return

        val draft = state.draft
        if (!isDraftValid(draft)) {
            _uiState.value = state.copy(notice = ChallengeError.INVALID_CHALLENGE)
            return
        }

        val clientRequestId = pendingCreationId ?: newClientRequestId().also {
            pendingCreationId = it
        }

        _uiState.value = state.copy(
            creationPhase = ChallengeCreationPhase.Submitting,
            notice = null
        )

        viewModelScope.launch {
            val outcome = gateway.create(
                clientRequestId = clientRequestId,
                name = draft.name.trim(),
                type = draft.type,
                target = draft.target,
                startDate = draft.startDate,
                endDate = draft.endDate,
                timeZoneId = draft.timeZoneId,
                invitedSocialIds = draft.invitedSocialIds.toList()
            )
            if (currentUid != uid) return@launch

            when (outcome) {
                is ChallengeOutcome.Success -> {
                    pendingCreationId = null
                    _uiState.value = _uiState.value.copy(
                        creationPhase = ChallengeCreationPhase.Created(outcome.value),
                        draft = ChallengeDraft()
                    )
                    refresh()
                }
                is ChallengeOutcome.Failure -> {
                    // Volta para a edição com o rascunho intacto: a pessoa corrige e tenta de
                    // novo, com o **mesmo** `clientRequestId` — que é o que torna o retry seguro.
                    _uiState.value = _uiState.value.copy(
                        creationPhase = ChallengeCreationPhase.Editing,
                        notice = outcome.error
                    )
                }
            }
        }
    }

    /**
     * O identificador da tentativa em curso.
     *
     * Ele sobrevive a uma falha e é reusado no retry (§189), e é limpo no sucesso e ao fechar a
     * tela. Fora do `UiState` de propósito: ele não é informação de tela, e colocá-lo lá o faria
     * atravessar recomposições como se fosse.
     */
    private var pendingCreationId: String? = null

    /** A criação terminou e a tela foi vista. Volta ao estado fechado. */
    fun creationHandled() {
        pendingCreationId = null
        _uiState.value = _uiState.value.copy(creationPhase = ChallengeCreationPhase.Closed)
    }

    /** Descarta o aviso pontual da última ação. */
    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    // --------------------------------------------------------------------------------- regras

    /**
     * O rascunho está preenchido o suficiente para ser enviado?
     *
     * Validação **de tela**, e não de domínio: ela evita uma ida ao servidor que já se sabe que
     * falharia. A autoridade continua sendo o servidor, que revalida tudo (§166) — inclusive
     * "começa amanhã", que depende do relógio dele e não do daqui.
     */
    fun isDraftValid(draft: ChallengeDraft = _uiState.value.draft): Boolean {
        val name = draft.name.trim()
        if (name.length < ChallengeContract.Limits.NAME_MIN_LENGTH) return false
        if (name.length > ChallengeContract.Limits.NAME_MAX_LENGTH) return false
        if (draft.timeZoneId.isBlank()) return false

        val days = durationDaysOf(draft) ?: return false
        if (days < ChallengeContract.Limits.MIN_DURATION_DAYS) return false
        if (days > ChallengeContract.Limits.MAX_DURATION_DAYS) return false

        if (draft.target < ChallengeContract.Limits.MIN_TARGET) return false
        if (draft.target > maxTargetFor(draft.type, draft)) return false

        // Um desafio sozinho não é um desafio (§29). A tela exige pelo menos um convidado; o
        // servidor não exige — ele apenas trata um desafio sem participantes como `VOID`.
        return draft.invitedSocialIds.isNotEmpty()
    }

    /** O teto da meta: absoluto para treinos, e a própria duração para dias ativos (§20/§21). */
    private fun maxTargetFor(type: ChallengeType, draft: ChallengeDraft): Int = when (type) {
        ChallengeType.WORKOUTS_COMPLETED -> ChallengeContract.Limits.MAX_WORKOUTS_TARGET
        ChallengeType.ACTIVE_DAYS ->
            durationDaysOf(draft) ?: ChallengeContract.Limits.MAX_DURATION_DAYS
    }

    /** Dias inclusivos entre as duas datas, ou `null` quando elas não formam um período. */
    private fun durationDaysOf(draft: ChallengeDraft): Int? {
        val start = runCatching { LocalDate.parse(draft.startDate) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(draft.endDate) }.getOrNull() ?: return null
        if (end.isBefore(start)) return null
        return (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1).toInt()
    }

    private fun listPhaseFor(error: ChallengeError): ChallengeListPhase = when (error) {
        ChallengeError.NETWORK -> ChallengeListPhase.Offline
        ChallengeError.NOT_CONFIGURED -> ChallengeListPhase.NotConfigured
        ChallengeError.AUTH_REQUIRED -> ChallengeListPhase.SignedOut
        ChallengeError.SOCIAL_NOT_ENABLED -> ChallengeListPhase.SocialUnavailable(disabled = false)
        ChallengeError.SOCIAL_DISABLED -> ChallengeListPhase.SocialUnavailable(disabled = true)
        else -> ChallengeListPhase.Error(error)
    }

    private fun detailPhaseFor(error: ChallengeError): ChallengeDetailPhase = when (error) {
        ChallengeError.NETWORK -> ChallengeDetailPhase.Offline
        ChallengeError.NOT_FOUND -> ChallengeDetailPhase.NotAvailable
        else -> ChallengeDetailPhase.Error(error)
    }
}
