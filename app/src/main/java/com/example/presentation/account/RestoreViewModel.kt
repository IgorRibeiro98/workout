package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupMetadataDto
import com.example.data.restore.RestoreError
import com.example.data.restore.RestoreListing
import com.example.data.restore.RestoreOutcome
import com.example.data.restore.RestorePlan
import com.example.data.restore.RestorePreparation
import com.example.data.restore.RestoreRepository
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * O restore, do ponto de vista da UI (T16.5).
 *
 * ```text
 * Perfil → RestoreViewModel → RestoreRepository → Spark Backend + Room/DataStore
 * ```
 *
 * ## Nenhum restore começa sozinho
 *
 * Não há restauração em `init`, ao abrir a tela, em recomposição, no listener de login nem na
 * inicialização do app. Entrar na conta muda a fase de [RestorePhaseUi.NotAuthenticated] para
 * [RestorePhaseUi.Idle] — e nada mais. Listar backups é leitura de metadata; baixar exige escolher
 * um; substituir exige confirmar.
 *
 * ## Três toques, três decisões
 *
 * ```text
 * "Ver backups"        → lista (metadata; nenhum snapshot baixado)
 * escolher um backup   → download + hash + validação → preview
 * "Restaurar"          → confirmação (dupla, quando há dado local) → substituição
 * ```
 *
 * A confirmação não fica só aqui: ela atravessa até o repositório, que recusa aplicar sem ela.
 * Assim nem um caminho de UI esquecido consegue substituir o dataset.
 */
