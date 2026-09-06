package com.example.data.backup

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A fronteira do backup com o servidor.
 *
 * Existe como interface por um motivo de teste honesto: os invariantes que mais importam na T16.4 —
 * resposta perdida, retry com os mesmos bytes, mutação durante o upload, Outbox só liberada depois
 * da confirmação — só podem ser exercitados com o servidor **bloqueado no meio da chamada**. Uma
 * implementação de teste controla esse instante; um socket real, não.
 *
 * Não é uma camada de indireção "por via das dúvidas": há uma implementação de produção, e ela é a
 * única que fala HTTP.
 */
interface BackupApi {

    /** `true` quando existe endereço de Spark Backend neste build. */
    val isConfigured: Boolean

    /** Envia o snapshot. O corpo é o texto canônico da tentativa, byte a byte. */
    suspend fun upload(canonicalBody: String): BackupUploadResult

    /** A metadata do backup mais recente da conta, quando existe. */
    suspend fun latest(): BackupUploadResult
}

/**
 * A fronteira HTTP do backup (T16.4).
 *
 * ```text
 * BackupRepository → aqui → POST /v1/backups (Bearer <Firebase ID Token>) → Spark Backend
 * ```
 *
 * Reusa o `SparkBackendClient` da T16.1: um cliente, um interceptor, um lugar montando
 * `Authorization: Bearer`. Nenhum caminho novo de autenticação foi criado, e o token continua
 * sendo pedido ao Firebase na hora e usado na hora — nunca guardado, nunca registrado.
 *
 * ## Sem conta, sem requisição
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * [BackupUploadResult.AuthRequired] — **zero** rede. Isso não afeta treino, execução, histórico,
 * templates nem gamificação.
 *
 * ## Sem retry automático
 *
 * Uma ação explícita do usuário produz no máximo uma requisição. Repetição é decisão dele, no
 * botão — e o `clientBackupId` durável é o que torna essa repetição segura.
 */
class SparkBackupApi(
    private val client: SparkBackendClient?
) : BackupApi {

    private val json = Json { ignoreUnknownKeys = true }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    /**
     * Envia o snapshot. [canonicalBody] é o texto canônico da tentativa, byte a byte.
     *
     * Nada é reserializado aqui: o corpo é exatamente o que foi hasheado e guardado na tentativa.
     * Reserializar abriria a possibilidade de dois reenvios da mesma tentativa produzirem bytes
     * diferentes — que o servidor leria, corretamente, como conflito de idempotência.
     */
    override suspend fun upload(canonicalBody: String): BackupUploadResult {
        val backend = client
        if (backend == null || !backend.isConfigured) return BackupUploadResult.NotConfigured

        return when (val outcome = backend.postJson(BackupContract.BACKUPS_PATH, canonicalBody)) {
            SparkHttpOutcome.NotConfigured -> BackupUploadResult.NotConfigured
            SparkHttpOutcome.SignedOut -> BackupUploadResult.AuthRequired
            SparkHttpOutcome.NetworkFailure -> BackupUploadResult.Network
            is SparkHttpOutcome.Response -> interpret(outcome)
        }
    }

    /** A metadata do backup mais recente da conta, quando existe. Nunca o conteúdo. */
    override suspend fun latest(): BackupUploadResult {
        val backend = client
        if (backend == null || !backend.isConfigured) return BackupUploadResult.NotConfigured

        return when (val outcome = backend.getJson(BackupContract.LATEST_BACKUP_PATH)) {
            SparkHttpOutcome.NotConfigured -> BackupUploadResult.NotConfigured
            SparkHttpOutcome.SignedOut -> BackupUploadResult.AuthRequired
            SparkHttpOutcome.NetworkFailure -> BackupUploadResult.Network
            is SparkHttpOutcome.Response -> interpret(outcome)
        }
    }

    private fun interpret(response: SparkHttpOutcome.Response): BackupUploadResult = when {
        // 201 = criado agora; 200 = a mesma tentativa reconhecida. Os dois são sucesso, e para o
        // app são a mesma coisa: o servidor tem este backup.
        response.code == HTTP_OK || response.code == HTTP_CREATED -> decode(response.body)

        response.code == HTTP_UNAUTHORIZED -> BackupUploadResult.AuthRequired
        response.code == HTTP_NOT_FOUND -> BackupUploadResult.NotFound
        response.code == HTTP_CONFLICT -> BackupUploadResult.Conflict(errorCodeOf(response.body))
        response.code == HTTP_TOO_LARGE -> BackupUploadResult.Rejected(errorCodeOf(response.body))
        response.code in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR ->
            BackupUploadResult.Rejected(errorCodeOf(response.body))

        else -> BackupUploadResult.Unavailable
    }

    private fun decode(body: String): BackupUploadResult = try {
        BackupUploadResult.Success(json.decodeFromString(BackupMetadataDto.serializer(), body))
    } catch (e: SerializationException) {
        // Resposta que o app não sabe usar não vira "deu certo": o backup fica pendente e o
        // usuário pode tentar de novo com a mesma tentativa.
        BackupUploadResult.Rejected("RESPOSTA_ILEGIVEL")
    } catch (e: IllegalArgumentException) {
        BackupUploadResult.Rejected("RESPOSTA_ILEGIVEL")
    }

    /** Só o `code` do envelope de erro. Mensagem de servidor não é texto de UI. */
    private fun errorCodeOf(body: String): String? = try {
        json.parseToJsonElement(body)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("error")
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("code")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_TOO_LARGE = 413
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * O desfecho de uma chamada de backup.
 *
 * Modelado para que o app nunca confunda "o servidor não respondeu" com "você não está logado" —
 * a mesma regra do `SparkBackendResult` da T16.1. Indisponibilidade é recuperável e não apaga
 * identidade, não apaga vínculo e não apaga dado local.
 */
sealed interface BackupUploadResult {

    /** O servidor tem este backup — criado agora ou reconhecido de um reenvio. */
    data class Success(val metadata: BackupMetadataDto) : BackupUploadResult

    /** Não há endereço de backend neste build. Nenhuma requisição foi feita. */
    data object NotConfigured : BackupUploadResult

    /** Sem conta conectada, ou o servidor recusou o token. */
    data object AuthRequired : BackupUploadResult

    /** Não há backup para esta conta — resposta normal de `latest`, não erro. */
    data object NotFound : BackupUploadResult

    /** Sem rede ou servidor inalcançável. Recuperável, e o núcleo do Spark não muda. */
    data object Network : BackupUploadResult

    /** O servidor respondeu 5xx. Recuperável. */
    data object Unavailable : BackupUploadResult

    /**
     * Mesma tentativa, conteúdo diferente.
     *
     * Só acontece se o payload guardado tiver mudado — o que a tentativa imutável impede. Se
     * aparecer, é defeito, e o app precisa criar uma tentativa nova em vez de insistir.
     */
    data class Conflict(val code: String?) : BackupUploadResult

    /** O servidor recusou o snapshot. [code] é vocabulário do Spark, nunca detalhe interno. */
    data class Rejected(val code: String?) : BackupUploadResult
}
