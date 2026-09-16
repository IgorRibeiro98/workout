package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.WorkoutShareImportResult
import com.example.data.repository.WorkoutShareImporter
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareError
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
import com.example.domain.social.WorkoutShareKind
import com.example.domain.social.WorkoutShareOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SharedWorkoutsTab {
    RECEIVED,
    SENT
}

data class SharedWorkoutsUiState(
    val selectedTab: SharedWorkoutsTab = SharedWorkoutsTab.RECEIVED,
    val isLoading: Boolean = false,
    val receivedItems: List<WorkoutShareItem> = emptyList(),
    val sentItems: List<WorkoutShareItem> = emptyList(),
    val previewDetail: WorkoutShareDetail? = null,
    val isPreviewLoading: Boolean = false,
    val importingShareId: String? = null,
    val notice: String? = null
)

/**
 * As ofertas recebidas e enviadas — treinos (T17.7) e programas (T19.3) na mesma lista.
 *
 * ## Escopo de conta
 *
 * Toda leitura e toda ação carregam o `uid` com que começaram, e a resposta de uma conta que já
 * não é a atual é **descartada**: trocar de conta limpa a tela **antes** de a leitura da conta
 * nova sair, e uma resposta atrasada da anterior nunca a preenche de volta.
 *
 * ## Aceitar
 *
 * "Adicionar" é uma operação só, do [WorkoutShareImporter]: aceite no servidor (que revalida
 * bloqueio, cancelamento e expiração) e transação local. A tela não fala com o servidor por conta
 * própria, e um toque repetido enquanto uma importação corre não faz nada.
 */
class SharedWorkoutsViewModel(
    private val shareGateway: WorkoutShareGateway,
    private val shareImporter: WorkoutShareImporter,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedWorkoutsUiState())
    val uiState: StateFlow<SharedWorkoutsUiState> = _uiState.asStateFlow()

    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            authGateway.state.collect { state ->
                val newUid = (state as? AuthState.SignedIn)?.account?.uid
                if (newUid != currentUid) {
                    currentUid = newUid
                    _uiState.value = SharedWorkoutsUiState()
                    if (newUid != null) {
                        refresh()
                    }
                }
            }
        }
    }

    fun selectTab(tab: SharedWorkoutsTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun refresh() {
        if (!shareGateway.isConfigured) return
        val uid = currentUid ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val receivedOutcome = shareGateway.listReceived()
            val sentOutcome = shareGateway.listSent()
            if (currentUid != uid) return@launch

            val received = (receivedOutcome as? WorkoutShareOutcome.Success)?.data ?: emptyList()
            val sent = (sentOutcome as? WorkoutShareOutcome.Success)?.data ?: emptyList()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                receivedItems = received,
                sentItems = sent
            )
        }
    }

    fun openDetail(shareId: String) {
        val uid = currentUid ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPreviewLoading = true)
            val outcome = shareGateway.getDetail(shareId)
            if (currentUid != uid) return@launch
            when (outcome) {
                is WorkoutShareOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isPreviewLoading = false,
                        previewDetail = outcome.data
                    )
                }
                is WorkoutShareOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isPreviewLoading = false,
                        notice = "Não foi possível carregar os detalhes da oferta."
                    )
                }
            }
        }
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(previewDetail = null)
    }

    /**
     * Aceita e importa a oferta. O conteúdo vem do servidor no aceite — a tela não passa o
     * snapshot que tinha em memória — e um toque duplo cai no `importingShareId`.
     */
    fun importShare(shareId: String) {
        val uid = currentUid ?: return
        if (_uiState.value.importingShareId != null) return
        _uiState.value = _uiState.value.copy(importingShareId = shareId)
        viewModelScope.launch {
            val result = shareImporter.acceptAndImport(shareId)
            if (currentUid != uid) return@launch
            when (result) {
                is WorkoutShareImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        previewDetail = null,
                        notice = successNotice(result.kind)
                    )
                    refresh()
                }
                is WorkoutShareImportResult.AlreadyImported -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        previewDetail = null,
                        notice = alreadyImportedNotice(result.kind)
                    )
                }
                is WorkoutShareImportResult.MissingExercises -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        notice = "Não foi possível importar: alguns exercícios não existem no catálogo local."
                    )
                }
                is WorkoutShareImportResult.Rejected -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        notice = rejectedNotice(result.error)
                    )
                    // A oferta pode ter mudado de estado no servidor (cancelada, expirada): a lista
                    // precisa refletir isso, e não o que ela era quando foi aberta.
                    if (result.error != WorkoutShareError.NETWORK) {
                        _uiState.value = _uiState.value.copy(previewDetail = null)
                        refresh()
                    }
                }
                is WorkoutShareImportResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        notice = result.message
                    )
                }
            }
        }
    }

    fun declineShare(shareId: String) {
        val uid = currentUid ?: return
        viewModelScope.launch {
            val outcome = shareGateway.declineShare(shareId)
            if (currentUid != uid) return@launch
            when (outcome) {
                is WorkoutShareOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        previewDetail = null,
                        notice = "Oferta recusada."
                    )
                    refresh()
                }
                is WorkoutShareOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(notice = "Falha ao recusar a oferta.")
                }
            }
        }
    }

    fun cancelShare(shareId: String) {
        val uid = currentUid ?: return
        viewModelScope.launch {
            val outcome = shareGateway.cancelShare(shareId)
            if (currentUid != uid) return@launch
            when (outcome) {
                is WorkoutShareOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        previewDetail = null,
                        notice = "Compartilhamento cancelado."
                    )
                    refresh()
                }
                is WorkoutShareOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(notice = "Falha ao cancelar compartilhamento.")
                }
            }
        }
    }

    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    private fun successNotice(kind: WorkoutShareKind): String = when (kind) {
        WorkoutShareKind.WORKOUT_TEMPLATE -> "Treino adicionado com sucesso aos seus treinos!"
        // Receber não troca o programa atual: dizer isso aqui é o que evita a pergunta "cadê?".
        WorkoutShareKind.WORKOUT_PROGRAM ->
            "Programa adicionado aos seus programas! Ative-o em Treinos quando quiser usá-lo."
    }

    private fun alreadyImportedNotice(kind: WorkoutShareKind): String = when (kind) {
        WorkoutShareKind.WORKOUT_TEMPLATE -> "Este treino já foi adicionado aos seus treinos anteriormente."
        WorkoutShareKind.WORKOUT_PROGRAM -> "Este programa já foi adicionado aos seus programas anteriormente."
    }

    private fun rejectedNotice(error: WorkoutShareError): String = when (error) {
        WorkoutShareError.NETWORK -> "Sem conexão. Nada foi adicionado — tente novamente com internet."
        WorkoutShareError.AUTH_REQUIRED -> "Entre na sua Conta Spark para aceitar a oferta."
        WorkoutShareError.SHARE_NOT_FOUND -> "Esta oferta não está mais disponível."
        WorkoutShareError.INVALID_STATE -> "Esta oferta não está mais disponível."
        WorkoutShareError.REJECTED -> "Esta oferta não está mais disponível."
        WorkoutShareError.UNAVAILABLE -> "O servidor está indisponível no momento. Tente novamente mais tarde."
        else -> "Não foi possível aceitar a oferta agora. Tente novamente."
    }
}
