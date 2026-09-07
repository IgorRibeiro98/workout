package com.example.data.restore

import com.example.data.backup.BackupCanonicalJson
import com.example.data.backup.BackupItemDto
import com.example.data.backup.BackupSnapshotBuilder
import com.example.data.backup.BackupSnapshotDto
import com.example.data.backup.BackupSourceDto
import com.example.data.datastore.SettingsManager
import com.example.data.sync.DeviceIdProvider
import com.example.data.sync.TransactionRunner
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Onde os arquivos de um restore moram (T16.5).
 *
 * ```text
 * <files>/restore/<restoreAttemptId>.json           o snapshot baixado
 * <files>/restore/<restoreAttemptId>-safety.json    o estado anterior, para desfazer
 * ```
 *
 * **Armazenamento privado do app**, sempre. Nada disso vai para `Downloads`, `Documents`,
 * `getExternalFilesDir` ou qualquer lugar que outro app leia: um snapshot é o histórico inteiro da
 * pessoa — treinos, cargas, medidas corporais e notas.
 *
 * Arquivo, e não coluna de banco: o documento é grande, é rebaixável (o download é read-only no
 * servidor) e não tem por que atravessar a transação que substitui o dataset. O que fica no banco é
 * o **caminho** e a fase, que é o que precisa sobreviver a um process death.
 */
class RestoreFileStore(private val root: File) {

    /** O snapshot baixado desta tentativa. */
    fun downloadFile(restoreAttemptId: String): File = file("$restoreAttemptId.json")

    /** O snapshot de segurança do estado anterior a esta tentativa. */
    fun safetyFile(restoreAttemptId: String): File = file("$restoreAttemptId-safety.json")

    private fun file(name: String): File {
        root.mkdirs()
        return File(root, name)
    }

    /**
     * Apaga os arquivos de uma tentativa encerrada.
     *
     * Chamado quando a tentativa vira `COMPLETED` ou `ABANDONED` — nunca antes: enquanto o restore
     * não terminou, o snapshot de segurança é a única cópia do estado anterior deste aparelho.
     */
    fun deleteFilesOf(restoreAttemptId: String) {
        downloadFile(restoreAttemptId).delete()
        safetyFile(restoreAttemptId).delete()
    }

    /**
     * Remove arquivos que não pertencem a nenhuma tentativa viva.
     *
     * Sem isso, uma sequência de tentativas interrompidas acumularia snapshots no aparelho para
     * sempre. Com ele, a política é simples e dizível: **enquanto a tentativa não terminar, os
     * arquivos dela ficam; depois, saem.**
     */
    fun deleteOrphans(liveAttemptIds: Set<String>) {
        val files = root.listFiles() ?: return
        files.forEach { file ->
            val attemptId = file.name.removeSuffix(".json").removeSuffix("-safety")
            if (attemptId !in liveAttemptIds) file.delete()
        }
    }
}

/**
 * O snapshot de segurança do estado local, criado **antes** de qualquer substituição (T16.5).
 *
 * ## Por que ele existe
 *
 * ```text
 * PROIBIDO   apagar o dataset → aplicar o backup → falhar → não ter para onde voltar
 * OBRIGATÓRIO  salvar o estado atual → aplicar o backup → falhar → voltar
 * ```
 *
 * Ele não é um backup na nuvem e **nunca** é enviado ao servidor: é uma proteção local e temporária
 * para o intervalo em que o dataset está sendo trocado. Mandá-lo para a VPS criaria um backup que o
 * usuário não pediu, na conta de alguém, a partir de um estado que ele acabou de decidir descartar.
 *
 * ## Ele reusa o serializador do backup
 *
 * O formato é o mesmo `BackupSnapshotDto` da T16.4, montado pelo mesmo [BackupSnapshotBuilder]. Não
 * existe uma segunda representação do estado pessoal no Spark — e a consequência prática é que
 * desfazer um restore é *restaurar*: o mesmo leitor, o mesmo validador e a mesma transação.
 *
 * ## Sem rede
 *
 * A criação lê Room e DataStore e escreve um arquivo. Ela não depende de conta, de internet nem do
 * Spark Backend: um restore que falhe com o servidor fora do ar ainda precisa ter para onde voltar.
 */
class RestoreSafetySnapshotStore(
    private val snapshotBuilder: BackupSnapshotBuilder,
    private val settingsManager: SettingsManager,
    private val deviceIdProvider: DeviceIdProvider,
    private val transactions: TransactionRunner,
    private val files: RestoreFileStore,
    private val source: BackupSourceDto,
    private val json: Json = Json { encodeDefaults = true },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Captura o estado atual e o grava. Devolve o arquivo escrito.
     *
     * A captura do Room acontece **dentro de uma transação**: ler os treinos, o usuário alterar algo
     * e só então ler as sessões produziria um snapshot internamente contraditório — exatamente o
     * cuidado que a T16.4 já tem ao montar um backup.
     *
     * As preferências vêm do DataStore, fora da transação, pela razão de sempre: não existe
     * atomicidade entre os dois armazenamentos, e fingir que existe seria pior do que assumir.
     */
    suspend fun create(restoreAttemptId: String): File {
        val deviceId = deviceIdProvider.deviceId()
        val preferences = snapshotBuilder.preferencesItem(settingsManager)
        val now = clock()

        val items: List<BackupItemDto> = transactions.runInTransaction {
            (snapshotBuilder.captureRoomItems() + preferences)
                .sortedWith(compareBy({ it.entityType }, { it.syncId }))
        }

        val snapshot = BackupSnapshotDto(
            // A identidade da tentativa **de restore** nomeia este snapshot. Ele não é um backup e
            // nunca vira um: nenhum `clientBackupId` de backup é reaproveitado aqui.
            clientBackupId = restoreAttemptId,
            backupSchemaVersion = com.example.data.backup.BackupContract.SCHEMA_VERSION,
            deviceId = deviceId,
            capturedAt = now,
            source = source,
            items = items
        )

        val canonical = BackupCanonicalJson.canonicalHash(json.encodeToJsonElement(snapshot))
        val file = files.safetyFile(restoreAttemptId)
        file.writeText(canonical.text, Charsets.UTF_8)
        return file
    }
}
