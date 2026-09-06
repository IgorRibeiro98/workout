package com.example.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupOperation
import com.example.data.backup.BackupRepository
import com.example.data.backup.BackupSnapshotIncompleteException
import com.example.domain.auth.AuthGateway
import com.example.domain.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * O backup na nuvem, do ponto de vista da UI (T16.4).
 *
 * ```text
 * Perfil → BackupViewModel → BackupRepository → Room + Spark Backend
 * ```
 *
 * ## Nenhum backup começa sozinho
 *
 * Não há chamada de backup em `init`, ao abrir a tela, em recomposição, no listener de login nem
 * na inicialização do app. O que acontece ao observar o estado é o oposto disso: o app **lê** o
 * vínculo e a última tentativa para saber o que mostrar. É a mesma regra de custo que o Coach IA
 * segue desde a T14 (PROJECT_RULES §13) — e aqui ela também protege dado, não só dinheiro.
 *
 * ## Login não adota
 *
 * Entrar na conta muda a fase de [BackupPhase.NotAuthenticated] para [BackupPhase.Unbound] e nada
 * mais. Associar os dados desta instalação a uma Conta Spark exige tocar em "Ativar backup" e
 * confirmar — dois atos explícitos, nesta ordem.
 */
class BackupViewModel(
    private val repository: BackupRepository,
    private val authGateway: AuthGateway
) : ViewModel() {

    /**
     * O estado inicial é calculado, não chutado.
     *
     * `isConfigured` é síncrono, então a tela nasce já sabendo se existe servidor neste build. Sem
     * isso ela começaria dizendo "não configurado" e mudaria de ideia um instante depois — uma
     * seção que aparece do nada logo após abrir o Perfil.
     */
    private val _uiState = MutableStateFlow(
        BackupUiState(
            phase = if (repository.isConfigured) {
                BackupPhase.NotAuthenticated
            } else {
                BackupPhase.NotConfigured
            }
        )
    )
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var currentUid: String? = null
    private var currentEmail: String? = null

    init {
        viewModelScope.launch {
            // Observar a sessão que já existe é leitura. Não abre seletor de contas e, agora,
            // também não dispara backup: só recalcula o que a tela deve mostrar.
            authGateway.state.collect { state ->
                val account = (state as? AuthState.SignedIn)?.account
                currentUid = account?.uid
                currentEmail = account?.email
                refresh()
            }
        }
    }

    /**
     * Recalcula a fase a partir do vínculo e da última tentativa. **Só lê.**
     *
     * A ordem das perguntas é a ordem em que elas importam: sem backend não há o que oferecer;
     * com vínculo de outra conta, o que importa é o descompasso e não a sessão atual; e só então
     * a conta atual decide entre "entre na conta" e "ative o backup".
     */
    private suspend fun refresh(force: Boolean = false) {
        if (!repository.isConfigured) {
            _uiState.value = _uiState.value.copy(phase = BackupPhase.NotConfigured)
            return
        }
        // Uma mudança de sessão no meio de um upload não pode apagar o estado "enviando" da tela.
        // `force` é o caminho de quem acabou de terminar a operação e quer o estado real de volta.
        if (!force && _uiState.value.isBusy) return

        val binding = repository.binding()
        val uid = currentUid

        val phase = when {
            binding == null && uid == null -> BackupPhase.NotAuthenticated
            binding == null -> BackupPhase.Unbound

            // O vínculo vale mesmo sem sessão: sair da conta não desassocia os dados. A conta
            // errada conectada é descompasso; nenhuma conta conectada é só "entre de novo".
            uid != null && uid != binding.ownerUid -> BackupPhase.AccountMismatch
            uid == null -> BackupPhase.NotAuthenticated

            else -> {
                val last = repository.lastSucceeded(binding.ownerUid)
                BackupPhase.Ready(
                    ownerUid = binding.ownerUid,
                    lastBackupAt = last?.serverCreatedAt ?: binding.lastSuccessfulBackupAt,
                    lastBackupItemCount = last?.itemCount,
                    hasPendingAttempt = repository.pendingAttempt(binding.ownerUid) != null
                )
            }
        }

        _uiState.value = _uiState.value.copy(phase = phase, accountEmail = currentEmail)
    }

    /**
     * "Ativar backup": abre a confirmação e mostra **o que** será associado.
     *
     * Isto não vincula nada e não envia nada. Ele lê contagens do banco para que a confirmação
     * seja informada — a decisão continua sendo do próximo toque.
     */
    fun startAdoption() {
        if (_uiState.value.isBusy || _uiState.value.isConfirmingAdoption) return
        if (currentUid == null) return

        viewModelScope.launch {
            val summary = repository.summary()
            _uiState.value = _uiState.value.copy(
                isConfirmingAdoption = true,
                summary = summary,
                accountEmail = currentEmail
            )
        }
    }

    /** Cancelar a confirmação. Nada foi vinculado, nada foi enviado. */
    fun cancelAdoption() {
        _uiState.value = _uiState.value.copy(isConfirmingAdoption = false, summary = null)
    }

    /**
     * "Associar e criar backup": a confirmação explícita da adoção.
     *
     * É o único caminho que pode criar um vínculo — [BackupRepository.backupNow] recusa a adoção
     * sem ele, então nem um `LaunchedEffect` esquecido conseguiria associar dado.
     */
    fun confirmAdoption() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isConfirmingAdoption = false, summary = null)
        run(confirmedAdoption = true)
    }

    /**
     * "Fazer backup agora", para um dataset já vinculado.
     *
     * `confirmedAdoption = false`: se por qualquer motivo o vínculo não existir, o resultado é
     * [BackupOperation.AdoptionRequired] e a tela volta a pedir a confirmação — nunca adota em
     * silêncio.
     */
    fun backupNow() {
        if (_uiState.value.isBusy) return
        run(confirmedAdoption = false)
    }

    private fun run(confirmedAdoption: Boolean) {
        // Toque repetido durante uma operação é ignorado aqui e, de novo, no repositório: dez
        // toques viram um backup, não dez.
        _uiState.value = _uiState.value.copy(phase = BackupPhase.PreparingSnapshot)

        viewModelScope.launch {
            // Não há progresso real de bytes para mostrar, então a UI diz "criando" e "enviando" —
            // e não uma porcentagem inventada.
            _uiState.value = _uiState.value.copy(phase = BackupPhase.Uploading)

            val operation = try {
                repository.backupNow(currentUid, confirmedAdoption)
            } catch (e: BackupSnapshotIncompleteException) {
                _uiState.value = _uiState.value.copy(
                    phase = BackupPhase.Failed(BackupFailure.SNAPSHOT)
                )
                return@launch
            }

            when (operation) {
                is BackupOperation.Success -> refreshAfterOperation()

                // Toque repetido chegando enquanto o backup anterior sobe: a tela já mostra o
                // que está acontecendo, e sobrescrever isso só piscaria.
                BackupOperation.AlreadyRunning -> Unit

                // Sem vínculo e sem confirmação: a tela volta a pedir a confirmação em vez de
                // adotar em silêncio.
                BackupOperation.AdoptionRequired -> {
                    _uiState.value = _uiState.value.copy(phase = BackupPhase.Unbound)
                    startAdoption()
                }
                is BackupOperation.AccountMismatch ->
                    _uiState.value = _uiState.value.copy(phase = BackupPhase.AccountMismatch)

                // Falhar o backup **não** é sair da conta: quem decide isso é o Firebase Auth
                // local, e ele continua dizendo que a sessão existe.
                BackupOperation.AuthRequired -> fail(BackupFailure.AUTH_REQUIRED)
                BackupOperation.Network -> fail(BackupFailure.NETWORK)
                BackupOperation.Unavailable -> fail(BackupFailure.UNAVAILABLE)
                is BackupOperation.Rejected -> fail(BackupFailure.REJECTED)
                BackupOperation.NotConfigured ->
                    _uiState.value = _uiState.value.copy(phase = BackupPhase.NotConfigured)
            }
        }
    }

    private fun fail(reason: BackupFailure) {
        _uiState.value = _uiState.value.copy(phase = BackupPhase.Failed(reason))
    }

    /** Depois de um sucesso a tela volta a refletir o banco — inclusive a hora vinda do servidor. */
    private suspend fun refreshAfterOperation() {
        refresh(force = true)
    }
}
