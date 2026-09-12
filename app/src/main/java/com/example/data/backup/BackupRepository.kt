package com.example.data.backup

import com.example.data.datastore.SettingsManager
import com.example.data.sync.CloudOperationLock
import com.example.data.sync.CloudSyncState
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.IdGenerator
import com.example.data.sync.RandomUuidIdGenerator
import com.example.data.sync.SyncOutboxDao
import com.example.data.sync.TransactionRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * O caso de uso do backup no Android (T16.4).
 *
 * ```text
 * Perfil → "Ativar backup" → confirmação explícita
 *   ↓
 * transação Room:  vincula o dataset  +  captura o snapshot  +  lê o corte da Outbox
 *                  +  cria a BackupAttempt
 *   ↓ COMMIT
 * upload HTTPS autenticado
 *   ↓ o servidor confirma
 * transação Room:  marca a tentativa  +  baseline da Outbox coberta  +  registra o último backup
 * ```
 *
 * ## Login não adota nada
 *
 * A conta ficar conectada não vincula dado nenhum. O vínculo nasce de um toque explícito seguido
 * de confirmação, e é o [CloudDataBindingEntity] que o registra. Depois disso, sair da conta não o
 * remove, reiniciar não o remove, e entrar com outra conta **não** o transfere.
 *
 * ## O escopo é o vínculo, não o `FirebaseUser` atual
 *
 * Toda operação usa `binding.ownerUid`. Se o dataset é da conta A e a sessão atual é da conta B, o
 * resultado é [BackupOperation.AccountMismatch] — e nada sobe. É isso que impede uma troca de
 * conta no aparelho de virar transferência de histórico.
 *
 * ## O que este repositório não faz
 *
 * Não baixa, não restaura, não mescla, não resolve conflito e não agenda nada. Não existe
 * `WorkManager`, polling, retry automático ou backup em background: a única coisa que dispara um
 * backup é o usuário tocando no botão.
 */
