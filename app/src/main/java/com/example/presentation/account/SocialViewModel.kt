package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.social.SocialContract
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SocialError
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import com.example.domain.social.SocialProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Os recursos sociais, do ponto de vista da UI (T17.0).
 *
 * ```text
 * Perfil → SocialViewModel → SocialGateway → Spark Backend
 *                                                 │
 *                                            autoridade
 * ```
 *
 * ## Nada acontece sozinho
 *
 * Não há ativação em `init`, ao abrir a tela, em recomposição ou no listener de login. O que
 * acontece ao observar a sessão é o oposto disso: o app **lê** o perfil que já existe para saber o
 * que mostrar — e uma leitura nunca cria perfil, porque `GET /v1/social/me` responde
 * `{ enabled: false }` sem escrever nada.
 *
 * Ativar exige dois atos explícitos, nesta ordem: tocar em "Ativar recursos sociais" (que abre a
 * explicação do que será criado) e confirmar.
 *
 * ## O cache é cache
 *
 * [SocialUiState.profile] é a última leitura, em memória, dentro deste ViewModel. Ele não é
 * gravado no Room, no DataStore nem em arquivo, e **não** é autoridade: toda escrita vai ao
 * servidor e a tela passa a mostrar o que o servidor devolveu, não o que ela supôs.
 *
 * ## Troca de conta invalida na hora
 *
 * `A logado → social A carregado → logout → login B` não pode, em nenhum instante, mostrar o
 * perfil de A como se fosse de B. O estado account-scoped é descartado **antes** da requisição de
 * B sair, e a resposta de uma requisição iniciada por A é descartada se a conta tiver mudado no
 * meio do voo (a mesma lição da T16.7.1: o uid capturado antes da chamada não vale depois dela).
 *
 * ## Offline não finge
 *
 * Sem internet, uma edição **não** acontece: ela não vai para a Outbox, não fica pendente e não é
 * reenviada depois. A tela diz que nada foi enviado. Isso é a diferença deliberada entre o social
 * (server-authoritative) e o treino (local-first) — e nenhuma falha aqui afeta Room, execução,
 * histórico, gamificação ou backup.
 */
