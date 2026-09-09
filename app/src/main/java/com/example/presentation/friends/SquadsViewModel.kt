package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.social.SocialGroupContract
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SocialGroup
import com.example.domain.social.SocialGroupError
import com.example.domain.social.SocialGroupGateway
import com.example.domain.social.SocialGroupInvitation
import com.example.domain.social.SocialGroupOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A fase de leitura da lista de Squads. */
sealed interface SquadsPhase {
    data object Loading : SquadsPhase

    /** Carregou. Uma lista vazia é um estado normal — e não um erro. */
    data class Success(
        val groups: List<SocialGroup>,
        val invitations: List<SocialGroupInvitation>
    ) : SquadsPhase

    /** Sem rede. O núcleo do Spark continua funcionando (§116). */
    data object Offline : SquadsPhase

    /** É preciso entrar na Conta Spark. */
    data object SignedOut : SquadsPhase

    /** A conta não tem perfil social ativo. */
    data object SocialNotEnabled : SquadsPhase

    /** Não há Spark Backend neste build: Squads simplesmente não existem aqui (§116). */
    data object NotConfigured : SquadsPhase

    data class Error(val message: String) : SquadsPhase
}

data class SquadsUiState(
    val phase: SquadsPhase = SquadsPhase.Loading,
    val isRefreshing: Boolean = false,
    /** `true` enquanto a criação está em voo. Ocupação **por operação**, nunca um `isLoading`. */
    val isCreating: Boolean = false,
    /** O convite cuja resposta está em andamento. Ocupação **por alvo**. */
    val respondingInvitationId: String? = null,
    val notice: String? = null
) {
    val groups: List<SocialGroup>
        get() = (phase as? SquadsPhase.Success)?.groups.orEmpty()

    val invitations: List<SocialGroupInvitation>
        get() = (phase as? SquadsPhase.Success)?.invitations.orEmpty()

    /** §18 — a tela não oferece o que o servidor vai recusar; quem decide continua sendo ele. */
    val canCreateGroup: Boolean
        get() = groups.count { it.isOwner } < SocialGroupContract.Limits.MAX_OWNED_GROUPS

    private val SocialGroup.isOwner: Boolean
        get() = role == com.example.domain.social.SocialGroupRole.OWNER
}

/**
 * A lista de Squads e os convites recebidos (T17.11, Etapa E).
 *
 * ## O que ela guarda, e por quanto tempo
 *
 * Memória, e só memória (§113). Não existe entidade Room de Squad, não existe DataStore e não
 * existe cache em disco (§114): uma cópia local continuaria mostrando o grupo que foi excluído, o
 * membro que saiu e o convite que já foi respondido em outro aparelho. Quem decide o que aparece é
 * o servidor, **a cada leitura**.
 *
 * ## Troca de conta (§112/§163/§164)
 *
 * O estado é limpo **antes** de a requisição da conta nova sair, e a resposta de uma requisição da
 * conta anterior é descartada comparando o `uid` de agora com o de então. Sair da conta limpa a
 * lista imediatamente. É o mesmo desenho do `SocialFeedViewModel`, e pela mesma razão: um Squad da
 * conta A desenhado na sessão de B é vazamento, e não atraso.
 *
 * ## O que ela nunca faz
 *
 * Sem polling (§88), sem WebSocket (§89), sem push de feed (§95). O que busca é abrir a tela e o
 * "puxar para atualizar" — nada roda em background, e nada se agenda.
 */
