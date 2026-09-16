package com.example.presentation.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.ai.AiCapabilitiesGateway
import com.example.domain.ai.model.AiCapabilitiesErrorKind
import com.example.domain.ai.model.AiCapabilitiesGatewayResult
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * As capabilities de IA da conta atual, do ponto de vista da UI (T19.0).
 *
 * ```text
 * AiCoachScreen / GenerateWorkoutScreen / AdaptWorkoutScreen → AiCapabilitiesViewModel
 *   → AiCapabilitiesGateway → Spark Backend
 * ```
 *
 * Este estado só orienta a UX — habilitar/desabilitar uma ação, mostrar uma mensagem. O backend
 * continua sendo a autoridade: toda operação real do Coach é validada de novo em
 * `AiCoachService`, mesmo que este estado diga "permitido" (e é isso que
 * `ai-entitlement-enforcement.spec.ts`, do lado do servidor, prova).
 *
 * ## Troca de conta invalida na hora
 *
 * A mesma lição de [com.example.presentation.account.FriendsViewModel]: o estado é descartado
 * **antes** de qualquer requisição da conta nova sair, e a resposta de uma consulta iniciada pela
 * conta anterior é descartada se a conta tiver mudado no meio do voo — o `uid` capturado antes da
 * chamada não vale depois dela.
 */
class AiCapabilitiesViewModel(
    private val gateway: AiCapabilitiesGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _state = MutableStateFlow<AiCapabilitiesUiState>(AiCapabilitiesUiState.SignedOut)
    val state: StateFlow<AiCapabilitiesUiState> = _state.asStateFlow()

    /** A conta da qual o estado atual fala. `null` = nenhuma. */
    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            // Observar a sessão é leitura: não dispara requisição. O que ela faz é invalidar
            // quando a conta muda — carregar é sempre um ato explícito de quem usa este estado.
            authGateway.state.collect { state ->
                onAccountChanged((state as? AuthState.SignedIn)?.account?.uid)
            }
        }
    }

    private fun onAccountChanged(uid: String?) {
        if (uid == currentUid) return
        currentUid = uid
        _state.value = if (uid == null) AiCapabilitiesUiState.SignedOut else AiCapabilitiesUiState.Idle
    }

    /** Carrega, se ainda não estiver carregado ou carregando. Chamado ao abrir uma tela do Coach. */
    fun ensureLoaded() {
        val uid = currentUid ?: return
        if (_state.value is AiCapabilitiesUiState.Loaded) return
        if (_state.value is AiCapabilitiesUiState.Loading) return
        load(uid)
    }

    /** Força uma nova consulta — usado por "tentar de novo" depois de uma falha de carregamento. */
    fun retry() {
        val uid = currentUid ?: return
        if (_state.value is AiCapabilitiesUiState.Loading) return
        load(uid)
    }

    private fun load(uid: String) {
        _state.value = AiCapabilitiesUiState.Loading
        viewModelScope.launch {
            val result = gateway.fetch()
            // A conta trocou enquanto a requisição estava em voo: a resposta é de quem já saiu.
            if (currentUid != uid) return@launch

            _state.value = when (result) {
                is AiCapabilitiesGatewayResult.Success -> AiCapabilitiesUiState.Loaded(result.allowed)
                is AiCapabilitiesGatewayResult.Error -> when (result.kind) {
                    AiCapabilitiesErrorKind.AUTH_REQUIRED -> AiCapabilitiesUiState.SignedOut
                    AiCapabilitiesErrorKind.UNAVAILABLE,
                    AiCapabilitiesErrorKind.NETWORK,
                    AiCapabilitiesErrorKind.INVALID_RESPONSE -> AiCapabilitiesUiState.LoadFailed
                }
            }
        }
    }
}
