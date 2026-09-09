package com.example.presentation.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.WorkoutShareImportResult
import com.example.data.repository.WorkoutShareImporter
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareDetail
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareItem
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

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val receivedOutcome = shareGateway.listReceived()
            val sentOutcome = shareGateway.listSent()

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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPreviewLoading = true)
            when (val outcome = shareGateway.getDetail(shareId)) {
                is WorkoutShareOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isPreviewLoading = false,
                        previewDetail = outcome.data
                    )
                }
                is WorkoutShareOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isPreviewLoading = false,
                        notice = "Não foi possível carregar os detalhes do treino."
                    )
                }
            }
        }
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(previewDetail = null)
    }

    fun importWorkout(shareId: String, snapshot: SharedWorkoutSnapshot) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importingShareId = shareId)
            when (val result = shareImporter.importShare(shareId, snapshot)) {
                is WorkoutShareImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        previewDetail = null,
                        notice = "Treino adicionado com sucesso aos seus treinos!"
                    )
                    refresh()
                }
                is WorkoutShareImportResult.AlreadyImported -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        previewDetail = null,
                        notice = "Este treino já foi adicionado aos seus treinos anteriormente."
                    )
                }
                is WorkoutShareImportResult.MissingExercises -> {
                    _uiState.value = _uiState.value.copy(
                        importingShareId = null,
                        notice = "Não foi possível importar: alguns exercícios não existem no catálogo local."
                    )
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
        viewModelScope.launch {
            when (shareGateway.declineShare(shareId)) {
                is WorkoutShareOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        previewDetail = null,
                        notice = "Oferta de treino recusada."
                    )
                    refresh()
                }
                is WorkoutShareOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(notice = "Falha ao recusar oferta de treino.")
                }
            }
        }
    }

    fun cancelShare(shareId: String) {
        viewModelScope.launch {
            when (shareGateway.cancelShare(shareId)) {
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
}