class BackupRepository(
    private val bindingDao: CloudDataBindingDao,
    private val attemptDao: BackupAttemptDao,
    private val outboxDao: SyncOutboxDao,
    private val snapshotBuilder: BackupSnapshotBuilder,
    private val api: BackupApi,
    private val settingsManager: SettingsManager,
    private val deviceIdProvider: DeviceIdProvider,
    private val transactions: TransactionRunner,
    private val source: BackupSourceDto,
    /**
     * A trava compartilhada com o restore (T16.5).
     *
     * O padrão é uma trava só desta instância — que é o comportamento da T16.4. Em produção ela é
     * a **mesma** instância que o `RestoreRepository` recebe: backup e restore disputam o mesmo
     * banco, e um snapshot capturado no meio de uma substituição descreveria um estado que nunca
     * existiu.
     */
    private val operationLock: CloudOperationLock = CloudOperationLock(),
    private val idGenerator: IdGenerator = RandomUuidIdGenerator,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { encodeDefaults = true }
) {

    val isConfigured: Boolean get() = api.isConfigured

    /** O vínculo atual do dataset, ou `null` se ele ainda não tem dono. */
    suspend fun binding(): CloudDataBindingEntity? = bindingDao.get()

    /** O que será associado à conta — leitura pura, sem vincular nada. */
    suspend fun summary(): BackupSummary = transactions.runInTransaction {
        snapshotBuilder.summary()
    }

    /** A última tentativa confirmada desta conta, para a UI mostrar "último backup". */
    suspend fun lastSucceeded(ownerUid: String): BackupAttemptEntity? =
        attemptDao.lastSucceededFor(ownerUid)

    /** A tentativa pendente, se o app morreu ou a rede falhou antes da confirmação. */
    suspend fun pendingAttempt(ownerUid: String): BackupAttemptEntity? =
        attemptDao.oldestPendingFor(ownerUid)

    /**
     * Faz backup: adota o dataset se ainda não houver dono, captura o snapshot e envia.
     *
     * [currentUid] é o `uid` da sessão Firebase **atual**. Ele só é usado para dois fins: decidir a
     * quem o dataset será vinculado na primeira vez, e detectar divergência com um vínculo que já
     * existe. Depois do vínculo, quem manda é ele.
     *
     * [confirmedAdoption] precisa ser `true` para criar um vínculo novo. É a confirmação explícita
     * da UI atravessando até aqui: nenhum caminho vincula dado sem ela, nem por engano, nem por
     * um `LaunchedEffect` esquecido.
     */
    suspend fun backupNow(
        currentUid: String?,
        confirmedAdoption: Boolean
    ): BackupOperation {
        // Uma operação de nuvem por vez. Dez toques em "Fazer backup agora" precisam produzir
        // **um** backup, não dez — e a trava recusa em vez de enfileirar, porque enfileirar criaria
        // dez snapshots em sequência: resolveria a concorrência sem resolver o problema. Desde a
        // T16.5 a mesma trava barra um backup durante um restore, e vice-versa.
        return operationLock.tryRun { runBackup(currentUid, confirmedAdoption) }
            ?: BackupOperation.AlreadyRunning
    }

    private suspend fun runBackup(
        currentUid: String?,
        confirmedAdoption: Boolean
    ): BackupOperation {
        if (!api.isConfigured) return BackupOperation.NotConfigured

        val existing = bindingDao.get()

        val ownerUid = when {
            existing == null -> {
                if (currentUid.isNullOrBlank()) return BackupOperation.AuthRequired
                // Sem confirmação não há adoção. Este é o ponto que separa "entrei na conta" de
                // "estes dados passam a pertencer a ela".
                if (!confirmedAdoption) return BackupOperation.AdoptionRequired
                currentUid
            }

            // O dataset já tem dono, e não é quem está conectado agora. Recurso de nuvem fica
            // indisponível; o núcleo do Spark continua completo.
            currentUid != null && currentUid != existing.ownerUid ->
                return BackupOperation.AccountMismatch(existing.ownerUid)

            // Sem sessão: o vínculo continua valendo, mas não há token para enviar nada.
            currentUid == null -> return BackupOperation.AuthRequired

            else -> existing.ownerUid
        }

        val attempt = attemptDao.oldestPendingFor(ownerUid)
            ?: prepareAttempt(ownerUid, isNewBinding = existing == null)

        return send(attempt)
    }

    /**
     * Cria o vínculo (se preciso), captura o snapshot e registra a tentativa — **em uma transação**.
     *
     * Tudo que precisa ser consistente entre si acontece aqui dentro:
     *
     * - o vínculo passa a existir, então a Outbox começa a registrar no escopo daquela conta;
     * - o snapshot é lido de um estado único do banco, sem alteração no meio;
     * - o corte da Outbox é lido **no mesmo instante lógico** do snapshot;
     * - a tentativa nasce com o payload já congelado.
     *
     * Um commit, um rollback. Se qualquer parte falhar, não sobra vínculo sem tentativa nem
     * tentativa sem corte.
     *
     * As preferências vêm do DataStore, **fora** da transação: não existe atomicidade entre Room e
     * DataStore, e fingir que existe seria pior do que assumir a limitação (ver
     * [BackupSnapshotBuilder.preferencesItem]).
     */
    private suspend fun prepareAttempt(
        ownerUid: String,
        isNewBinding: Boolean
    ): BackupAttemptEntity {
        // Fora da transação de propósito: `deviceId` e preferências moram no DataStore, e abrir
        // I/O de outra fonte com uma transação Room aberta seria segurar o lock do banco à toa.
        val deviceId = deviceIdProvider.deviceId()
        val preferences = snapshotBuilder.preferencesItem(settingsManager)
        val now = clock()
        val clientBackupId = idGenerator.newId()

        return transactions.runInTransaction {
            if (isNewBinding) {
                bindingDao.insertIfAbsent(
                    CloudDataBindingEntity(
                        ownerUid = ownerUid,
                        state = CloudSyncState.PREPARING.name,
                        boundAt = now,
                        deviceId = deviceId
                    )
                )
            }

            val items = (snapshotBuilder.captureRoomItems() + preferences)
                // Ordem determinística: o mesmo dataset produz a mesma sequência de itens, e o
                // mesmo texto canônico. Sem isso, dois reenvios da mesma tentativa poderiam
                // divergir só pela ordem de leitura.
                .sortedWith(compareBy({ it.entityType }, { it.syncId }))

            val coveredSequence = outboxDao.maxSequence()

            val snapshot = BackupSnapshotDto(
                clientBackupId = clientBackupId,
                backupSchemaVersion = BackupContract.SCHEMA_VERSION,
                deviceId = deviceId,
                capturedAt = now,
                source = source,
                items = items
            )
            val canonical = BackupCanonicalJson.canonicalHash(json.encodeToJsonElement(snapshot))

            val attempt = BackupAttemptEntity(
                clientBackupId = clientBackupId,
                ownerUid = ownerUid,
                deviceId = deviceId,
                backupSchemaVersion = BackupContract.SCHEMA_VERSION,
                coveredOutboxSequence = coveredSequence,
                payloadHash = canonical.hash,
                payload = canonical.text,
                itemCount = items.size,
                sizeBytes = canonical.text.toByteArray(Charsets.UTF_8).size,
                createdAt = now
            )
            val id = attemptDao.insert(attempt)
            attempt.copy(id = id)
        }
    }

    /**
     * Envia a tentativa e, **só depois da confirmação do servidor**, aplica o baseline local.
     *
     * A ordem é o invariante. Limpar a Outbox antes da confirmação transformaria uma resposta
     * perdida em alteração perdida — e uma alteração perdida não tem conserto, enquanto um reenvio
     * tem.
     */
    private suspend fun send(attempt: BackupAttemptEntity): BackupOperation {
        // O corpo é o texto guardado, byte a byte. Nada é remontado: um reenvio precisa produzir
        // exatamente os mesmos bytes, ou o servidor o leria como conteúdo diferente.
        return when (val result = api.upload(attempt.payload)) {
            is BackupUploadResult.Success -> confirm(attempt, result.metadata)

            BackupUploadResult.AuthRequired -> fail(attempt, "AUTH_REQUIRED") {
                BackupOperation.AuthRequired
            }

            BackupUploadResult.Network -> fail(attempt, "NETWORK") { BackupOperation.Network }

            BackupUploadResult.Unavailable -> fail(attempt, "UNAVAILABLE") {
                BackupOperation.Unavailable
            }

            BackupUploadResult.NotConfigured -> BackupOperation.NotConfigured

            // As três abaixo são recusas **destes bytes**, não indisponibilidade. A tentativa é
            // imutável, então reenviá-la produziria a mesma resposta indefinidamente — e, como
            // `runBackup` sempre reaproveita a pendente mais antiga, o usuário ficava sem poder
            // fazer backup nenhum. Encerrar a tentativa é o que devolve a ele a próxima.
            BackupUploadResult.NotFound -> abandon(attempt, "NOT_FOUND") {
                BackupOperation.Rejected("NOT_FOUND")
            }

            is BackupUploadResult.Conflict -> abandon(attempt, result.code ?: "CONFLICT") {
                BackupOperation.Rejected(result.code)
            }

            is BackupUploadResult.Rejected -> abandon(attempt, result.code ?: "REJECTED") {
                BackupOperation.Rejected(result.code)
            }
        }
    }

    /**
     * O servidor confirmou: a tentativa vira sucesso, o vínculo vira `ENABLED` e a Outbox coberta
     * pelo snapshot é liberada.
     *
     * Tudo em uma transação, e **depois** da resposta. As entradas com sequência maior que o corte
     * — as que o usuário criou enquanto o upload acontecia — não são tocadas: elas descrevem
     * alterações que este snapshot não contém.
     */
    private suspend fun confirm(
        attempt: BackupAttemptEntity,
        metadata: BackupMetadataDto
    ): BackupOperation {
        val now = clock()
        transactions.runInTransaction {
            attemptDao.markSucceeded(
                id = attempt.id,
                serverBackupId = metadata.backupId,
                serverCreatedAt = metadata.createdAt,
                serverPayloadHash = metadata.payloadHash,
                now = now
            )
            outboxDao.deleteCoveredBy(attempt.ownerUid, attempt.coveredOutboxSequence)
            bindingDao.markBackupSucceeded(
                ownerUid = attempt.ownerUid,
                state = CloudSyncState.ENABLED.name,
                backupId = metadata.backupId,
                backupAt = metadata.createdAt
            )
        }
        return BackupOperation.Success(metadata)
    }

    /**
     * A tentativa continua pendente e recuperável.
     *
     * Nada é apagado: nem o snapshot guardado, nem a Outbox, nem o vínculo, nem dado local. Falhar
     * um backup não pode custar nada além do backup.
     */
    private suspend fun fail(
        attempt: BackupAttemptEntity,
        reason: String,
        result: () -> BackupOperation
    ): BackupOperation {
        attemptDao.markFailed(attempt.id, reason, clock())
        return result()
    }

    /**
     * A tentativa é encerrada: o servidor recusou **estes** bytes.
     *
     * A diferença para [fail] é o que acontece depois. Uma falha recuperável quer o mesmo snapshot
     * de novo — é o que torna uma resposta perdida inofensiva. Uma recusa definitiva quer o
     * contrário: o mesmo snapshot seria recusado de novo, e o que o usuário precisa é de uma
     * tentativa nova, capturada do estado atual.
     *
     * Continua não havendo retry automático: a próxima tentativa nasce quando **ele** pedir outro
     * backup. E nada local é perdido — a Outbox não foi liberada, porque nada subiu.
     */
    private suspend fun abandon(
        attempt: BackupAttemptEntity,
        reason: String,
        result: () -> BackupOperation
    ): BackupOperation {
        attemptDao.markAbandoned(attempt.id, reason, clock())
        return result()
    }
}

