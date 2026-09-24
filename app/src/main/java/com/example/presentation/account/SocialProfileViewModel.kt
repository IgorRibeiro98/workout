package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.ProgressSharing
import com.example.domain.social.ProgressSharingField
import com.example.domain.social.SocialConsistencyParameters
import com.example.domain.social.SocialProfileError
import com.example.domain.social.SocialProfileGateway
import com.example.domain.social.SocialProfileOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * O perfil social enriquecido, do ponto de vista da UI (T17.2).
 *
 * ```text
 * Amigos ──toque em um nome──▶ FriendSocialProfileScreen ─┐
 *                                                         ├─▶ SocialProfileGateway ─▶ Backend
 * Perfil ──▶ Compartilhar progresso ──▶ Pré-visualizar ───┘
 * ```
 *
 * ## Nada acontece sozinho, e nada acontece em lote
 *
 * Não há requisição em `init`, em recomposição nem no listener de login. Um perfil é lido quando
 * alguém **toca em um amigo** (§64) — nunca ao abrir a lista, e nunca uma requisição por linha
 * (§65). A lista de amigos da T17.1 continua exatamente tão leve quanto era.
 *
 * ## Troca de conta invalida na hora
 *
 * `A vendo o perfil de B → logout → login C` não pode, em nenhum instante, mostrar o perfil de B
 * para C. O estado é descartado **antes** de qualquer requisição de C sair (§109), e a resposta de
 * uma requisição iniciada por A é descartada se a conta mudou no meio do voo (§110) — a mesma
 * lição da T16.7.1: o `uid` capturado antes da chamada não vale depois dela.
 *
 * ## Offline não finge
 *
 * Sem internet, alterar o compartilhamento **não acontece**: não vai para a Outbox, não fica
 * pendente e não é reenviado (§107/§108). O interruptor volta para onde estava e a tela diz que
 * nada foi enviado. Numa tela de privacidade, um "salvo" que não salvou é o pior desfecho
 * possível.
 *
 * ## Sem atualização otimista
 *
 * O interruptor só se move depois que o servidor confirmou (§106). A alternativa exigiria um
 * rollback correto para valer a pena, e um interruptor de privacidade que se move e volta sozinho
 * deixa a pessoa sem saber o que está valendo.
 */
