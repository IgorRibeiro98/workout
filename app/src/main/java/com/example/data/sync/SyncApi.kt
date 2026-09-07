package com.example.data.sync

import com.example.data.remote.spark.SparkBackendClient
import com.example.data.remote.spark.SparkHttpOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A fronteira do sync com o servidor (T16.6).
 *
 * Existe como interface pelo mesmo motivo de teste honesto que o backup: os invariantes que mais
 * importam — resposta perdida, reenvio idempotente, escrita stale, falha no meio de uma página de
 * pull — só podem ser exercitados com o servidor **bloqueado ou controlado** no instante certo.
 * Uma implementação de teste controla esse instante; um socket real, não.
 *
 * Não é indireção "por via das dúvidas": há uma implementação de produção, e ela é a única que
 * fala HTTP.
 */
interface SyncApi {

    /** `true` quando existe endereço de Spark Backend neste build. */
    val isConfigured: Boolean

    /** Envia um lote de mutações. O corpo é o texto canônico, byte a byte. */
    suspend fun push(canonicalBody: String): SyncPushOutcome

    /** Lê uma página do change log da conta depois de [cursor]. */
    suspend fun pull(cursor: Long, limit: Int): SyncPullOutcome
}

/**
 * A fronteira HTTP do sync.
 *
 * ```text
 * SyncRepository → aqui → POST /v1/sync/push  (Bearer <Firebase ID Token>) → Spark Backend
 *                       → GET  /v1/sync/pull  (Bearer <Firebase ID Token>) → Spark Backend
 * ```
 *
 * Reusa o `SparkBackendClient` da T16.1: um cliente, um interceptor, um lugar montando
 * `Authorization: Bearer`. Nenhum caminho novo de autenticação foi criado, e o token continua
 * sendo pedido ao Firebase na hora e usado na hora — nunca guardado, nunca registrado.
 *
 * ## Sem conta, sem requisição
 *
 * Sem sessão do Firebase o interceptor não deixa a requisição sair, e o resultado é
 * [SyncPushOutcome.AuthRequired] — **zero** rede. Isso não afeta treino, execução, histórico,
 * templates nem gamificação.
 *
 * ## Sem retry aqui
 *
 * Esta camada faz uma requisição e devolve o que aconteceu. Quem decide se vale tentar de novo é o
 * coordenador — e só para erro transitório, com espaçamento. Um retry escondido no transporte
 * multiplicaria requisições sem ninguém ver.
 */
class SparkSyncApi(
    private val client: SparkBackendClient?
) : SyncApi {

    private val json = Json { ignoreUnknownKeys = true }

    override val isConfigured: Boolean get() = client?.isConfigured == true

    override suspend fun push(canonicalBody: String): SyncPushOutcome {
        val backend = client
        if (backend == null || !backend.isConfigured) return SyncPushOutcome.NotConfigured

        return when (val outcome = backend.postJson(SyncProtocol.PUSH_PATH, canonicalBody)) {
            SparkHttpOutcome.NotConfigured -> SyncPushOutcome.NotConfigured
            SparkHttpOutcome.SignedOut -> SyncPushOutcome.AuthRequired
            SparkHttpOutcome.NetworkFailure -> SyncPushOutcome.Network
            is SparkHttpOutcome.Response -> when {
                outcome.code == HTTP_OK -> decodePush(outcome.body)
                outcome.code == HTTP_UNAUTHORIZED -> SyncPushOutcome.AuthRequired
                outcome.code == HTTP_TOO_MANY -> SyncPushOutcome.RateLimited
                outcome.code in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR ->
                    SyncPushOutcome.Rejected(errorCodeOf(outcome.body))
                else -> SyncPushOutcome.Unavailable
            }
        }
    }

    override suspend fun pull(cursor: Long, limit: Int): SyncPullOutcome {
        val backend = client
        if (backend == null || !backend.isConfigured) return SyncPullOutcome.NotConfigured

        val path = "${SyncProtocol.PULL_PATH}?cursor=$cursor&limit=$limit"
        return when (val outcome = backend.getJson(path)) {
            SparkHttpOutcome.NotConfigured -> SyncPullOutcome.NotConfigured
            SparkHttpOutcome.SignedOut -> SyncPullOutcome.AuthRequired
            SparkHttpOutcome.NetworkFailure -> SyncPullOutcome.Network
            is SparkHttpOutcome.Response -> when {
                outcome.code == HTTP_OK -> decodePull(outcome.body)
                outcome.code == HTTP_UNAUTHORIZED -> SyncPullOutcome.AuthRequired
                outcome.code == HTTP_TOO_MANY -> SyncPullOutcome.RateLimited
                outcome.code in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR ->
                    SyncPullOutcome.Rejected(errorCodeOf(outcome.body))
                else -> SyncPullOutcome.Unavailable
            }
        }
    }

    private fun decodePush(body: String): SyncPushOutcome = try {
        SyncPushOutcome.Success(json.decodeFromString(SyncPushResponseDto.serializer(), body))
    } catch (e: SerializationException) {
        // Resposta que o app não sabe ler **não** vira "deu certo": as entradas continuam
        // pendentes e o reenvio é seguro pelo `clientMutationId`.
        SyncPushOutcome.Rejected(UNREADABLE_RESPONSE)
    } catch (e: IllegalArgumentException) {
        SyncPushOutcome.Rejected(UNREADABLE_RESPONSE)
    }

    private fun decodePull(body: String): SyncPullOutcome = try {
        SyncPullOutcome.Success(json.decodeFromString(SyncPullResponseDto.serializer(), body))
    } catch (e: SerializationException) {
        // Idem: o cursor não avança, e a próxima tentativa pede a mesma página.
        SyncPullOutcome.Rejected(UNREADABLE_RESPONSE)
    } catch (e: IllegalArgumentException) {
        SyncPullOutcome.Rejected(UNREADABLE_RESPONSE)
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
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
        const val UNREADABLE_RESPONSE = "RESPOSTA_ILEGIVEL"
    }
}

/**
 * O desfecho de um push.
 *
 * Modelado para que o app nunca confunda "o servidor não respondeu" com "você não está logado" —
 * a mesma regra do backup. Indisponibilidade é recuperável, mantém a Outbox intacta e não apaga
 * identidade, vínculo nem dado local.
 */
sealed interface SyncPushOutcome {

    /** O servidor respondeu. Cada mutação tem o desfecho dela dentro de [response]. */
    data class Success(val response: SyncPushResponseDto) : SyncPushOutcome

    data object NotConfigured : SyncPushOutcome
    data object AuthRequired : SyncPushOutcome
    data object Network : SyncPushOutcome
    data object Unavailable : SyncPushOutcome

    /** O servidor pediu para diminuir o ritmo. Nada foi aplicado; a Outbox continua intacta. */
    data object RateLimited : SyncPushOutcome

    /** O servidor recusou a requisição inteira. [code] é vocabulário do Spark. */
    data class Rejected(val code: String?) : SyncPushOutcome
}

/** O desfecho de um pull. Mesmas classes de falha do push. */
sealed interface SyncPullOutcome {

    data class Success(val response: SyncPullResponseDto) : SyncPullOutcome

    data object NotConfigured : SyncPullOutcome
    data object AuthRequired : SyncPullOutcome
    data object Network : SyncPullOutcome
    data object Unavailable : SyncPullOutcome
    data object RateLimited : SyncPullOutcome
    data class Rejected(val code: String?) : SyncPullOutcome
}
