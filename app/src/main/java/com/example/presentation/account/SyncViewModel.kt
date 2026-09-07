package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.sync.SyncActivity
import com.example.data.sync.SyncApplyStop
import com.example.data.sync.SyncConflictChoice
import com.example.data.sync.SyncConflictId
import com.example.data.sync.SyncConflictResolution
import com.example.data.sync.SyncCoordinator
import com.example.data.sync.SyncOutcome
import com.example.data.sync.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A sincronização multi-device, do ponto de vista da UI (T16.6).
 *
 * ```text
 * Perfil → SyncViewModel → SyncCoordinator → SyncRepository → Room + Spark Backend
 * ```
 *
 * ## Nenhum ciclo começa por abrir a tela
 *
 * Não há sincronização em `init`, em recomposição nem em listener de login. O que acontece ao
 * observar o estado é o oposto disso: o app **lê** o vínculo, a fila e os conflitos para saber o
 * que mostrar. Um ciclo nasce de um toque, do app voltando ao primeiro plano, ou do trabalho
 * agendado quando uma alteração local entra na fila.
 *
 * ## A UI não conhece o protocolo
 *
 * `cursor`, `revision`, `baseRevision` e `serverSequence` não atravessam esta fronteira. O que
 * sobe para a tela é "atualizado agora", "3 alterações aguardando conexão" e "1 item precisa de
 * atenção".
 */