/** O desfecho de uma operação de backup, do ponto de vista de quem chamou. */
sealed interface BackupOperation {

    data class Success(val metadata: BackupMetadataDto) : BackupOperation

    /** Não há endereço de backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : BackupOperation

    /** É preciso estar na Conta Spark. Não é falha: é o convite para entrar. */
    data object AuthRequired : BackupOperation

    /** O dataset ainda não tem dono e a confirmação explícita não aconteceu. */
    data object AdoptionRequired : BackupOperation

    /** O dataset pertence a [ownerUid], e a sessão atual é de outra conta. */
    data class AccountMismatch(val ownerUid: String) : BackupOperation

    /** Sem rede. Recuperável — a tentativa continua guardada. */
    data object Network : BackupOperation

    /** Servidor fora do ar. Recuperável — a tentativa continua guardada. */
    data object Unavailable : BackupOperation

    /**
     * Já existe um backup em andamento. **Não** é erro, e não vira operação nova.
     *
     * É o toque repetido chegando enquanto o anterior sobe — o que a UI já evita, e o que o
     * repositório evita de novo porque é ele quem cria a tentativa.
     */
    data object AlreadyRunning : BackupOperation

    /** O servidor recusou o snapshot. [code] é vocabulário do Spark. */
    data class Rejected(val code: String?) : BackupOperation
}