class SquadsViewModel(
    private val gateway: SocialGroupGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(SquadsUiState())
    val uiState: StateFlow<SquadsUiState> = _uiState.asStateFlow()

    private var observedUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid == observedUid) return@collect
                observedUid = newUid

                // Limpar vem primeiro, sempre: a lista da conta anterior não pode ficar na tela
                // enquanto a leitura da conta nova corre (§163).
                _uiState.value = SquadsUiState(
                    phase = if (newUid == null) SquadsPhase.SignedOut else SquadsPhase.Loading
                )
                if (newUid != null) load(newUid, refreshing = false)
            }
        }
    }

    /** Abrir a tela busca. Chamar de novo enquanto uma leitura corre não abre outra. */
    fun open() {
        val uid = currentUid() ?: run {
            _uiState.value = SquadsUiState(phase = SquadsPhase.SignedOut)
            return
        }
        if (_uiState.value.isRefreshing) return
        load(uid, refreshing = false)
    }

    fun refresh() {
        val uid = currentUid() ?: run {
            _uiState.value = SquadsUiState(phase = SquadsPhase.SignedOut)
            return
        }
        if (_uiState.value.isRefreshing) return
        load(uid, refreshing = true)
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /**
     * Cria um Squad (§16/§134).
     *
     * O `clientRequestId` nasce **aqui**, uma vez por tentativa do usuário, e não a cada
     * requisição: é ele que faz o toque duplo e o retry de resposta perdida convergirem em um Squad
     * só (§146). A validação local de comprimento existe para dar resposta imediata; quem recusa de
     * verdade é o servidor (§8).
     */
    fun createGroup(name: String) {
        val uid = currentUid() ?: return
        if (_uiState.value.isCreating) return

        val trimmed = name.trim()
        if (trimmed.length < SocialGroupContract.Limits.MIN_NAME_LENGTH ||
            trimmed.length > SocialGroupContract.Limits.MAX_NAME_LENGTH
        ) {
            _uiState.update {
                it.copy(
                    notice = "O nome precisa ter entre " +
                        "${SocialGroupContract.Limits.MIN_NAME_LENGTH} e " +
                        "${SocialGroupContract.Limits.MAX_NAME_LENGTH} caracteres."
                )
            }
            return
        }

        _uiState.update { it.copy(isCreating = true, notice = null) }
        val clientRequestId = UUID.randomUUID().toString()

        viewModelScope.launch {
            val outcome = gateway.createGroup(trimmed, clientRequestId)
            if (currentUid() != uid) return@launch

            when (outcome) {
                is SocialGroupOutcome.Success -> {
                    _uiState.update { it.copy(isCreating = false, notice = "Squad criado.") }
                    load(uid, refreshing = true)
                }

                is SocialGroupOutcome.Failure -> _uiState.update {
                    it.copy(isCreating = false, notice = noticeFor(outcome.error))
                }
            }
        }
    }

    /** Aceita um convite (§138). O servidor revalida amizade, bloqueio e capacidade (§29). */
    fun acceptInvitation(invitationId: String) {
        respond(invitationId) { gateway.acceptInvitation(invitationId) }
    }

    /** Recusa um convite (§104/§138). */
    fun declineInvitation(invitationId: String) {
        respond(invitationId) { gateway.declineInvitation(invitationId) }
    }

    // ------------------------------------------------------------------ internas

    private fun respond(
        invitationId: String,
        action: suspend () -> SocialGroupOutcome<*>
    ) {
        val uid = currentUid() ?: return
        if (_uiState.value.respondingInvitationId != null) return

        _uiState.update { it.copy(respondingInvitationId = invitationId, notice = null) }

        viewModelScope.launch {
            val outcome = action()
            if (currentUid() != uid) return@launch

            when (outcome) {
                is SocialGroupOutcome.Success -> {
                    // Remoção local imediata **mais** releitura: o convite some sem piscar, e a
                    // verdade continua vindo do servidor.
                    _uiState.update { state ->
                        val phase = state.phase
                        state.copy(
                            respondingInvitationId = null,
                            phase = if (phase is SquadsPhase.Success) {
                                phase.copy(
                                    invitations = phase.invitations.filterNot {
                                        it.invitationId == invitationId
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
                    it.copy(
                        respondingInvitationId = null,
                        notice = noticeFor(outcome.error)
                    )
                }
            }
        }
    }

    /**
     * Uma leitura busca as duas listas.
     *
     * Duas requisições e não uma: o servidor não tem — e não deveria ter — uma rota que devolva
     * "meus squads e meus convites" juntos, porque são autorizações diferentes. O que a tela faz é
     * esperar as duas antes de trocar a fase, para não desenhar meia tela.
     */
    private fun load(uid: String, refreshing: Boolean) {
        if (!gateway.isConfigured) {
            _uiState.value = SquadsUiState(phase = SquadsPhase.NotConfigured)
            return
        }

        _uiState.update {
            it.copy(
                isRefreshing = refreshing,
                phase = if (refreshing) it.phase else SquadsPhase.Loading
            )
        }

        viewModelScope.launch {
            val groupsOutcome = gateway.groups()
            // §164 — uma resposta iniciada para A que chega depois do login de B é descartada.
            if (currentUid() != uid) return@launch

            if (groupsOutcome is SocialGroupOutcome.Failure) {
                _uiState.value = SquadsUiState(phase = phaseFor(groupsOutcome.error))
                return@launch
            }

            val invitationsOutcome = gateway.invitations()
            if (currentUid() != uid) return@launch

            if (invitationsOutcome is SocialGroupOutcome.Failure) {
                _uiState.value = SquadsUiState(phase = phaseFor(invitationsOutcome.error))
                return@launch
            }

            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    phase = SquadsPhase.Success(
                        groups = (groupsOutcome as SocialGroupOutcome.Success).data,
                        invitations = (invitationsOutcome as SocialGroupOutcome.Success).data
                    )
                )
            }
        }
    }

    private fun currentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid

    private fun phaseFor(error: SocialGroupError): SquadsPhase = when (error) {
        SocialGroupError.NETWORK -> SquadsPhase.Offline
        SocialGroupError.AUTH_REQUIRED -> SquadsPhase.SignedOut
        SocialGroupError.SOCIAL_NOT_ENABLED -> SquadsPhase.SocialNotEnabled
        SocialGroupError.NOT_CONFIGURED -> SquadsPhase.NotConfigured
        else -> SquadsPhase.Error("Não foi possível carregar seus squads agora.")
    }

    /**
     * O texto de cada recusa.
     *
     * Uma frase por classe de erro, e nenhuma delas repete mensagem do servidor: o servidor descreve
     * a **forma** do defeito para quem lê log, e a tela descreve o que a pessoa pode fazer.
     */
    private fun noticeFor(error: SocialGroupError): String = when (error) {
        SocialGroupError.NETWORK -> "Sem conexão. Nada foi enviado."
        SocialGroupError.AUTH_REQUIRED -> "Entre na Conta Spark para usar os Squads."
        SocialGroupError.SOCIAL_NOT_ENABLED -> "Ative seu perfil social para usar os Squads."
        SocialGroupError.NOT_CONFIGURED -> "Os Squads não estão disponíveis nesta versão."
        SocialGroupError.OWNED_LIMIT_REACHED ->
            "Você já criou ${SocialGroupContract.Limits.MAX_OWNED_GROUPS} squads."
        SocialGroupError.MEMBERSHIP_LIMIT_REACHED -> "Você já participa de squads demais."
        SocialGroupError.GROUP_FULL ->
            "Este squad já tem ${SocialGroupContract.Limits.MAX_MEMBERS} participantes."
        SocialGroupError.INVITATION_NOT_AVAILABLE -> "Este convite não está mais disponível."
        SocialGroupError.GROUP_NOT_FOUND -> "Este squad não está mais disponível."
        SocialGroupError.INVALID_NAME -> "Escolha outro nome para o squad."
        SocialGroupError.RATE_LIMITED -> "Muitas ações seguidas. Tente em instantes."
        SocialGroupError.UNAVAILABLE -> "O servidor está indisponível. Tente novamente."
        else -> "Não foi possível concluir agora."
    }
}