class SyncViewModel(
    private val repository: SyncRepository,
    private val coordinator: SyncCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SyncUiState(
            phase = if (repository.isConfigured) SyncPhase.Disabled else SyncPhase.NotConfigured
        )
    )
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var currentUid: String? = null

    init {
        viewModelScope.launch {
            // Observar o coordenador é leitura: ele só publica o que já aconteceu.
            coordinator.activity.collect { activity -> render(activity) }
        }
    }

    /**
     * A sessão atual mudou.
     *
     * Chamado pela tela, que já observa a conta para a seção de conta. Só recalcula o que mostrar
     * — não sincroniza, não vincula e não adota nada.
     */
    fun onAccountChanged(uid: String?) {
        currentUid = uid
        viewModelScope.launch { render(coordinator.activity.value) }
    }

    /** "Sincronizar agora". Dois toques produzem um ciclo — o coordenador recusa o segundo. */
    fun syncNow() {
        if (_uiState.value.isBusy) return
        coordinator.syncNow()
    }

    /**
     * O usuário escolheu o que fazer com um conflito (T16.7).
     *
     * A proteção contra duplo toque é dupla, de propósito: aqui, para que a tela nem chame duas
     * vezes; e na resolução, com uma escrita condicional no banco — porque duas corrotinas podem
     * entrar antes de o estado da tela mudar, e é o banco que decide.
     *
     * Uma decisão que virou mutação dispara um ciclo: o usuário acabou de agir, e esperar o
     * agendamento faria a tela dizer "aguardando envio" sem motivo visível.
     */
    fun resolveConflict(id: SyncConflictId, choice: SyncConflictChoice) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(resolving = id, resolutionProblem = null)
        viewModelScope.launch {
            val resolution = repository.resolveConflict(currentUid, id, choice)
            _uiState.value = _uiState.value.copy(
                resolving = null,
                resolutionProblem = problemOf(resolution)
            )
            render(coordinator.activity.value)
            if (resolution is SyncConflictResolution.Queued) coordinator.syncNow()
        }
    }

    /** O usuário leu o aviso da última tentativa de resolução. */
    fun dismissResolutionProblem() {
        _uiState.value = _uiState.value.copy(resolutionProblem = null)
    }

    private fun problemOf(resolution: SyncConflictResolution): SyncResolutionProblem? =
        when (resolution) {
            // "Já resolvido" não é falha: outro toque chegou antes, e o resultado é o mesmo.
            SyncConflictResolution.Applied,
            SyncConflictResolution.Queued,
            SyncConflictResolution.AlreadyResolved,
            SyncConflictResolution.NotFound -> null

            SyncConflictResolution.NoRemoteCopy -> SyncResolutionProblem.NO_REMOTE_COPY
            SyncConflictResolution.StillReferenced -> SyncResolutionProblem.STILL_REFERENCED
            SyncConflictResolution.PendingChildChanges ->
                SyncResolutionProblem.PENDING_CHILD_CHANGES
            SyncConflictResolution.AccountMismatch -> SyncResolutionProblem.ACCOUNT_MISMATCH

            SyncConflictResolution.AuthRequired,
            SyncConflictResolution.NotEnabled,
            SyncConflictResolution.NotAvailable,
            SyncConflictResolution.NoLocalCopy -> SyncResolutionProblem.FAILED
        }

    private suspend fun render(activity: SyncActivity) {
        if (!repository.isConfigured) {
            _uiState.value = SyncUiState(phase = SyncPhase.NotConfigured)
            return
        }

        val snapshot = repository.snapshot()
        val binding = snapshot.ownerUid

        val base = _uiState.value.copy(
            pending = snapshot.pending,
            // O que "precisa de atenção" é a soma do que ficou travado na fila com o que divergiu
            // do servidor — do ponto de vista de quem olha a tela, é uma coisa só.
            needsAttention = maxOf(snapshot.blocked, snapshot.conflicts),
            lastSyncedAt = snapshot.lastSyncedAt,
            // Leitura pura do Room: listar conflitos não sincroniza e não resolve nada.
            conflicts = repository.conflicts(currentUid)
        )

        val phase = when {
            binding == null -> SyncPhase.Disabled
            activity is SyncActivity.Running -> SyncPhase.Syncing
            currentUid == null -> SyncPhase.AuthRequired
            currentUid != binding -> SyncPhase.AccountMismatch
            else -> phaseFor(activity, base)
        }

        _uiState.value = base.copy(
            phase = phase,
            deferredDeletes = (activity as? SyncActivity.Finished)
                ?.let { (it.outcome as? SyncOutcome.Success)?.deferredDeletes }
                ?: base.deferredDeletes
        )
    }

    private fun phaseFor(activity: SyncActivity, base: SyncUiState): SyncPhase {
        val outcome = (activity as? SyncActivity.Finished)?.outcome

        // Erro de transporte tem prioridade sobre a contagem: dizer "3 alterações aguardando"
        // quando o servidor está fora do ar esconderia a causa real.
        when (outcome) {
            SyncOutcome.Offline -> return SyncPhase.Offline(base.pending, base.lastSyncedAt)
            SyncOutcome.Unavailable ->
                return SyncPhase.Failed(SyncFailure.UNAVAILABLE, base.lastSyncedAt)
            SyncOutcome.RateLimited ->
                return SyncPhase.Failed(SyncFailure.RATE_LIMITED, base.lastSyncedAt)
            is SyncOutcome.Rejected ->
                // O cursor deste aparelho aponta para antes do que o servidor ainda guarda. Não é
                // erro recuperável: continuar andando pularia mudanças — possivelmente exclusões —
                // e ressuscitaria dado apagado. A tela orienta; ela não reconstrói sozinha.
                return if (outcome.code == CURSOR_EXPIRED) {
                    SyncPhase.NeedsRebaseline(base.lastSyncedAt)
                } else {
                    SyncPhase.Failed(SyncFailure.REJECTED, base.lastSyncedAt)
                }
            is SyncOutcome.Success -> when (outcome.pausedAt) {
                // Este app não sabe ler algo que outro aparelho enviou, ou uma referência do
                // payload não existe aqui (catálogo desatualizado). Nada foi perdido: o sync
                // pausou naquele ponto e volta a andar depois da atualização.
                is SyncApplyStop.Unsupported, is SyncApplyStop.Failed ->
                    return SyncPhase.Failed(SyncFailure.NEEDS_APP_UPDATE, base.lastSyncedAt)

                // Adiado porque há treino em execução. Não é erro, e a tela não precisa dizer
                // nada: o ciclo seguinte aplica.
                is SyncApplyStop.DeferredActiveWorkout, null -> Unit
            }
            else -> Unit
        }

        return when {
            base.needsAttention > 0 ->
                SyncPhase.NeedsAttention(base.needsAttention, base.lastSyncedAt)
            base.pending > 0 -> SyncPhase.Pending(base.pending, base.lastSyncedAt)
            else -> SyncPhase.UpToDate(base.lastSyncedAt)
        }
    }
}

private const val CURSOR_EXPIRED = "CURSOR_EXPIRED"