class SocialProfileViewModel(
    private val gateway: SocialProfileGateway,
    private val authGateway: AuthGateway,
    /**
     * O fuso deste aparelho, enviado junto com a primeira alteração.
     *
     * Ele **não** é progresso: é o parâmetro que permite ao servidor usar a mesma semana canônica
     * da tela de consistência (segunda a domingo, na data local). Injetado para o teste poder
     * fixá-lo — o padrão é o fuso real do aparelho.
     */
    private val deviceTimeZoneId: () -> String = { java.util.TimeZone.getDefault().id },
    /**
     * Os parâmetros de consistência deste aparelho (T19.2A): meta por semana e início do
     * acompanhamento, lidos das autoridades locais de sempre — por quem monta a ViewModel, nunca
     * por ela. `null` quando o aparelho ainda não os tem (acompanhamento não inicializado) ou
     * quando o build não os fornece.
     *
     * Eles **não** são progresso: são a configuração que o servidor precisa para derivar a mesma
     * sequência que a tela de consistência mostra. Sequência, nível, XP e conquista continuam
     * não existindo como parâmetro em lugar nenhum desta classe.
     */
    private val consistencyParameters: (suspend () -> SocialConsistencyParameters?)? = null,
    private val blockGateway: com.example.domain.social.BlockGateway? = null,
    private val reportGateway: com.example.domain.social.ReportGateway? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SocialProfileUiState(isConfigured = gateway.isConfigured))
    val uiState: StateFlow<SocialProfileUiState> = _uiState.asStateFlow()

    /** A conta da qual o estado atual fala. `null` = nenhuma. */
    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            // Observar a sessão é leitura: não abre seletor de contas e não carrega nada. O que
            // ela faz é **invalidar** quando a conta muda.
            authGateway.state.collect { state ->
                onAccountChanged((state as? AuthState.SignedIn)?.account?.uid)
            }
        }
    }

    /**
     * A conta mudou (ou foi observada pela primeira vez).
     *
     * A invalidação vem **antes** de qualquer requisição: entre o logout de A e a resposta de C, a
     * tela mostra vazio — nunca o perfil que A estava vendo, e nunca a configuração de A.
     */
    private fun onAccountChanged(uid: String?) {
        if (uid == currentUid) return
        currentUid = uid
        // Estado novo por completo: perfil aberto, configurações, disponibilidade e prévia.
        // Tudo pertencia à conta anterior.
        _uiState.value = SocialProfileUiState(isConfigured = gateway.isConfigured)
    }

    // --------------------------------------------------------------------------- perfil do amigo

    /**
     * Abre o perfil de um amigo.
     *
     * Idempotente para o **mesmo** alvo já carregado: voltar para a tela não refaz a requisição.
     * Trocar de alvo descarta o anterior antes de pedir o novo — meio segundo mostrando o perfil do
     * Igor no cabeçalho do João seria uma afirmação errada sobre duas pessoas.
     */
    fun openFriendProfile(socialId: String) {
        val uid = currentUid ?: return
        val state = _uiState.value
        if (state.openedSocialId == socialId &&
            (state.friendPhase is FriendProfilePhase.Ready ||
                state.friendPhase is FriendProfilePhase.NoSharedProgress ||
                state.friendPhase is FriendProfilePhase.Loading)
        ) {
            return
        }

        _uiState.value = state.copy(
            openedSocialId = socialId,
            friendPhase = FriendProfilePhase.Loading
        )
        viewModelScope.launch { loadFriendProfile(uid, socialId) }
    }

    /**
     * Relê o perfil aberto. É por aqui que "desfizeram a amizade" e "mudou a privacidade" aparecem.
     *
     * O "↻" da barra (T19.H3): com o perfil na tela, ele fica enquanto a releitura voa. Uma falha
     * de rede mantém a última leitura boa com um aviso; uma resposta que muda o que a tela é —
     * "perfil indisponível" depois de desfazer a amizade ou bloquear — substitui a tela, sempre.
     */
    fun refreshFriendProfile() {
        val uid = currentUid ?: return
        val socialId = _uiState.value.openedSocialId ?: return
        val state = _uiState.value
        if (state.friendPhase is FriendProfilePhase.Loading || state.isFriendProfileRefreshing) return

        _uiState.value = if (state.friendPhase.showsProfile()) {
            state.copy(isFriendProfileRefreshing = true, friendStaleNotice = null)
        } else {
            state.copy(friendPhase = FriendProfilePhase.Loading, friendStaleNotice = null)
        }
        viewModelScope.launch { loadFriendProfile(uid, socialId) }
    }

    /** Fecha o perfil. Descarta o que foi lido: ele é cache, e a próxima abertura relê. */
    fun closeFriendProfile() {
        _uiState.value = _uiState.value.copy(
            openedSocialId = null,
            friendPhase = FriendProfilePhase.Idle,
            isFriendProfileRefreshing = false,
            friendStaleNotice = null
        )
    }

    private suspend fun loadFriendProfile(uid: String, socialId: String) {
        val outcome = gateway.friendProfile(socialId)
        // A conta mudou no voo, ou a tela já pediu outro alvo: a resposta antiga não pode
        // sobrescrever o estado novo.
        if (currentUid != uid || _uiState.value.openedSocialId != socialId) return

        val state = _uiState.value
        if (outcome is SocialProfileOutcome.Failure && state.friendPhase.showsProfile() &&
            outcome.error.isRecoverableRead()
        ) {
            _uiState.value = state.copy(
                isFriendProfileRefreshing = false,
                friendStaleNotice = outcome.error
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isFriendProfileRefreshing = false,
            friendStaleNotice = null,
            friendPhase = when (outcome) {
                is SocialProfileOutcome.Success -> {
                    val profile = outcome.value
                    if (profile.sharedProgress.isEmpty) {
                        FriendProfilePhase.NoSharedProgress(profile)
                    } else {
                        FriendProfilePhase.Ready(profile)
                    }
                }
                is SocialProfileOutcome.Failure -> friendPhaseFor(outcome.error)
            }
        )
    }

    // --------------------------------------------------------------------------- minhas configurações

    /** Carrega minhas configurações, se ainda não estiverem carregadas. */
    fun openProgressSharing() {
        val phase = _uiState.value.sharingPhase
        if (phase is ProgressSharingPhase.Ready || phase is ProgressSharingPhase.Loading) return
        refreshProgressSharing()
    }

    /**
     * Relê minhas configurações e a disponibilidade de cada campo — o "↻" da barra e o "tentar de
     * novo" (T19.H3).
     *
     * Com os interruptores na tela, eles ficam enquanto a releitura voa, e uma falha os mantém
     * com um aviso: ler de novo não pode fazer uma tela de privacidade "esquecer" o que mostrava.
     */
    fun refreshProgressSharing() {
        val uid = currentUid ?: return
        val state = _uiState.value
        if (state.isSharingBusy || state.isSharingRefreshing) return

        val showing = state.sharingPhase is ProgressSharingPhase.Ready
        _uiState.value = if (showing) {
            state.copy(isSharingRefreshing = true, notice = null)
        } else {
            state.copy(sharingPhase = ProgressSharingPhase.Loading, notice = null)
        }
        viewModelScope.launch {
            val outcome = gateway.progressSharing()
            if (currentUid != uid) return@launch
            _uiState.value = _uiState.value.copy(isSharingRefreshing = false)
            // Um interruptor foi tocado enquanto a leitura voava: a resposta do `PATCH` é mais
            // nova que esta, e é ela quem escreve (T19.H3 §47).
            if (showing && _uiState.value.sharingPhase is ProgressSharingPhase.Saving) return@launch
            applySharing(
                outcome,
                busyPhase = if (showing) ProgressSharingPhase.Saving else ProgressSharingPhase.Loading
            )
            if (outcome is SocialProfileOutcome.Success) {
                alignConsistencyParameters(uid, outcome.value.settings.consistency)
            }
        }
    }

    /**
     * Declara ao servidor os parâmetros de consistência deste aparelho quando ele ainda não os
     * conhece — ou conhece uma versão antiga (a meta semanal mudou desde a última visita).
     *
     * É a única escrita que esta tela faz sem um toque: ela não move interruptor nenhum e não
     * altera privacidade. Sem ela, quem ligou "Consistência semanal" numa versão anterior veria o
     * campo indisponível para sempre, porque nada mais o faria chegar ao servidor. Falhar aqui
     * não gera aviso: a disponibilidade na tela já diz "ainda não disponível", e a próxima abertura
     * tenta de novo.
     */
    private suspend fun alignConsistencyParameters(
        uid: String,
        known: SocialConsistencyParameters?
    ) {
        val local = localConsistencyParameters() ?: return
        if (local == known) return
        if (currentUid != uid || _uiState.value.isSharingBusy) return

        // `Saving` enquanto a escrita voa: um toque neste intervalo esperaria a resposta e, se as
        // duas respostas se cruzassem, a mais antiga poderia sobrescrever o interruptor mais novo.
        _uiState.value = _uiState.value.copy(sharingPhase = ProgressSharingPhase.Saving)
        val outcome = gateway.updateProgressSharing(consistency = local)
        if (currentUid != uid) return
        _uiState.value = when (outcome) {
            is SocialProfileOutcome.Success -> _uiState.value.copy(
                sharingPhase = ProgressSharingPhase.Ready,
                settings = outcome.value.settings,
                availability = outcome.value.availability
            )
            // Sem aviso: nenhum interruptor foi tocado, e a disponibilidade já diz o que falta.
            is SocialProfileOutcome.Failure -> _uiState.value.copy(sharingPhase = ProgressSharingPhase.Ready)
        }
    }

    private suspend fun localConsistencyParameters(): SocialConsistencyParameters? =
        try {
            consistencyParameters?.invoke()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Liga ou desliga um campo.
     *
     * O `weekTimeZone` viaja junto na primeira alteração que o exigir: sem ele, a contagem semanal
     * ficaria indisponível para sempre, e a pessoa teria ligado um interruptor que nunca publica.
     * Ele não é escolha do usuário — é o fuso do aparelho, e o servidor precisa dele para usar a
     * **mesma** semana que a tela de consistência usa.
     */
    fun setShareLevel(enabled: Boolean) = setShare(ProgressSharingField.LEVEL, enabled)

    fun setShareConsistencyStreak(enabled: Boolean) =
        setShare(ProgressSharingField.CONSISTENCY_STREAK, enabled)

    fun setShareWeeklyWorkoutCount(enabled: Boolean) =
        setShare(ProgressSharingField.WEEKLY_WORKOUT_COUNT, enabled)

    fun setShareHighlightedAchievements(enabled: Boolean) =
        setShare(ProgressSharingField.HIGHLIGHTED_ACHIEVEMENTS, enabled)

    /**
     * Liga ou desliga **um** interruptor (T19.H3: os quinze passam por aqui).
     *
     * Um toque, uma mudança: o `PATCH` leva só este campo, e o servidor mantém os outros. O
     * interruptor na tela só se move quando a resposta confirma — offline, nada finge ter sido
     * salvo (§45).
     */
    fun setShare(field: ProgressSharingField, enabled: Boolean) = update(mapOf(field to enabled))

    private fun update(changes: Map<ProgressSharingField, Boolean>) {
        val uid = currentUid ?: return
        if (_uiState.value.isSharingBusy) return

        // O fuso só é enviado quando o servidor ainda não o conhece: ele descreve o aparelho, e
        // reenviá-lo a cada toque mudaria a semana de quem viaja no meio de uma configuração.
        val timeZone = if (_uiState.value.settings.weekTimeZone == null) deviceTimeZoneId() else null

        _uiState.value = _uiState.value.copy(
            sharingPhase = ProgressSharingPhase.Saving,
            notice = null
        )

        viewModelScope.launch {
            // Os parâmetros de consistência viajam quando o servidor não os tem ou tem outros —
            // a mesma regra do fuso, pelo mesmo motivo: sem eles, "Consistência semanal" e "Nível"
            // ficariam indisponíveis para sempre.
            val local = localConsistencyParameters()
            val consistency = if (local != null && local != _uiState.value.settings.consistency) local else null

            val outcome = gateway.updateProgressSharing(
                changes = changes,
                weekTimeZone = timeZone,
                consistency = consistency
            )
            if (currentUid != uid) return@launch
            applySharing(outcome, busyPhase = ProgressSharingPhase.Saving)

            // Uma configuração nova muda o que o amigo vê: a prévia que estava na tela deixou de
            // descrever a realidade, e mantê-la seria mostrar o perfil de antes.
            if (outcome is SocialProfileOutcome.Success && _uiState.value.preview != null) {
                loadPreview(uid)
            }
        }
    }

    /**
     * Aplica o resultado de uma leitura ou de uma escrita.
     *
     * Numa falha, os interruptores **não** se movem: o estado anterior continua na tela, e o aviso
     * diz o que aconteceu. É isso que impede "desliguei e o servidor não recebeu" de virar
     * "desliguei" na cabeça de quem olhou.
     */
    private fun applySharing(
        outcome: SocialProfileOutcome<ProgressSharing>,
        busyPhase: ProgressSharingPhase
    ) {
        when (outcome) {
            is SocialProfileOutcome.Success -> {
                _uiState.value = _uiState.value.copy(
                    sharingPhase = ProgressSharingPhase.Ready,
                    settings = outcome.value.settings,
                    availability = outcome.value.availability
                )
            }
            is SocialProfileOutcome.Failure -> {
                val wasSaving = busyPhase is ProgressSharingPhase.Saving
                _uiState.value = _uiState.value.copy(
                    // Uma escrita que falhou não derruba a tela: o que já estava lido continua
                    // válido, e o aviso explica. Uma leitura que falhou não tem o que mostrar.
                    sharingPhase = if (wasSaving) ProgressSharingPhase.Ready
                    else sharingPhaseFor(outcome.error),
                    notice = if (wasSaving) outcome.error else null
                )
            }
        }
    }

    // --------------------------------------------------------------------------- prévia

    /**
     * Pede ao servidor exatamente o que um amigo veria de mim agora.
     *
     * É o **mesmo** pipeline do perfil de amigo, do outro lado: nenhuma lógica de prévia existe no
     * app. Uma prévia montada localmente a partir dos interruptores mostraria o que eu liguei, e
     * não o que o servidor consegue publicar — que é justamente a diferença que esta tela existe
     * para revelar.
     */
    fun loadPreview() {
        val uid = currentUid ?: return
        if (_uiState.value.isPreviewLoading) return
        viewModelScope.launch { loadPreview(uid) }
    }

    private suspend fun loadPreview(uid: String) {
        _uiState.value = _uiState.value.copy(isPreviewLoading = true, notice = null)
        val outcome = gateway.profilePreview()
        if (currentUid != uid) return

        _uiState.value = when (outcome) {
            is SocialProfileOutcome.Success -> _uiState.value.copy(
                isPreviewLoading = false,
                preview = outcome.value
            )
            is SocialProfileOutcome.Failure -> _uiState.value.copy(
                isPreviewLoading = false,
                notice = outcome.error
            )
        }
    }

    /** Fecha a prévia. */
    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(preview = null)
    }

    /** Descarta o aviso pontual da última ação. */
    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    private fun friendPhaseFor(error: SocialProfileError): FriendProfilePhase = when (error) {
        SocialProfileError.NETWORK -> FriendProfilePhase.Offline
        // "Não somos mais amigos", "ela desativou o Social" e "não existe" chegam todos como
        // `PROFILE_UNAVAILABLE`, e o app não tenta distingui-los.
        SocialProfileError.PROFILE_UNAVAILABLE -> FriendProfilePhase.NotAvailable
        else -> FriendProfilePhase.Error(error)
    }

    private fun sharingPhaseFor(error: SocialProfileError): ProgressSharingPhase = when (error) {
        SocialProfileError.NETWORK -> ProgressSharingPhase.Offline
        SocialProfileError.SOCIAL_NOT_ENABLED -> ProgressSharingPhase.SocialUnavailable(disabled = false)
        SocialProfileError.SOCIAL_DISABLED -> ProgressSharingPhase.SocialUnavailable(disabled = true)
        else -> ProgressSharingPhase.Error(error)
    }

    /** Bloqueia o usuário do perfil social visualizado (T17.6). */
    fun blockUser(
        socialId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val activeBlockGateway = blockGateway ?: run {
            onError("Bloqueio não configurado neste build.")
            return
        }
        viewModelScope.launch {
            when (val outcome = activeBlockGateway.blockUser(socialId)) {
                is com.example.domain.social.BlockOutcome.Success -> {
                    closeFriendProfile()
                    onSuccess()
                }
                is com.example.domain.social.BlockOutcome.Failure -> {
                    val msg = when (outcome.error) {
                        com.example.domain.social.BlockError.CANNOT_BLOCK_SELF -> "Você não pode bloquear a si mesmo."
                        com.example.domain.social.BlockError.PROFILE_NOT_FOUND -> "Usuário não encontrado."
                        com.example.domain.social.BlockError.RATE_LIMITED -> "Muitas requisições. Tente mais tarde."
                        com.example.domain.social.BlockError.NETWORK -> "Sem conexão com a internet."
                        else -> "Não foi possível bloquear este usuário."
                    }
                    onError(msg)
                }
            }
        }
    }

    /** Denuncia o usuário por motivo específico (T17.6). */
    fun reportUser(
        socialId: String,
        reason: com.example.domain.social.ReportReason,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val activeReportGateway = reportGateway ?: run {
            onError("Denúncia não configurada neste build.")
            return
        }
        viewModelScope.launch {
            when (val outcome = activeReportGateway.reportUser(socialId, reason)) {
                is com.example.domain.social.ReportOutcome.Success -> onSuccess()
                is com.example.domain.social.ReportOutcome.Failure -> {
                    val msg = when (outcome.error) {
                        com.example.domain.social.ReportError.CANNOT_REPORT_SELF -> "Você não pode denunciar a si mesmo."
                        com.example.domain.social.ReportError.NO_LEGITIMATE_CONTEXT -> "Não é possível denunciar sem contexto social compartilhado."
                        com.example.domain.social.ReportError.RATE_LIMITED -> "Limite de denúncias atingido. Tente novamente mais tarde."
                        com.example.domain.social.ReportError.NETWORK -> "Sem conexão com a internet."
                        else -> "Não foi possível enviar a denúncia."
                    }
                    onError(msg)
                }
            }
        }
    }
}

/** O perfil do amigo está na tela (com ou sem progresso compartilhado)? */
private fun FriendProfilePhase.showsProfile(): Boolean =
    this is FriendProfilePhase.Ready || this is FriendProfilePhase.NoSharedProgress

/** Falhas que não dizem nada sobre o perfil — só que ele não pôde ser relido agora (T19.H3). */
private fun SocialProfileError.isRecoverableRead(): Boolean =
    this == SocialProfileError.NETWORK ||
        this == SocialProfileError.UNAVAILABLE ||
        this == SocialProfileError.RATE_LIMITED
