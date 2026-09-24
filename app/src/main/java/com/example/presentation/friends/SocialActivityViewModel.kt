package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SocialActivityError
import com.example.domain.social.SocialActivityGateway
import com.example.domain.social.SocialActivityOutcome
import com.example.domain.social.SocialGateway
import com.example.domain.social.SocialOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel da tela de Atividade dos Amigos e Rankings Contextuais (T17.4).
 *
 * ## Regras
 * - Descarta respostas se a conta mudar durante a requisição (segurança de sessão).
 * - Trata RANKING_NOT_ENABLED exibindo convite para participar do ranking (reciprocidade).
 * - Ação de opt-in atualiza privacidade via SocialGateway e recarrega o ranking.
 * - Não persiste dados no Room e não interage com a Outbox.
 */
class SocialActivityViewModel(
    private val activityGateway: SocialActivityGateway,
    private val socialGateway: SocialGateway,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(SocialActivityUiState())
    val uiState: StateFlow<SocialActivityUiState> = _uiState.asStateFlow()

    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid != currentUid) {
                    currentUid = newUid
                    if (newUid != null) {
                        // O estado da conta anterior — inclusive um "↻" em voo, cuja resposta
                        // será descartada pelo `uid` — não sobrevive à troca (T19.H3 §44).
                        _uiState.value = SocialActivityUiState()
                        loadData()
                    } else {
                        _uiState.value = SocialActivityUiState(
                            rankingState = RankingUiState.Error("Você precisa entrar na Conta Spark."),
                            activityState = ActivityFeedUiState.Error("Você precisa entrar na Conta Spark.")
                        )
                    }
                }
            }
        }
    }

    private fun getCurrentUid(): String? =
        (authGateway.state.value as? AuthState.SignedIn)?.account?.uid

    fun loadData() {
        val uid = getCurrentUid()
        if (uid == null) {
            _uiState.update {
                it.copy(
                    rankingState = RankingUiState.Error("Você precisa entrar na Conta Spark."),
                    activityState = ActivityFeedUiState.Error("Você precisa entrar na Conta Spark.")
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                rankingState = RankingUiState.Loading,
                activityState = ActivityFeedUiState.Loading,
                optInErrorMessage = null
            )
        }

        loadRanking(uid)
        loadActivity(uid)
    }

    private fun loadRanking(expectedUid: String) {
        viewModelScope.launch {
            val outcome = activityGateway.getFriendRankingLast7Days()
            if (getCurrentUid() != expectedUid) return@launch

            when (outcome) {
                is SocialActivityOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            rankingState = RankingUiState.Success(
                                entries = outcome.data.entries,
                                participantCount = outcome.data.participantCount
                            )
                        )
                    }
                }
                is SocialActivityOutcome.Failure -> {
                    val state = when (outcome.error) {
                        SocialActivityError.RANKING_NOT_ENABLED -> RankingUiState.OptedOut
                        SocialActivityError.AUTH_REQUIRED -> RankingUiState.Error("Autenticação necessária.")
                        SocialActivityError.NOT_ENABLED -> RankingUiState.Error("Perfil social não ativado.")
                        SocialActivityError.NETWORK -> RankingUiState.Error("Sem conexão com a internet.")
                        SocialActivityError.UNAVAILABLE -> RankingUiState.Error("Serviço indisponível no momento.")
                        else -> RankingUiState.Error("Não foi possível carregar o ranking.")
                    }
                    _uiState.update { it.copy(rankingState = state) }
                }
            }
        }
    }

    private fun loadActivity(expectedUid: String) {
        viewModelScope.launch {
            val outcome = activityGateway.getRecentFriendActivity()
            if (getCurrentUid() != expectedUid) return@launch

            when (outcome) {
                is SocialActivityOutcome.Success -> {
                    _uiState.update {
                        it.copy(activityState = ActivityFeedUiState.Success(outcome.data))
                    }
                }
                is SocialActivityOutcome.Failure -> {
                    val state = when (outcome.error) {
                        SocialActivityError.AUTH_REQUIRED -> ActivityFeedUiState.Error("Autenticação necessária.")
                        SocialActivityError.NOT_ENABLED -> ActivityFeedUiState.Error("Perfil social não ativado.")
                        SocialActivityError.NETWORK -> ActivityFeedUiState.Error("Sem conexão com a internet.")
                        SocialActivityError.UNAVAILABLE -> ActivityFeedUiState.Error("Serviço indisponível no momento.")
                        else -> ActivityFeedUiState.Error("Não foi possível carregar a atividade dos amigos.")
                    }
                    _uiState.update { it.copy(activityState = state) }
                }
            }
        }
    }

    /**
     * O "↻" da barra (T19.H3): relê ranking e atividade.
     *
     * Com as duas seções na tela, elas **ficam** enquanto a releitura voa, e uma falha mantém a
     * última leitura boa com um aviso — sem colapsar para "carregando" nem trocar o ranking de
     * ontem por "sem conexão". Sem conteúdo na tela (primeira carga, ou um erro), é a carga de
     * sempre ([loadData]). Um toque durante a releitura é ignorado.
     */
    fun refresh() {
        val uid = getCurrentUid() ?: return loadData()
        val state = _uiState.value
        if (state.isRefreshing) return
        val showingRanking = state.rankingState is RankingUiState.Success ||
            state.rankingState is RankingUiState.OptedOut
        if (!showingRanking || state.activityState !is ActivityFeedUiState.Success) {
            loadData()
            return
        }

        _uiState.update { it.copy(isRefreshing = true, staleNotice = null) }
        viewModelScope.launch {
            val ranking = activityGateway.getFriendRankingLast7Days()
            val activity = activityGateway.getRecentFriendActivity()
            if (getCurrentUid() != uid) return@launch

            val failed = (ranking is SocialActivityOutcome.Failure &&
                ranking.error != SocialActivityError.RANKING_NOT_ENABLED) ||
                activity is SocialActivityOutcome.Failure
            _uiState.update { current ->
                current.copy(
                    isRefreshing = false,
                    rankingState = when (ranking) {
                        is SocialActivityOutcome.Success -> RankingUiState.Success(
                            entries = ranking.data.entries,
                            participantCount = ranking.data.participantCount
                        )
                        is SocialActivityOutcome.Failure ->
                            if (ranking.error == SocialActivityError.RANKING_NOT_ENABLED) {
                                RankingUiState.OptedOut
                            } else {
                                current.rankingState
                            }
                    },
                    activityState = when (activity) {
                        is SocialActivityOutcome.Success -> ActivityFeedUiState.Success(activity.data)
                        is SocialActivityOutcome.Failure -> current.activityState
                    },
                    staleNotice = if (failed) {
                        "Não foi possível atualizar agora — mostrando a última atualização."
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun optInToRanking() {
        val uid = getCurrentUid() ?: return
        _uiState.update { it.copy(isOptingIn = true, optInErrorMessage = null) }

        viewModelScope.launch {
            val outcome = socialGateway.updatePrivacy(
                friendRankingParticipationEnabled = true
            )
            if (getCurrentUid() != uid) return@launch

            _uiState.update { it.copy(isOptingIn = false) }

            when (outcome) {
                is SocialOutcome.Success -> {
                    loadRanking(uid)
                }
                is SocialOutcome.Failure -> {
                    _uiState.update {
                        it.copy(optInErrorMessage = "Não foi possível habilitar a participação no ranking. Tente novamente.")
                    }
                }
                SocialOutcome.NotEnabled -> {
                    _uiState.update {
                        it.copy(optInErrorMessage = "Perfil social não ativado.")
                    }
                }
            }
        }
    }
}
