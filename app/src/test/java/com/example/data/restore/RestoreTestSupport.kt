package com.example.data.restore

import android.content.Context
import androidx.room.Room
import com.example.data.backup.BackupCanonicalJson
import com.example.data.backup.BackupMetadataDto
import com.example.data.backup.BackupSnapshotBuilder
import com.example.data.backup.BackupSourceDto
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.sync.CloudOperationLock
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.IdGenerator
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncAggregateSnapshotBuilder
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Suporte dos testes de restore (T16.5).
 *
 * Tudo offline: nenhum teste desta pasta abre socket, fala com Firebase, com o Gemini ou com a
 * VPS. O servidor é substituído por [FakeRestoreApi], que serve **texto** — o que permite testar o
 * que mais importa aqui: um byte trocado, um payload semanticamente inválido, uma versão futura e
 * um download interrompido no meio.
 */

/**
 * Um Spark Backend de teste para leitura de backup.
 *
 * Ele guarda snapshots por `backupId` e os serve como o servidor real: o corpo é o texto canônico,
 * verbatim, e a metadata declara o SHA-256 **desse** texto. Um teste que queira corromper o
 * download troca o conteúdo servido sem tocar na metadata — exatamente o cenário que o hash existe
 * para pegar.
 */
class FakeRestoreApi(
    override val isConfigured: Boolean = true
) : RestoreApi {

    /** Os snapshots disponíveis, na ordem em que o servidor os devolveria (mais recente primeiro). */
    val snapshots = mutableListOf<StoredSnapshot>()

    /** O desfecho da próxima listagem, quando o teste quer forçar falha. */
    var nextListResult: RestoreListResult? = null

    /** O desfecho do próximo download, quando o teste quer forçar falha. */
    var nextDownloadResult: RestoreDownloadResult? = null

    /** Quantos downloads chegaram. Prova que a lista **não** baixa snapshot. */
    var downloadCount: Int = 0
        private set

    var listCount: Int = 0
        private set

    /** Quando não-nulo, `download` fica suspenso até este sinal — para testar troca de conta. */
    var downloadGate: CompletableDeferred<Unit>? = null

    override suspend fun list(): RestoreListResult {
        listCount += 1
        nextListResult?.let { return it }
        return RestoreListResult.Success(snapshots.map { it.metadata })
    }

    override suspend fun download(backupId: String, destination: File): RestoreDownloadResult {
        downloadCount += 1
        downloadGate?.await()
        nextDownloadResult?.let { return it }

        val stored = snapshots.firstOrNull { it.metadata.backupId == backupId }
            ?: return RestoreDownloadResult.NotFound

        destination.parentFile?.mkdirs()
        destination.writeText(stored.body, Charsets.UTF_8)
        return RestoreDownloadResult.Success(stored.body.toByteArray(Charsets.UTF_8).size.toLong())
    }

    /**
     * Publica um snapshot, com a metadata coerente com o texto.
     *
     * [corruptBody] serve aos testes de integridade: o corpo servido muda, a metadata **não** — que
     * é o que o servidor faria se algo corrompesse o conteúdo no caminho ou no disco.
     */
    fun publish(
        canonicalBody: String,
        backupId: String = "backup-${snapshots.size + 1}",
        createdAt: Long = 1_700_000_000_000L,
        itemCount: Int? = null,
        corruptBody: String? = null
    ): BackupMetadataDto {
        val metadata = BackupMetadataDto(
            backupId = backupId,
            clientBackupId = "cliente-$backupId",
            backupSchemaVersion = com.example.data.backup.BackupContract.SCHEMA_VERSION,
            createdAt = createdAt,
            itemCount = itemCount ?: countItems(canonicalBody),
            sizeBytes = canonicalBody.toByteArray(Charsets.UTF_8).size.toLong(),
            payloadHash = BackupCanonicalJson.sha256(canonicalBody)
        )
        // Mais recente primeiro, como o servidor devolve.
        snapshots.add(0, StoredSnapshot(metadata, corruptBody ?: canonicalBody))
        return metadata
    }

    private fun countItems(body: String): Int =
        Json.parseToJsonElement(body).let { element ->
            (element as? kotlinx.serialization.json.JsonObject)
                ?.get("items")
                ?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.size ?: 0
        }

    data class StoredSnapshot(val metadata: BackupMetadataDto, val body: String)
}

/**
 * Um restore montado sobre Room de verdade.
 *
 * Repositórios reais, transações reais, DAOs reais — só o servidor é dublê. Um restore testado
 * contra mocks de DAO não provaria nada do que esta tarefa promete: rollback, chave estrangeira,
 * ordem de exclusão e `localId` regenerado só existem no banco.
 */
