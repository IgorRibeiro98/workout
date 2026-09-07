package com.example.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O contrato de sincronização incremental entre o Spark Android e o Spark Backend (T16.6).
 *
 * O espelho TypeScript é `backend/src/modules/sync/sync.contract.ts`. A descrição legível do
 * protocolo — revision, cursor, idempotência, conflito — vive em
 * `docs/architecture/sync-protocol.md`.
 *
 * ## Sync não é backup
 *
 * ```text
 * T16.4   Android ──snapshot completo──▶ Spark Backend     backup, imutável, autocontido
 * T16.5   Android ◀──snapshot completo── Spark Backend     restore, substituição explícita
 * T16.6   Android ⇄ mudanças ⇄ Spark Backend               sync incremental
 * ```
 *
 * Os três coexistem e nenhum substitui o outro.
 */
object SyncProtocol {

    /** Versão do protocolo. Não é `/v1`, não é o Room, não é `entitySchemaVersion`. */
    const val VERSION: Int = 1

    const val PUSH_PATH: String = "v1/sync/push"
    const val PULL_PATH: String = "v1/sync/pull"

    /**
     * Mutações por requisição de push.
     *
     * Nem uma requisição por campo editado, nem um lote ilimitado: a Outbox é fatiada em lotes
     * deste tamanho e enviada em ordem de `id`. O servidor tem o mesmo teto (`sync.limits.ts`) —
     * um cliente que ignorasse este número receberia `SYNC_PAYLOAD_TOO_LARGE`.
     */
    const val PUSH_BATCH_SIZE: Int = 50

    /** Mudanças por página de pull. O servidor tem o teto; este é o pedido. */
    const val PULL_PAGE_SIZE: Int = 100

    /**
     * Quantas páginas um único ciclo percorre antes de parar.
     *
     * Um teto existe para que o ciclo termine mesmo se o servidor produzir mudanças mais rápido do
     * que o aparelho as aplica. O que sobra fica para o ciclo seguinte — o cursor é durável.
     */
    const val MAX_PULL_PAGES_PER_CYCLE: Int = 50

    /** As versões de payload que este app sabe aplicar, por agregado. */
    const val SUPPORTED_ENTITY_SCHEMA_VERSION: Int = 1
}

/**
 * O desfecho de **uma** mutação, como o servidor o reporta.
 *
 * Um valor desconhecido — de um servidor mais novo — vira [UNKNOWN], e uma entrada com esse
 * desfecho **não** é confirmada: o app prefere reenviar do que apagar uma alteração local achando
 * que ela subiu.
 */
enum class SyncMutationStatus {
    APPLIED,
    ALREADY_APPLIED,
    STALE,
    INVALID,
    UNSUPPORTED,
    IMMUTABLE_HISTORY_CONFLICT,
    IDEMPOTENCY_CONFLICT,
    UNKNOWN;

    companion object {
        fun from(raw: String?): SyncMutationStatus =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

@Serializable
data class SyncPushMutationDto(
    val clientMutationId: String,
    val entityType: String,
    val entitySyncId: String,
    val entitySchemaVersion: Int,
    val operation: String,
    /** A revision remota conhecida por este aparelho. `null` = criação. */
    val baseRevision: Long? = null,
    val payload: JsonElement
)

/**
 * O corpo de `POST /v1/sync/push`.
 *
 * **Não existe `ownerUid` aqui, e isso é o contrato.** O dono sai do Firebase ID Token verificado
 * pelo servidor; um campo de dono no corpo seria ignorado — e não existir é melhor do que existir
 * e ser ignorado, porque nenhuma versão futura do servidor pode "aproveitar" um campo que nunca
 * esteve lá. O envelope do servidor é estrito: um campo a mais recusa a requisição.
 *
 * O `deviceId` é metadado de diagnóstico. Ele não autoriza nada.
 */
@Serializable
data class SyncPushRequestDto(
    val deviceId: String,
    val mutations: List<SyncPushMutationDto>
)

@Serializable
data class SyncMutationResultDto(
    val clientMutationId: String,
    val status: String,
    val serverRevision: Long? = null,
    val serverSequence: Long? = null,
    /** A revision atual do servidor, quando o desfecho é `STALE`. */
    val currentRevision: Long? = null,
    val reason: String? = null
)

@Serializable
data class SyncPushResponseDto(
    val results: List<SyncMutationResultDto> = emptyList()
)

@Serializable
data class SyncChangeDto(
    val serverSequence: Long,
    val entityType: String,
    val entitySyncId: String,
    val entitySchemaVersion: Int,
    val serverRevision: Long,
    val operation: String,
    val payloadHash: String,
    @SerialName("originDeviceId") val originDeviceId: String = "",
    val createdAt: Long = 0,
    val payload: JsonElement
)

@Serializable
data class SyncPullResponseDto(
    val changes: List<SyncChangeDto> = emptyList(),
    val nextCursor: Long = 0,
    val hasMore: Boolean = false
)
