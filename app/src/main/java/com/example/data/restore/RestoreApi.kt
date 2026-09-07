package com.example.data.restore

import com.example.data.backup.BackupMetadataDto
import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkDownloadOutcome
import com.example.data.remote.spark.SparkHttpOutcome
import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A fronteira do restore com o servidor (T16.5).
 *
 * ```text
 * GET /v1/backups                     → metadata dos backups retidos da conta
 * GET /v1/backups/{id}/content        → o snapshot canônico, para arquivo privado
 * ```
 *
 * As duas são **read-only** no servidor: listar não marca nada, baixar não consome o backup, e
 * restaurar não o apaga. Repetir um download é seguro por construção.
 *
 * Interface por um motivo de teste honesto: os invariantes que mais importam — hash divergente,
 * download interrompido, conta trocada no meio — exigem controlar o que chega e quando. Uma
 * implementação de teste controla isso; um socket real, não. Há **uma** implementação de produção,
 * e ela é a única que fala HTTP.
 */
interface RestoreApi {

    /** `true` quando existe endereço de Spark Backend neste build. */
    val isConfigured: Boolean

    /** A metadata dos backups retidos da conta autenticada. Nunca o conteúdo. */
    suspend fun list(): RestoreListResult

    /**
     * Baixa o snapshot para [destination].
     *
     * O conteúdo vai direto para arquivo: ele existe para ser validado e aplicado, não para virar
     * uma `String` na memória do aparelho.
     */
    suspend fun download(backupId: String, destination: File): RestoreDownloadResult
}

/** O desfecho de uma listagem. */
sealed interface RestoreListResult {

    /** Os backups da conta, na ordem que o **servidor** definiu: do mais recente ao mais antigo. */
    data class Success(val items: List<BackupMetadataDto>) : RestoreListResult

    data object NotConfigured : RestoreListResult
    data object AuthRequired : RestoreListResult
    data object Network : RestoreListResult
    data object Unavailable : RestoreListResult

    /** O servidor recusou. [code] é vocabulário do Spark, nunca detalhe interno. */
    data class Rejected(val code: String?) : RestoreListResult
}

/** O desfecho de um download. */
sealed interface RestoreDownloadResult {

    /** O arquivo foi escrito por completo. [bytes] é o que realmente chegou. */
    data class Success(val bytes: Long) : RestoreDownloadResult

    data object NotConfigured : RestoreDownloadResult
    data object AuthRequired : RestoreDownloadResult
    data object Network : RestoreDownloadResult
    data object Unavailable : RestoreDownloadResult

    /** Não existe backup com aquele id **nesta conta** — que é a mesma resposta de "não existe". */
    data object NotFound : RestoreDownloadResult

    /** O servidor tem a metadata e não tem o documento (backup criado antes da T16.5). */
    data object ContentUnavailable : RestoreDownloadResult

    /** O corpo passou do teto do cliente. A escrita foi abortada e o arquivo, apagado. */
    data object TooLarge : RestoreDownloadResult

    data class Rejected(val code: String?) : RestoreDownloadResult
}

/**
 * A fronteira HTTP do restore.
 *
 * Reusa o `SparkBackendClient` da T16.1: um cliente, um interceptor, um lugar montando
 * `Authorization: Bearer`. Nenhum caminho novo de autenticação foi criado, e o token continua
 * sendo pedido ao Firebase na hora e usado na hora — nunca guardado, nunca registrado.
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * `AuthRequired` — **zero** rede. Isso não afeta treino, execução, histórico nem gamificação.
 */
class SparkRestoreApi(
    private val client: SparkBackendClient?
) : RestoreApi {

    private val json = Json { ignoreUnknownKeys = true }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun list(): RestoreListResult {
        val backend = client
        if (backend == null || !backend.isConfigured) return RestoreListResult.NotConfigured

        return when (val outcome = backend.getJson(RestoreContract.BACKUPS_PATH)) {
            SparkHttpOutcome.NotConfigured -> RestoreListResult.NotConfigured
            SparkHttpOutcome.SignedOut -> RestoreListResult.AuthRequired
            SparkHttpOutcome.NetworkFailure -> RestoreListResult.Network
            is SparkHttpOutcome.Response -> when {
                outcome.code == HTTP_OK -> decodeList(outcome.body)
                outcome.code == HTTP_UNAUTHORIZED -> RestoreListResult.AuthRequired
                outcome.code >= HTTP_SERVER_ERROR -> RestoreListResult.Unavailable
                else -> RestoreListResult.Rejected(errorCodeOf(outcome.body))
            }
        }
    }

    override suspend fun download(backupId: String, destination: File): RestoreDownloadResult {
        val backend = client
        if (backend == null || !backend.isConfigured) return RestoreDownloadResult.NotConfigured

        val outcome = backend.getToFile(
            path = RestoreContract.contentPath(backupId),
            destination = destination,
            maxBytes = RestoreLimits.MAX_SNAPSHOT_BYTES
        )
        return when (outcome) {
            is SparkDownloadOutcome.Downloaded -> RestoreDownloadResult.Success(outcome.bytes)
            SparkDownloadOutcome.NotConfigured -> RestoreDownloadResult.NotConfigured
            SparkDownloadOutcome.SignedOut -> RestoreDownloadResult.AuthRequired
            SparkDownloadOutcome.NetworkFailure -> RestoreDownloadResult.Network
            SparkDownloadOutcome.TooLarge -> RestoreDownloadResult.TooLarge
            is SparkDownloadOutcome.Rejected -> when {
                outcome.code == HTTP_UNAUTHORIZED -> RestoreDownloadResult.AuthRequired
                outcome.code == HTTP_NOT_FOUND -> RestoreDownloadResult.NotFound
                outcome.code == HTTP_GONE -> RestoreDownloadResult.ContentUnavailable
                outcome.code >= HTTP_SERVER_ERROR -> RestoreDownloadResult.Unavailable
                else -> RestoreDownloadResult.Rejected(errorCodeOf(outcome.body))
            }
        }
    }

    private fun decodeList(body: String): RestoreListResult = try {
        RestoreListResult.Success(json.decodeFromString(BackupListDto.serializer(), body).items)
    } catch (e: SerializationException) {
        // Resposta que o app não sabe usar não vira "não há backups": isso esconderia backups
        // reais e convidaria o usuário a criar um dataset novo por cima.
        RestoreListResult.Rejected("RESPOSTA_ILEGIVEL")
    } catch (e: IllegalArgumentException) {
        RestoreListResult.Rejected("RESPOSTA_ILEGIVEL")
    }

    /** Só o `code` do envelope de erro. Mensagem de servidor não é texto de UI. */
    private fun errorCodeOf(body: String): String? = try {
        (json.parseToJsonElement(body) as? JsonObject)
            ?.get("error")
            ?.let { it as? JsonObject }
            ?.get("code")
            ?.let { it as? JsonPrimitive }
            ?.content
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * A lista de backups que o servidor devolve.
 *
 * A ordem é do servidor e é preservada: o app **não** reordena por `createdAt`. Quem decide qual
 * backup é o mais recente é a sequência do servidor — relógio de aparelho diverge, e um celular
 * adiantado colocaria uma cópia velha no topo para sempre.
 */
@Serializable
data class BackupListDto(val items: List<BackupMetadataDto> = emptyList())