class RestoreViewModel(
    private val repository: RestoreRepository,
    private val authGateway: AuthGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RestoreUiState(
            phase = if (repository.isConfigured) {
                RestorePhaseUi.NotAuthenticated
            } else {
                RestorePhaseUi.NotConfigured
            }
        )
    )
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    private var currentUid: String? = null

    /** O plano validado do backup escolhido. Existe só entre o preview e a confirmação. */
    private var preparedPlan: RestorePlan? = null
    private var preparedAttemptId: String? = null

    /** A metadata da lista, para achar o backup escolhido sem recarregar nada. */
    private var listedBackups: List<BackupMetadataDto> = emptyList()

    init {
        viewModelScope.launch {
            // Observar a sessão que já existe é leitura: não abre seletor de contas, não lista
            // backups e, principalmente, não restaura.
            authGateway.state.collect { state ->
                currentUid = (state as? AuthState.SignedIn)?.account?.uid
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        if (!repository.isConfigured) {
            _uiState.value = RestoreUiState(phase = RestorePhaseUi.NotConfigured)
            return
        }
        // Uma mudança de sessão no meio de uma operação não pode apagar o que a tela mostra.
        if (_uiState.value.isBusy) return

        val uid = currentUid
        if (uid == null) {
            _uiState.value = RestoreUiState(phase = RestorePhaseUi.NotAuthenticated)
            return
        }

        // Uma tentativa interrompida tem prioridade sobre qualquer outra coisa: o aparelho pode
        // estar com um restore pela metade, e oferecer "restaurar de novo" antes de resolver isso
        // seria empilhar substituição sobre substituição.
        if (repository.unfinishedAttempt() != null) {
            _uiState.value = _uiState.value.copy(phase = RestorePhaseUi.RecoveryPending)
            return
        }

        if (_uiState.value.phase is RestorePhaseUi.Completed) return

        val lastRestore = repository.lastCompleted(uid)
        _uiState.value = _uiState.value.copy(
            phase = RestorePhaseUi.Idle(lastRestoredAt = lastRestore?.updatedAt)
        )
    }

    /** "Ver backups": carrega **metadata**. Nenhum snapshot é baixado aqui. */
    fun loadBackups() {
        if (_uiState.value.isBusy) return
        val uid = currentUid ?: return

        _uiState.value = _uiState.value.copy(phase = RestorePhaseUi.LoadingBackups)
        viewModelScope.launch {
            when (val listing = repository.availableBackups(uid)) {
                is RestoreListing.Available -> {
                    listedBackups = listing.backups
                    _uiState.value = _uiState.value.copy(
                        phase = RestorePhaseUi.ChoosingBackup(
                            listing.backups.map { backup ->
                                RestoreBackupItem(
                                    backupId = backup.backupId,
                                    createdAt = backup.createdAt,
                                    itemCount = backup.itemCount
                                )
                            }
                        )
                    )
                }

                is RestoreListing.Failed -> failWith(listing.error)
            }
        }
    }

    /**
     * Escolher um backup: baixa, confere o hash, valida e monta o preview.
     *
     * Nada local é alterado por este caminho — nem quando ele falha. É o download **sob demanda**
     * de que a lista precisa: metadata para escolher, conteúdo só para o escolhido.
     */
    fun selectBackup(backupId: String) {
        if (_uiState.value.isBusy) return
        val backup = listedBackups.firstOrNull { it.backupId == backupId } ?: return

        _uiState.value = _uiState.value.copy(phase = RestorePhaseUi.Preparing)
        viewModelScope.launch {
            when (val preparation = repository.prepare(backup, currentUid)) {
                is RestorePreparation.Ready -> {
                    preparedPlan = preparation.plan
                    preparedAttemptId = preparation.restoreAttemptId
                    _uiState.value = _uiState.value.copy(
                        phase = RestorePhaseUi.Preview(
                            RestorePreviewUi(
                                backupCreatedAt = preparation.plan.backup.createdAt,
                                // Contagens do snapshot lido, não do que a metadata declara.
                                counts = preparation.plan.counts,
                                warnings = preparation.plan.warnings,
                                replacesLocalData = preparation.plan.replacesLocalData
                            )
                        )
                    )
                }

                is RestorePreparation.Failed -> failWith(preparation.error)
            }
        }
    }

    /**
     * "Restaurar" no preview.
     *
     * Com dado local a perder, isto **não** aplica: abre a segunda confirmação. Com o aparelho
     * vazio, uma pergunta basta — não há o que substituir.
     */
    fun requestRestore() {
        val preview = (_uiState.value.phase as? RestorePhaseUi.Preview) ?: return
        if (preview.preview.replacesLocalData) {
            _uiState.value = _uiState.value.copy(isConfirmingReplacement = true)
        } else {
            applyRestore()
        }
    }

    /** A confirmação destrutiva: "isto substitui os dados deste aparelho". */
    fun confirmReplacement() {
        _uiState.value = _uiState.value.copy(isConfirmingReplacement = false)
        applyRestore()
    }

    fun cancelReplacement() {
        _uiState.value = _uiState.value.copy(isConfirmingReplacement = false)
    }

    /**
     * Sair do fluxo sem restaurar.
     *
     * A tentativa preparada é **encerrada**, e não esquecida: uma tentativa validada em aberto
     * bloquearia a próxima, e o app trataria uma desistência como um restore pela metade.
     */
    fun cancel() {
        val attemptId = preparedAttemptId
        preparedPlan = null
        preparedAttemptId = null
        _uiState.value = _uiState.value.copy(isConfirmingReplacement = false)
        viewModelScope.launch {
            if (attemptId != null) repository.discard(attemptId)
            refresh()
        }
    }

    private fun applyRestore() {
        val plan = preparedPlan ?: return
        val attemptId = preparedAttemptId ?: return
        if (_uiState.value.isBusy) return

        _uiState.value = _uiState.value.copy(phase = RestorePhaseUi.Applying)
        viewModelScope.launch {
            val outcome = repository.confirm(
                plan = plan,
                restoreAttemptId = attemptId,
                currentUid = currentUid,
                // A confirmação explícita atravessa daqui até o repositório: ele recusa aplicar sem
                // ela, então nenhum caminho de UI consegue substituir dados por engano.
                confirmed = true
            )
            preparedPlan = null
            preparedAttemptId = null

            _uiState.value = when (outcome) {
                is RestoreOutcome.Success ->
                    _uiState.value.copy(phase = RestorePhaseUi.Completed(plan.counts))

                // Dataset substituído, fase pendente: a tela **não** diz "restaurado".
                is RestoreOutcome.RecoveryPending ->
                    _uiState.value.copy(phase = RestorePhaseUi.RecoveryPending)

                is RestoreOutcome.Failed ->
                    _uiState.value.copy(phase = phaseFor(outcome.error))
            }
        }
    }

    private fun failWith(error: RestoreError) {
        _uiState.value = _uiState.value.copy(phase = phaseFor(error))
    }

    private fun phaseFor(error: RestoreError): RestorePhaseUi = when (error) {
        RestoreError.NOT_CONFIGURED -> RestorePhaseUi.NotConfigured
        RestoreError.AUTH_REQUIRED -> RestorePhaseUi.NotAuthenticated
        RestoreError.ACCOUNT_MISMATCH -> RestorePhaseUi.AccountMismatch
        RestoreError.RESTORE_RECOVERY_REQUIRED -> RestorePhaseUi.RecoveryPending
        else -> RestorePhaseUi.Failed(error)
    }
}
