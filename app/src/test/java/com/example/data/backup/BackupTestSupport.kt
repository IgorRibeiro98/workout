package com.example.data.backup

import java.io.File
import kotlinx.coroutines.CompletableDeferred

/**
 * Suporte dos testes de backup (T16.4).
 *
 * Tudo offline: nenhum teste desta pasta abre socket, fala com Firebase, com o Gemini ou com a
 * VPS. O servidor é substituído por [FakeBackupApi], que existe justamente para poder **parar no
 * meio** de um upload — o instante em que os invariantes mais importantes da tarefa acontecem.
 */

/**
 * Um Spark Backend de teste.
 *
 * Ele guarda os corpos recebidos por `clientBackupId` para que os testes verifiquem, com o mesmo
 * rigor do servidor real, que um reenvio manda **os mesmos bytes**.
 */
class FakeBackupApi(
    override val isConfigured: Boolean = true
) : BackupApi {

    /** Os corpos crus recebidos, na ordem. */
    val uploads = mutableListOf<String>()

    /** Quantas requisições chegaram, incluindo as que falharam. */
    val callCount: Int get() = uploads.size

    /** O desfecho da próxima chamada. */
    var nextResult: BackupUploadResult = success()

    /**
     * Quando não-nulo, `upload` fica suspenso até este sinal ser completado.
     *
     * É como se testa "o usuário alterou um treino enquanto o backup subia" e "a resposta se
     * perdeu": os dois exigem controlar o instante em que o servidor responde.
     */
    var gate: CompletableDeferred<Unit>? = null

    /**
     * Completado assim que uma requisição **entra**, antes de o [gate] segurá-la.
     *
     * É o que torna o teste de "mutação durante o upload" determinístico: o teste espera aqui em
     * vez de dormir, e sabe que o snapshot já foi capturado e commitado quando prossegue.
     */
    var arrived: CompletableDeferred<Unit>? = null

    override suspend fun upload(canonicalBody: String): BackupUploadResult {
        uploads += canonicalBody
        arrived?.complete(Unit)
        gate?.await()
        return nextResult
    }

    override suspend fun latest(): BackupUploadResult = nextResult

    companion object {

        fun success(
            backupId: String = "backup-do-servidor",
            createdAt: Long = 1_800_000_000_000L,
            itemCount: Int = 0,
            payloadHash: String = "hash-do-servidor"
        ): BackupUploadResult.Success = BackupUploadResult.Success(
            BackupMetadataDto(
                backupId = backupId,
                clientBackupId = "reescrito-pelo-teste",
                backupSchemaVersion = BackupContract.SCHEMA_VERSION,
                createdAt = createdAt,
                itemCount = itemCount,
                sizeBytes = 0,
                payloadHash = payloadHash
            )
        )
    }
}

/**
 * Leitura das fixtures canônicas do contrato.
 *
 * Elas moram em `contracts/backup/v1/fixtures/`, **fora** de `app/`, e são exatamente as mesmas
 * que o teste do backend lê. Uma mudança de formato em um lado só quebra os dois testes juntos —
 * que é a razão de existir um diretório de contrato em vez de duas definições independentes.
 */
object BackupContractFixtures {

    private const val RELATIVE = "contracts/backup/v1/fixtures"

    fun text(name: String): String = file(name).readText()

    private fun file(name: String): File {
        // O diretório de trabalho do teste pode ser a raiz do repositório ou o módulo `app/`.
        val candidates = listOf(
            File("$RELATIVE/$name.json"),
            File("../$RELATIVE/$name.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("fixture não encontrada: $name (procurado em ${candidates.map { it.absolutePath }})")
    }
}