class SocialViewModel(
    private val gateway: SocialGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    /**
     * O estado inicial é calculado, não chutado.
     *
     * `isConfigured` é síncrono, então a tela nasce sabendo se existe servidor neste build — sem
     * isso ela começaria dizendo "não configurado" e mudaria de ideia um instante depois, e a
     * seção apareceria do nada logo após abrir o Perfil.
     */
    private val _uiState = MutableStateFlow(
        SocialUiState(
            phase = if (gateway.isConfigured) SocialPhase.SignedOut else SocialPhase.NotConfigured
        )
    )
    val uiState: StateFlow<SocialUiState> = _uiState.asStateFlow()

    /** A conta da qual o estado atual fala. `null` = nenhuma. */
    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            // Observar a sessão que já existe é leitura: não abre seletor de contas, não ativa
            // Social e não cria linha nenhuma — nem aqui, nem no servidor.
            authGateway.state.collect { state ->
                onAccountChanged((state as? AuthState.SignedIn)?.account?.uid, state)
            }
        }
    }

    /**
     * A conta mudou (ou foi observada pela primeira vez).
     *
     * A invalidação vem **antes** de qualquer requisição: entre o logout de A e a resposta de B a
     * tela mostra "carregando", nunca o perfil de A.
     */
    private fun onAccountChanged(uid: String?, state: AuthState) {
        if (!gateway.isConfigured) {
            _uiState.value = SocialUiState(phase = SocialPhase.NotConfigured)
            return
        }
        if (uid == currentUid) return

        currentUid = uid
        if (uid == null) {
            // Sair da conta apaga o estado social da tela por inteiro — inclusive as folhas
            // abertas e o texto digitado, que pertenciam à conta anterior.
            _uiState.value = SocialUiState(phase = SocialPhase.SignedOut)
            return
        }

        val suggestion = (state as? AuthState.SignedIn)?.account?.displayName
        _uiState.value = SocialUiState(
            phase = SocialPhase.Loading,
            suggestedDisplayName = suggestion
        )
        load(uid)
    }

    /** Relê o perfil no servidor. Leitura pura: não cria e não altera nada. */
    fun refresh() {
        val uid = currentUid ?: return
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(phase = SocialPhase.Loading)
        load(uid)
    }

    private fun load(uid: String) {
        viewModelScope.launch {
            val outcome = gateway.profile()
            if (currentUid != uid) return@launch
            _uiState.value = _uiState.value.copy(
                phase = when (outcome) {
                    is SocialOutcome.Success -> SocialPhase.Active(outcome.profile)
                    SocialOutcome.NotEnabled -> SocialPhase.NotEnabled
                    is SocialOutcome.Failure -> failurePhase(outcome.error, profile = null)
                }
            )
        }
    }

    // ---------------------------------------------------------------------------- ativação

    /**
     * "Ativar recursos sociais": abre a explicação do que será criado.
     *
     * Isto **não** ativa nada e não faz requisição nenhuma. Ele só prepara o formulário, com o
     * nome da conta Google como sugestão inicial — sugestão, e não autoridade: depois da ativação
     * quem manda no nome é o perfil social.
     */
    fun startActivation() {
        if (_uiState.value.isBusy || _uiState.value.isActivationSheetOpen) return
        if (_uiState.value.phase !is SocialPhase.NotEnabled) return

        val suggestion = _uiState.value.suggestedDisplayName.orEmpty()
        _uiState.value = _uiState.value.copy(
            isActivationSheetOpen = true,
            displayNameInput = suggestion,
            isDisplayNameAcceptable = SocialContract.isDisplayNameAcceptable(suggestion)
        )
    }

    /** Cancelar. Nada foi criado, nada foi enviado. */
    fun cancelActivation() {
        _uiState.value = _uiState.value.copy(
            isActivationSheetOpen = false,
            displayNameInput = "",
            isDisplayNameAcceptable = false
        )
    }

    /** O texto do campo de nome. Estado de formulário: não sai do aparelho até a confirmação. */
    fun onDisplayNameChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            displayNameInput = value,
            isDisplayNameAcceptable = SocialContract.isDisplayNameAcceptable(value)
        )
    }

    /**
     * "Ativar": a confirmação explícita.
     *
     * É o **único** caminho do app que cria identidade social. Toque repetido é ignorado aqui e o
     * servidor repete a proteção sendo idempotente: dez toques produzem um perfil, não dez.
     */
    fun confirmActivation() {
        if (_uiState.value.isBusy) return
        val uid = currentUid ?: return
        val name = _uiState.value.displayNameInput.trim()
        if (!SocialContract.isDisplayNameAcceptable(name)) return

        _uiState.value = _uiState.value.copy(
            isActivationSheetOpen = false,
            phase = SocialPhase.Activating
        )
        viewModelScope.launch {
            apply(uid, previous = null) { gateway.activate(name) }
        }
    }

    // ---------------------------------------------------------------------------- edição

    /** Abre a edição do nome, já preenchida com o nome atual. */
    fun startEditingName() {
        val profile = _uiState.value.profile ?: return
        if (_uiState.value.isBusy || _uiState.value.isEditingName) return

        _uiState.value = _uiState.value.copy(
            isEditingName = true,
            displayNameInput = profile.displayName,
            isDisplayNameAcceptable = true
        )
    }

    fun cancelEditingName() {
        _uiState.value = _uiState.value.copy(
            isEditingName = false,
            displayNameInput = "",
            isDisplayNameAcceptable = false
        )
    }

    /** Confirma o nome novo. Sem internet, **nada** é salvo e a tela diz isso. */
    fun confirmDisplayName() {
        val profile = _uiState.value.profile ?: return
        val uid = currentUid ?: return
        if (_uiState.value.isBusy) return
        val name = _uiState.value.displayNameInput.trim()
        if (!SocialContract.isDisplayNameAcceptable(name)) return
        if (name == profile.displayName) {
            cancelEditingName()
            return
        }

        _uiState.value = _uiState.value.copy(
            isEditingName = false,
            phase = SocialPhase.Saving(profile)
        )
        viewModelScope.launch {
            apply(uid, previous = profile) { gateway.updateDisplayName(name) }
        }
    }

    // ---------------------------------------------------------------------------- privacidade

    /** Liga/desliga o recebimento de pedidos de amizade — respeitado pela T17.1 quando ela existir. */
    fun setFriendRequestsEnabled(enabled: Boolean) {
        updatePrivacy { gateway.updatePrivacy(friendRequestsEnabled = enabled) }
    }

    /** Liga/desliga o compartilhamento de atividade com amigos (T17.4). Ao ativar, envia o fuso do aparelho. */
    fun setActivitySharingEnabled(enabled: Boolean) {
        val timeZoneId = if (enabled) java.util.TimeZone.getDefault().id else null
        updatePrivacy {
            gateway.updatePrivacy(
                activitySharingEnabled = enabled,
                activityTimeZoneId = timeZoneId
            )
        }
    }

    /** Liga/desliga a participação no ranking semanal entre amigos (T17.4). */
    fun setFriendRankingParticipationEnabled(enabled: Boolean) {
        updatePrivacy {
            gateway.updatePrivacy(friendRankingParticipationEnabled = enabled)
        }
    }

    private fun updatePrivacy(operation: suspend () -> SocialOutcome) {
        val profile = _uiState.value.profile ?: return
        val uid = currentUid ?: return
        if (_uiState.value.isBusy) return

        _uiState.value = _uiState.value.copy(phase = SocialPhase.Saving(profile))
        viewModelScope.launch { apply(uid, previous = profile, operation = operation) }
    }

    // ---------------------------------------------------------------------------- desativar

    /** Abre a confirmação. O texto dela diz o que **não** será apagado. */
    fun startDisable() {
        if (_uiState.value.profile == null || _uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isConfirmingDisable = true)
    }

    fun cancelDisable() {
        _uiState.value = _uiState.value.copy(isConfirmingDisable = false)
    }

    /**
     * Desativa. A identidade é preservada no servidor: reativar devolve o mesmo `socialId` e o
     * mesmo `friendCode`.
     */
    fun confirmDisable() {
        val profile = _uiState.value.profile ?: return
        val uid = currentUid ?: return
        if (_uiState.value.isBusy) return

        _uiState.value = _uiState.value.copy(
            isConfirmingDisable = false,
            phase = SocialPhase.Disabling(profile)
        )
        viewModelScope.launch { apply(uid, previous = profile) { gateway.disable() } }
    }

    /** Reativa. */
    fun enable() {
        val profile = _uiState.value.profile ?: return
        val uid = currentUid ?: return
        if (_uiState.value.isBusy) return

        _uiState.value = _uiState.value.copy(phase = SocialPhase.Saving(profile))
        viewModelScope.launch { apply(uid, previous = profile) { gateway.enable() } }
    }

    // ---------------------------------------------------------------------------- comum

    /**
     * Executa a operação e reflete o resultado — **se** a conta ainda for a mesma.
     *
     * A revalidação depois da resposta é a lição da T16.7.1: o `uid` capturado antes da requisição
     * não serve, porque a conta pode trocar durante o voo. Uma resposta obtida como A nunca vira
     * estado de tela de B.
     */
    private suspend fun apply(
        uid: String,
        previous: SocialProfile?,
        operation: suspend () -> SocialOutcome
    ) {
        val outcome = operation()
        if (currentUid != uid) return

        // Outro aparelho já tinha feito a transição. Não é falha: o estado real do servidor é o
        // que interessa, então relemos em vez de mostrar erro.
        val isStaleTransition = outcome is SocialOutcome.Failure &&
            (outcome.error == SocialError.ALREADY_ENABLED ||
                outcome.error == SocialError.ALREADY_DISABLED)

        val phase = when {
            outcome is SocialOutcome.Success -> SocialPhase.Active(outcome.profile)
            outcome is SocialOutcome.NotEnabled -> SocialPhase.NotEnabled
            isStaleTransition -> SocialPhase.Loading
            // O perfil sumiu do servidor (desativação por outro caminho, conta recriada): a tela
            // volta a oferecer ativação em vez de insistir num perfil que não há.
            outcome is SocialOutcome.Failure &&
                outcome.error == SocialError.NOT_ENABLED -> SocialPhase.NotEnabled
            else -> failurePhase((outcome as SocialOutcome.Failure).error, previous)
        }

        // A escrita do estado acontece **antes** de a releitura ser disparada, e não dentro da
        // expressão que a calcula. Numa árvore de chamadas sem suspensão real — que é o caso de
        // um dublê em teste, e pode ser o de um cache futuro — uma releitura iniciada no meio da
        // expressão terminaria primeiro, e esta atribuição sobrescreveria o resultado dela com o
        // `Loading` que a antecedia. A tela ficaria carregando para sempre.
        _uiState.value = _uiState.value.copy(
            displayNameInput = "",
            isDisplayNameAcceptable = false,
            phase = phase
        )

        if (isStaleTransition) {
            refreshAfterConflict(uid)
        }
    }

    private fun refreshAfterConflict(uid: String) {
        viewModelScope.launch {
            val outcome = gateway.profile()
            if (currentUid != uid) return@launch
            _uiState.value = _uiState.value.copy(
                phase = when (outcome) {
                    is SocialOutcome.Success -> SocialPhase.Active(outcome.profile)
                    SocialOutcome.NotEnabled -> SocialPhase.NotEnabled
                    is SocialOutcome.Failure -> failurePhase(outcome.error, profile = null)
                }
            )
        }
    }

    private fun failurePhase(error: SocialError, profile: SocialProfile?): SocialPhase = when (error) {
        SocialError.NOT_CONFIGURED -> SocialPhase.NotConfigured
        // Falhar uma operação social **não** é sair da conta: quem decide isso é o Firebase Auth
        // local, e ele continua dizendo que a sessão existe.
        SocialError.AUTH_REQUIRED -> SocialPhase.Error(SocialError.AUTH_REQUIRED, profile)
        SocialError.NETWORK -> SocialPhase.Offline(profile)
        SocialError.NOT_ENABLED -> SocialPhase.NotEnabled
        else -> SocialPhase.Error(error, profile)
    }
}
