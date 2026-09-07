package com.example.data.restore

import com.example.data.backup.BackupMetadataDto
import com.example.data.backup.BackupSnapshotBuilder
import com.example.data.backup.CloudDataBindingDao
import com.example.data.backup.UserPreferencesBackupDto
import com.example.data.datastore.SettingsManager
import com.example.data.sync.CloudOperationLock
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.IdGenerator
import com.example.data.sync.RandomUuidIdGenerator
import com.example.data.sync.SyncOutboxDao
import com.example.data.sync.TransactionRunner
import java.io.File
import java.io.IOException

/**
 * O caso de uso do restore no Android (T16.5).
 *
 * ```text
 * Conta Spark autenticada
 *   ↓  listar (metadata, sem baixar snapshot nenhum)
 * o usuário escolhe um backup
 *   ↓  baixar para arquivo privado
 *   ↓  SHA-256 conferido contra a metadata do servidor
 *   ↓  versão, schema e semântica validados por inteiro
 *   ↓  RestorePlan  →  preview com contagens reais e avisos
 * confirmação explícita do usuário
 *   ↓  snapshot de segurança do estado atual (arquivo privado, sem rede)
 *   ↓  transação Room: substitui o dataset, grava o vínculo, zera a Outbox
 *   ↓  preferências no DataStore
 * COMPLETED
 * ```
 *
 * ## A ordem é o invariante
 *
 * **Nada local é alterado** antes da confirmação — e a confirmação só é oferecida depois de o
 * snapshot ter sido baixado por inteiro, conferido e validado. O fluxo proibido é o oposto deste:
 * apagar primeiro, baixar depois, e descobrir a falha sem ter para onde voltar.
 *
 * ## Restore não é sync
 *
 * Ele **substitui** o dataset local por um snapshot escolhido. Não mescla, não resolve conflito,
 * não baixa mudanças incrementais e não roda sozinho: não há caminho que restaure no login, na
 * abertura do app, ao abrir o Perfil ou ao listar backups.
 *
 * ## O que ele não faz
 *
 * Não cria backup, não apaga o snapshot remoto, não liga worker, não agenda nada e não retenta
 * sozinho. O download é read-only no servidor — repetir é seguro, e repetir é decisão do usuário.
 */