class RestoreHarness(
    val database: AppDatabase,
    val context: Context,
    val api: FakeRestoreApi = FakeRestoreApi(),
    val filesRoot: File,
    val operationLock: CloudOperationLock = CloudOperationLock(),
    val clock: () -> Long = { 1_800_000_000_000L },
    idGenerator: IdGenerator = IdGenerator { java.util.UUID.randomUUID().toString() },
    /**
     * Permite trocar o DAO do vínculo por um que falha.
     *
     * É assim que se testa uma falha **no meio** da transação de aplicação: sem um ponto de falha
     * injetável, o teste de rollback dependeria de provocar erro de banco por acaso — e provaria
     * menos.
     */
    bindingDao: com.example.data.backup.CloudDataBindingDao = database.cloudDataBindingDao()
) {

    val settingsManager = SettingsManager(context)

    val snapshotBuilder = BackupSnapshotBuilder(
        workoutDao = database.workoutDao(),
        bodyMeasurementDao = database.bodyMeasurementDao(),
        weeklyGoalDao = database.weeklyGoalDao(),
        aggregates = SyncAggregateSnapshotBuilder(
            database.workoutDao(),
            database.bodyMeasurementDao()
        )
    )

    val files = RestoreFileStore(filesRoot)

    val transaction = RestoreTransaction(
        transactions = RoomTransactionRunner(database),
        restoreDao = database.restoreDao(),
        workoutDao = database.workoutDao(),
        bodyMeasurementDao = database.bodyMeasurementDao(),
        weeklyGoalDao = database.weeklyGoalDao(),
        bindingDao = bindingDao,
        clock = clock
    )

    val safetySnapshots = RestoreSafetySnapshotStore(
        snapshotBuilder = snapshotBuilder,
        settingsManager = settingsManager,
        deviceIdProvider = DeviceIdProvider(settingsManager),
        transactions = RoomTransactionRunner(database),
        files = files,
        source = SOURCE,
        clock = clock
    )

    val repository = RestoreRepository(
        api = api,
        attemptDao = database.restoreAttemptDao(),
        restoreDao = database.restoreDao(),
        bindingDao = database.cloudDataBindingDao(),
        outboxDao = database.syncOutboxDao(),
        snapshotBuilder = snapshotBuilder,
        planBuilder = RestorePlanBuilder(database.workoutDao()),
        transaction = transaction,
        safetySnapshots = safetySnapshots,
        files = files,
        settingsManager = settingsManager,
        deviceIdProvider = DeviceIdProvider(settingsManager),
        transactions = RoomTransactionRunner(database),
        operationLock = operationLock,
        idGenerator = idGenerator,
        clock = clock
    )

    /**
     * O snapshot canônico do estado **atual** deste banco.
     *
     * É o mesmo caminho do backup da T16.4 — mesmo montador, mesma forma canônica. É o que torna
     * o teste de ida e volta honesto: o que é publicado no servidor de teste é literalmente o que o
     * app enviaria.
     */
    suspend fun canonicalSnapshotOfCurrentState(
        clientBackupId: String = "snapshot-de-teste",
        deviceId: String = "device-de-teste"
    ): String {
        val preferences = snapshotBuilder.preferencesItem(settingsManager)
        val items = RoomTransactionRunner(database).runInTransaction {
            (snapshotBuilder.captureRoomItems() + preferences)
                .sortedWith(compareBy({ it.entityType }, { it.syncId }))
        }
        val snapshot = com.example.data.backup.BackupSnapshotDto(
            clientBackupId = clientBackupId,
            backupSchemaVersion = com.example.data.backup.BackupContract.SCHEMA_VERSION,
            deviceId = deviceId,
            capturedAt = clock(),
            source = SOURCE,
            items = items
        )
        return BackupCanonicalJson.canonicalize(JSON.encodeToJsonElement(snapshot))
    }

    companion object {
        val SOURCE = BackupSourceDto(
            appVersionName = "teste",
            appVersionCode = 1,
            databaseVersion = AppDatabase.SCHEMA_VERSION
        )
        val JSON = Json { encodeDefaults = true }

        /** Um banco em memória com o schema atual. */
        fun database(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

/**
 * Leitura das fixtures canônicas do contrato.
 *
 * As mesmas que o backend lê (`contracts/backup/v1/fixtures/`). Uma mudança de formato em um lado
 * quebra os dois testes juntos, que é a razão de o diretório de contrato existir.
 */
object RestoreContractFixtures {

    private const val RELATIVE = "contracts/backup/v1/fixtures"

    fun text(name: String): String = file(name).readText()

    private fun file(name: String): File {
        // O diretório de trabalho do teste pode ser a raiz do repositório ou o módulo `app/`.
        val candidates = listOf(File("$RELATIVE/$name.json"), File("../$RELATIVE/$name.json"))
        return candidates.firstOrNull { it.isFile }
            ?: error("fixture não encontrada: $name (procurado em ${candidates.map { it.absolutePath }})")
    }
}