class RestoreRepository(
    private val api: RestoreApi,
    private val attemptDao: RestoreAttemptDao,
    private val restoreDao: RestoreDao,
    private val bindingDao: CloudDataBindingDao,
    private val outboxDao: SyncOutboxDao,
    private val snapshotBuilder: BackupSnapshotBuilder,
    private val planBuilder: RestorePlanBuilder,
    private val transaction: RestoreTransaction,
    private val safetySnapshots: RestoreSafetySnapshotStore,
    private val files: RestoreFileStore,
    private val settingsManager: SettingsManager,
    private val deviceIdProvider: DeviceIdProvider,
    private val transactions: TransactionRunner,
    private val reader: RestoreSnapshotReader = RestoreSnapshotReader(),
    private val operationLock: CloudOperationLock = CloudOperationLock(),
    private val idGenerator: IdGenerator = RandomUuidIdGenerator,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    val isConfigured: Boolean get() = api.isConfigured

    /** A tentativa interrompida que ainda precisa ser resolvida, se houver. */
    suspend fun unfinishedAttempt(): RestoreAttemptEntity? = attemptDao.oldestUnfinished()

    /** A última restauração concluída nesta conta — o que a UI mostra como "restaurado em". */
    suspend fun lastCompleted(ownerUid: String): RestoreAttemptEntity? =
        attemptDao.lastCompletedFor(ownerUid)

    /**
     * Os backups disponíveis para a conta atual. **Só metadata.**
     *
     * Nenhum snapshot é baixado aqui: montar uma lista de datas não pode custar o download do
     * histórico inteiro, e a lista existe justamente para o usuário escolher **antes** de baixar.
     *
     * As regras de conta valem antes da rede: um dataset que pertence a outra Conta Spark não
     * lista, não baixa e não restaura.
     */
    suspend fun availableBackups(currentUid: String?): RestoreListing {
        if (!api.isConfigured) return RestoreListing.Failed(RestoreError.NOT_CONFIGURED)
        if (currentUid.isNullOrBlank()) return RestoreListing.Failed(RestoreError.AUTH_REQUIRED)

        val binding = bindingDao.get()
        if (binding != null && binding.ownerUid != currentUid) {
            return RestoreListing.Failed(RestoreError.ACCOUNT_MISMATCH)
        }
        if (attemptDao.oldestUnfinished() != null) {
            return RestoreListing.Failed(RestoreError.RESTORE_RECOVERY_REQUIRED)
        }

        return when (val result = api.list()) {
            // A ordem do servidor é preservada: quem decide qual é o mais recente é a sequência
            // dele, não o relógio deste aparelho.
            is RestoreListResult.Success -> RestoreListing.Available(result.items)
            RestoreListResult.NotConfigured -> RestoreListing.Failed(RestoreError.NOT_CONFIGURED)
            RestoreListResult.AuthRequired -> RestoreListing.Failed(RestoreError.AUTH_REQUIRED)
            RestoreListResult.Network -> RestoreListing.Failed(RestoreError.NETWORK)
            RestoreListResult.Unavailable -> RestoreListing.Failed(RestoreError.UNAVAILABLE)
            is RestoreListResult.Rejected -> RestoreListing.Failed(RestoreError.BACKUP_DOWNLOAD_FAILED)
        }
    }

    /**
     * Baixa, confere e valida o backup escolhido — sem tocar em dado local.
     *
     * O resultado é um [RestorePlan] pronto para virar preview. Se qualquer etapa falhar, a
     * tentativa é encerrada como `ABANDONED` e o aparelho continua exatamente como estava: essa é
     * a promessa que separa "restore falhou" de "dados perdidos".
     */
    suspend fun prepare(backup: BackupMetadataDto, currentUid: String?): RestorePreparation =
        operationLock.tryRun { runPrepare(backup, currentUid) }
            ?: RestorePreparation.Failed(RestoreError.RESTORE_IN_PROGRESS, null)

    private suspend fun runPrepare(
        backup: BackupMetadataDto,
        currentUid: String?
    ): RestorePreparation {
        if (!api.isConfigured) return failed(RestoreError.NOT_CONFIGURED)
        if (currentUid.isNullOrBlank()) return failed(RestoreError.AUTH_REQUIRED)

        val binding = bindingDao.get()
        if (binding != null && binding.ownerUid != currentUid) {
            // Dataset da conta A, sessão da conta B. Restaurar aqui transferiria dados entre
            // contas por efeito colateral — e trocar o dono de um dataset é uma política que o
            // Spark ainda não tem (fora do escopo da T16.5).
            return failed(RestoreError.ACCOUNT_MISMATCH)
        }
        if (attemptDao.oldestUnfinished() != null) return failed(RestoreError.RESTORE_RECOVERY_REQUIRED)
        if (restoreDao.countActiveSessions() > 0) return failed(RestoreError.WORKOUT_IN_PROGRESS)
        if (!RestoreContract.supports(backup.backupSchemaVersion)) {
            // A versão já é conhecida pela metadata: recusar aqui evita baixar um documento que
            // este app não saberia ler de qualquer forma.
            return failed(RestoreError.UNSUPPORTED_BACKUP_VERSION)
        }

        val now = clock()
        val restoreAttemptId = idGenerator.newId()
        val attemptId = attemptDao.insert(
            RestoreAttemptEntity(
                restoreAttemptId = restoreAttemptId,
                backupId = backup.backupId,
                ownerUid = currentUid,
                payloadHash = backup.payloadHash,
                backupSchemaVersion = backup.backupSchemaVersion,
                backupCreatedAt = backup.createdAt,
                datasetWasUnbound = binding == null,
                status = RestorePhase.DOWNLOADING.name,
                createdAt = now,
                updatedAt = now
            )
        )

        val file = files.downloadFile(restoreAttemptId)
        val download = api.download(backup.backupId, file)
        if (download !is RestoreDownloadResult.Success) {
            return abandon(attemptId, restoreAttemptId, downloadError(download))
        }

        return try {
            // Integridade antes de interpretação: um documento que não é o que a metadata descreve
            // não merece nem ser parseado.
            BackupIntegrityVerifier.verify(file, backup.payloadHash)

            val snapshot = reader.read(file)
            if (snapshot.itemCount != backup.itemCount) {
                // Metadata e conteúdo precisam concordar. Divergir aqui significa que um dos dois
                // está errado, e restaurar "o que veio" seria confiar no que não fecha.
                throw RestoreException(
                    RestoreError.INVALID_BACKUP,
                    "itens do snapshot divergem da metadata"
                )
            }

            val plan = planBuilder.build(
                backup = backup,
                ownerUid = currentUid,
                snapshot = snapshot,
                localSummary = transactions.runInTransaction { snapshotBuilder.summary() },
                localSessionsAfterBackup = restoreDao.countCompletedSessionsAfter(backup.createdAt),
                pendingMutations = outboxDao.pendingFor(currentUid).size
            )

            attemptDao.updateDownloadPath(attemptId, file.absolutePath, clock())
            attemptDao.updateStatus(attemptId, RestorePhase.VALIDATED.name, clock())
            RestorePreparation.Ready(plan, restoreAttemptId)
        } catch (e: RestoreException) {
            abandon(attemptId, restoreAttemptId, e.error, e.detail)
        } catch (e: IOException) {
            abandon(attemptId, restoreAttemptId, RestoreError.BACKUP_DOWNLOAD_FAILED)
        }
    }

    /**
     * Desiste de uma tentativa que ainda **não** alterou nada.
     *
     * É o "Cancelar" do preview. Sem ele, uma tentativa validada e não confirmada ficaria em aberto
     * e bloquearia a próxima — o app trataria uma desistência como um restore pela metade.
     *
     * Só encerra tentativa em fase anterior à mutação. Depois de `ROOM_APPLIED` o dataset já foi
     * substituído, e desistir não é uma opção: o desfecho é retomar ou desfazer ([recover]).
     */
    suspend fun discard(restoreAttemptId: String) {
        val attempt = attemptDao.byRestoreAttemptId(restoreAttemptId) ?: return
        val alteredNothing = attempt.status == RestorePhase.DOWNLOADING.name ||
            attempt.status == RestorePhase.VALIDATED.name ||
            attempt.status == RestorePhase.SAFETY_SNAPSHOT_CREATED.name
        if (!alteredNothing) return

        attemptDao.abandon(attempt.id, CANCELLED_BY_USER, clock())
        files.deleteFilesOf(attempt.restoreAttemptId)
    }

    /**
     * Aplica o restore. **Só com [confirmed] verdadeiro.**
     *
     * A confirmação atravessa da UI até aqui em vez de ficar só na tela: nenhum caminho —
     * `LaunchedEffect` esquecido, retomada de tela, listener de login — consegue substituir o
     * dataset sem o usuário ter dito que sim.
     *
     * Antes de aplicar, a conta é revalidada: se a sessão mudou entre o preview e a confirmação, o
     * restore para com [RestoreError.ACCOUNT_CHANGED] e **zero** alteração local.
     */
    suspend fun confirm(
        plan: RestorePlan,
        restoreAttemptId: String,
        currentUid: String?,
        confirmed: Boolean
    ): RestoreOutcome =
        operationLock.tryRun { runConfirm(plan, restoreAttemptId, currentUid, confirmed) }
            ?: RestoreOutcome.Failed(RestoreError.RESTORE_IN_PROGRESS, null)

    private suspend fun runConfirm(
        plan: RestorePlan,
        restoreAttemptId: String,
        currentUid: String?,
        confirmed: Boolean
    ): RestoreOutcome {
        if (!confirmed) return RestoreOutcome.Failed(RestoreError.RESTORE_CONFIRMATION_REQUIRED, null)

        val attempt = attemptDao.byRestoreAttemptId(restoreAttemptId)
            ?: return RestoreOutcome.Failed(RestoreError.RESTORE_APPLY_FAILED, "tentativa ausente")
        if (attempt.status != RestorePhase.VALIDATED.name) {
            return RestoreOutcome.Failed(RestoreError.RESTORE_APPLY_FAILED, "fase inesperada")
        }

        // Sair da conta antes da aplicação cancela o restore: um dataset não pode passar a
        // pertencer a uma conta que não está mais ali para responder por ele.
        if (currentUid.isNullOrBlank()) {
            return abandonOutcome(attempt, RestoreError.AUTH_REQUIRED)
        }
        if (currentUid != attempt.ownerUid) {
            return abandonOutcome(attempt, RestoreError.ACCOUNT_CHANGED)
        }
        val binding = bindingDao.get()
        if (binding != null && binding.ownerUid != currentUid) {
            return abandonOutcome(attempt, RestoreError.ACCOUNT_MISMATCH)
        }
        if (restoreDao.countActiveSessions() > 0) {
            return abandonOutcome(attempt, RestoreError.WORKOUT_IN_PROGRESS)
        }

        // O snapshot de segurança vem **antes** da mutação. Criá-lo depois seria criá-lo tarde:
        // não haveria mais estado anterior para salvar.
        val safetyFile = try {
            safetySnapshots.create(attempt.restoreAttemptId)
        } catch (e: IOException) {
            return abandonOutcome(attempt, RestoreError.RESTORE_APPLY_FAILED, "snapshot de segurança")
        }
        attemptDao.updateSafetySnapshotPath(attempt.id, safetyFile.absolutePath, clock())
        attemptDao.updateStatus(attempt.id, RestorePhase.SAFETY_SNAPSHOT_CREATED.name, clock())

        val applied = try {
            transaction.apply(
                plan = plan,
                binding = RestoreBindingOutcome.Bind(
                    ownerUid = attempt.ownerUid,
                    deviceId = deviceIdProvider.deviceId(),
                    backupId = attempt.backupId,
                    backupCreatedAt = attempt.backupCreatedAt
                )
            )
        } catch (e: Exception) {
            // A transação do Room desfez tudo: o dataset anterior continua inteiro. A tentativa é
            // encerrada e os arquivos saem — inclusive o de segurança, que já não protege nada.
            return abandonOutcome(attempt, RestoreError.RESTORE_APPLY_FAILED, e.javaClass.simpleName)
        }
        attemptDao.updateStatus(attempt.id, RestorePhase.ROOM_APPLIED.name, clock())

        // A partir daqui o dataset **já foi substituído**. Uma falha nas preferências não pode ser
        // tratada como falha do restore inteiro: ela é uma fase pendente, com recuperação própria.
        return when (val preferences = applyPreferences(plan.snapshot.preferences)) {
            true -> {
                attemptDao.updateStatus(attempt.id, RestorePhase.PREFERENCES_APPLIED.name, clock())
                complete(attempt)
                RestoreOutcome.Success(applied)
            }

            else -> {
                // Fica em ROOM_APPLIED de propósito: a próxima abertura do app retoma esta fase, e
                // até lá a UI **não** diz que o restore terminou.
                RestoreOutcome.RecoveryPending(applied)
            }
        }
    }

    /**
     * Retoma ou desfaz uma tentativa interrompida — determinística, pela fase (T16.5).
     *
     * ```text
     * DOWNLOADING / VALIDATED / SAFETY_SNAPSHOT_CREATED  → nada foi aplicado  → encerrar
     * ROOM_APPLIED                                        → dado já substituído → retomar
     * PREFERENCES_APPLIED                                 → só faltou concluir  → concluir
     * ```
     *
     * O caso interessante é `ROOM_APPLIED`: o Room já é o backup e o DataStore ainda não. Retomar é
     * o desfecho correto — o usuário pediu aquele dataset, e ele já está lá. Desfazer só entra em
     * cena quando retomar é impossível, e aí o snapshot de segurança reconstrói o estado anterior.
     *
     * Enquanto isso não acontece, nenhuma tela diz "restaurado": a fase é a autoridade sobre isso.
     */
    suspend fun recover(): RestoreRecoveryResult =
        // A mesma trava do restore e do backup. Ela quase nunca está ocupada aqui — a recuperação
        // roda na abertura do app, antes de existir tela —, mas recuperar em paralelo a uma
        // operação que já está mexendo no mesmo banco seria a única forma de a própria recuperação
        // corromper algo.
        operationLock.tryRun { runRecovery() } ?: RestoreRecoveryResult.Postponed

    private suspend fun runRecovery(): RestoreRecoveryResult {
        val attempt = attemptDao.oldestUnfinished()
        if (attempt == null) {
            files.deleteOrphans(emptySet())
            return RestoreRecoveryResult.NothingToRecover
        }

        return when (attempt.status) {
            RestorePhase.DOWNLOADING.name,
            RestorePhase.VALIDATED.name,
            RestorePhase.SAFETY_SNAPSHOT_CREATED.name -> {
                // Nenhuma dessas fases alterou dado de domínio: encerrar é literalmente não fazer
                // nada com o banco.
                abandon(attempt.id, attempt.restoreAttemptId, RestoreError.RESTORE_APPLY_FAILED)
                RestoreRecoveryResult.Discarded
            }

            RestorePhase.ROOM_APPLIED.name -> resumeAfterRoom(attempt)

            RestorePhase.PREFERENCES_APPLIED.name -> {
                complete(attempt)
                RestoreRecoveryResult.Resumed
            }

            else -> RestoreRecoveryResult.NothingToRecover
        }
    }

    /**
     * `ROOM_APPLIED`: o dataset já é o do backup. Falta a fase das preferências.
     *
     * Retomar exige o documento baixado, que continua em disco justamente para isto. Sem ele — e
     * só sem ele — o desfecho é desfazer, reconstruindo o estado anterior a partir do snapshot de
     * segurança. Marcar sucesso com uma fase pendente seria a única saída inaceitável.
     */
    private suspend fun resumeAfterRoom(attempt: RestoreAttemptEntity): RestoreRecoveryResult {
        val downloaded = attempt.downloadPath?.let { File(it) }?.takeIf { it.isFile }
        if (downloaded != null) {
            val preferences = runCatching { reader.read(downloaded).preferences }.getOrNull()
            if (applyPreferences(preferences)) {
                attemptDao.updateStatus(attempt.id, RestorePhase.PREFERENCES_APPLIED.name, clock())
                complete(attempt)
                return RestoreRecoveryResult.Resumed
            }
        }
        return rollback(attempt)
    }

    /**
     * Desfaz até o estado anterior, a partir do snapshot de segurança.
     *
     * Desfazer é *restaurar*: mesmo leitor, mesmo validador, mesma transação — e por isso o
     * caminho de rollback é exercitado pelos mesmos testes que exercitam o restore. O vínculo volta
     * ao que era: se o dataset era sem dono, ele volta a ser sem dono.
     */
    private suspend fun rollback(attempt: RestoreAttemptEntity): RestoreRecoveryResult {
        val safety = attempt.safetySnapshotPath?.let { File(it) }?.takeIf { it.isFile }
            ?: run {
                // Sem snapshot de segurança não há como reconstruir. O dataset restaurado continua
                // íntegro — ele é um backup válido —, e a tentativa é encerrada dizendo o que houve.
                abandon(attempt.id, attempt.restoreAttemptId, RestoreError.RESTORE_RECOVERY_REQUIRED)
                return RestoreRecoveryResult.RolledBackWithoutPreferences
            }

        return try {
            val snapshot = reader.read(safety)
            val plan = planBuilder.build(
                backup = com.example.data.backup.BackupMetadataDto(
                    backupId = attempt.backupId,
                    clientBackupId = attempt.restoreAttemptId,
                    backupSchemaVersion = attempt.backupSchemaVersion,
                    createdAt = attempt.backupCreatedAt,
                    itemCount = snapshot.itemCount,
                    sizeBytes = safety.length(),
                    payloadHash = attempt.payloadHash
                ),
                ownerUid = attempt.ownerUid,
                snapshot = snapshot,
                localSummary = transactions.runInTransaction { snapshotBuilder.summary() },
                localSessionsAfterBackup = 0,
                pendingMutations = 0
            )
            transaction.apply(
                plan = plan,
                binding = if (attempt.datasetWasUnbound) {
                    RestoreBindingOutcome.Unbind
                } else {
                    RestoreBindingOutcome.Preserve
                }
            )
            // As preferências do estado anterior voltam junto: elas fazem parte do snapshot de
            // segurança pelo mesmo motivo que fazem parte de um backup.
            applyPreferences(snapshot.preferences)
            abandon(attempt.id, attempt.restoreAttemptId, RestoreError.RESTORE_APPLY_FAILED)
            RestoreRecoveryResult.RolledBack
        } catch (e: Exception) {
            abandon(attempt.id, attempt.restoreAttemptId, RestoreError.RESTORE_RECOVERY_REQUIRED)
            RestoreRecoveryResult.RolledBackWithoutPreferences
        }
    }

    /**
     * As preferências sincronizáveis, no DataStore.
     *
     * Fase própria porque **não existe transação atravessando Room e DataStore**. Fingir que existe
     * seria a mentira mais cara possível aqui: o app diria "restaurado" com metade do estado
     * aplicado. Em vez disso, a fase é registrada, e uma falha vira recuperação na próxima abertura.
     *
     * Preferências de **aparelho** — tema, som, vibração, tela ligada, timer — não são tocadas:
     * elas descrevem este celular, não o atleta (matriz de dados, Grupo C).
     */
    private suspend fun applyPreferences(preferences: UserPreferencesBackupDto?): Boolean = try {
        if (preferences != null) {
            settingsManager.setWeeklyGoal(preferences.weeklyGoal)
            settingsManager.setUseKg(preferences.useKg)
            settingsManager.setDefaultRestSeconds(preferences.defaultRestSeconds)
            settingsManager.setDefaultExerciseRestSeconds(preferences.defaultExerciseRestSeconds)
            settingsManager.setRirRpeEnabled(preferences.rirRpeEnabled)
            settingsManager.setAutoRestTimerOnSet(preferences.autoRestTimerOnSet)
        }
        true
    } catch (e: IOException) {
        false
    }

    /** Conclui a tentativa e recolhe os arquivos dela. */
    private suspend fun complete(attempt: RestoreAttemptEntity) {
        attemptDao.updateStatus(attempt.id, RestorePhase.COMPLETED.name, clock())
        // Só agora: enquanto a tentativa não terminou, o snapshot de segurança é a única cópia do
        // estado anterior deste aparelho.
        files.deleteFilesOf(attempt.restoreAttemptId)
    }

    private suspend fun abandon(
        attemptId: Long,
        restoreAttemptId: String,
        error: RestoreError,
        detail: String? = null
    ): RestorePreparation.Failed {
        attemptDao.abandon(attemptId, error.name, clock())
        files.deleteFilesOf(restoreAttemptId)
        return RestorePreparation.Failed(error, detail)
    }

    private suspend fun abandonOutcome(
        attempt: RestoreAttemptEntity,
        error: RestoreError,
        detail: String? = null
    ): RestoreOutcome {
        attemptDao.abandon(attempt.id, error.name, clock())
        files.deleteFilesOf(attempt.restoreAttemptId)
        return RestoreOutcome.Failed(error, detail)
    }

    private fun failed(error: RestoreError) = RestorePreparation.Failed(error, null)

    private companion object {
        /** Não é erro: é o usuário tendo desistido antes de qualquer alteração. */
        const val CANCELLED_BY_USER = "CANCELADO_PELO_USUARIO"
    }

    private fun downloadError(result: RestoreDownloadResult): RestoreError = when (result) {
        RestoreDownloadResult.AuthRequired -> RestoreError.AUTH_REQUIRED
        RestoreDownloadResult.Network -> RestoreError.NETWORK
        RestoreDownloadResult.Unavailable -> RestoreError.UNAVAILABLE
        RestoreDownloadResult.NotFound -> RestoreError.BACKUP_NOT_FOUND
        RestoreDownloadResult.ContentUnavailable -> RestoreError.BACKUP_CONTENT_UNAVAILABLE
        RestoreDownloadResult.TooLarge -> RestoreError.BACKUP_TOO_LARGE
        RestoreDownloadResult.NotConfigured -> RestoreError.NOT_CONFIGURED
        else -> RestoreError.BACKUP_DOWNLOAD_FAILED
    }
}

/** O desfecho de uma listagem de backups. */
sealed interface RestoreListing {

    /** Os backups da conta, na ordem do servidor. Pode estar vazia — e isso não é erro. */
    data class Available(val backups: List<BackupMetadataDto>) : RestoreListing

    data class Failed(val error: RestoreError) : RestoreListing
}

/** O desfecho da preparação: download, integridade e validação. */
sealed interface RestorePreparation {

    /** Validado. [plan] já contém contagens reais e avisos — é dele que sai o preview. */
    data class Ready(val plan: RestorePlan, val restoreAttemptId: String) : RestorePreparation

    /** Nada local foi alterado. Nenhuma exceção a esta regra. */
    data class Failed(val error: RestoreError, val detail: String?) : RestorePreparation
}

/** O desfecho da aplicação. */
sealed interface RestoreOutcome {

    /** Todas as fases concluíram. Só aqui a UI pode dizer "restaurado". */
    data class Success(val applied: RestoreApplied) : RestoreOutcome

    /**
     * O Room foi substituído e uma fase posterior não concluiu.
     *
     * **Não é sucesso** e não é apresentado como tal: a próxima abertura do app retoma ou desfaz.
     */
    data class RecoveryPending(val applied: RestoreApplied) : RestoreOutcome

    data class Failed(val error: RestoreError, val detail: String?) : RestoreOutcome
}

/** O que a recuperação de abertura fez. */
enum class RestoreRecoveryResult {
    /** Não havia tentativa interrompida. */
    NothingToRecover,

    /** A tentativa parou antes de qualquer alteração: foi apenas encerrada. */
    Discarded,

    /** O restore foi concluído a partir de onde parou. */
    Resumed,

    /** O estado anterior foi reconstruído a partir do snapshot de segurança. */
    RolledBack,

    /**
     * Outra operação de nuvem estava em curso; a recuperação não foi tentada agora.
     *
     * Não é sucesso e não é falha: a pendência continua registrada, e a próxima abertura resolve.
     */
    Postponed,

    /**
     * Não foi possível retomar nem reconstruir por completo.
     *
     * O dataset é o do backup (um estado íntegro e escolhido pelo usuário); o que pode ter ficado
     * para trás são as preferências. A tentativa é encerrada dizendo isso, e não fingindo sucesso.
     */
    RolledBackWithoutPreferences
}
