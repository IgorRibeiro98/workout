package com.example.data.sync

import com.example.data.backup.BackupCanonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Um Spark Backend de teste, em memória, falando o **mesmo protocolo** do servidor real (T16.6).
 *
 * ## Por que ele existe
 *
 * Os invariantes que mais importam do lado do Android — resposta perdida, reenvio idempotente,
 * escrita stale, eco, convergência entre dois aparelhos — só podem ser exercitados com controle
 * fino sobre o que o servidor responde e **quando**. Um socket real não dá esse controle, e um
 * `mock` que devolve o que o teste mandar não provaria convergência nenhuma.
 *
 * Este dublê implementa as regras de decisão do servidor: ledger de idempotência por
 * `clientMutationId`, `serverRevision` por entidade, sequência global crescente, histórico
 * imutável e change log append-only. As mesmas regras estão testadas contra a implementação real
 * em `backend/test/sync-*.spec.ts` — aqui o que se prova é como o **cliente** reage a elas.
 *
 * Ele não é uma segunda autoridade: nada de produção o conhece, e ele vive só em `test/`.
 */
class FakeSparkSyncServer {

    private val entities = LinkedHashMap<EntityKey, StoredEntity>()
    private val changes = mutableListOf<StoredChange>()
    private val ledger = LinkedHashMap<Pair<String, String>, LedgerRow>()

    /** Quantas requisições de push chegaram. Serve para provar que um retry aconteceu. */
    var pushRequests: Int = 0
        private set

    /** Quantas mudanças o log tem, no total. Uma reaplicação idempotente não aumenta este número. */
    val changeCount: Int get() = changes.size

    fun revisionOf(ownerUid: String, entityType: SyncEntityType, entitySyncId: String): Long? =
        entities[EntityKey(ownerUid, entityType.name, entitySyncId)]?.revision

    fun payloadOf(ownerUid: String, entityType: SyncEntityType, entitySyncId: String): String? =
        entities[EntityKey(ownerUid, entityType.name, entitySyncId)]?.payloadText

    /** `true` quando a identidade tem tombstone no servidor de teste (T16.7). */
    fun isDeleted(ownerUid: String, entityType: SyncEntityType, entitySyncId: String): Boolean =
        entities[EntityKey(ownerUid, entityType.name, entitySyncId)]?.deleted == true

    fun push(
        ownerUid: String,
        deviceId: String,
        mutations: List<SyncPushMutationDto>
    ): SyncPushResponseDto {
        pushRequests++
        return SyncPushResponseDto(mutations.map { applyOne(ownerUid, deviceId, it) })
    }

    /**
     * O estado **atual** de um agregado daquela conta (T16.7.1) — o `GET /v1/sync/entities/...`.
     *
     * Somente leitura, como no servidor real: nenhuma revision é gasta, nenhuma mudança é anexada
     * ao log e nenhum ledger é escrito. `null` significa "esta conta não tem esta identidade" — a
     * mesma resposta que o servidor dá para uma identidade de outra conta.
     */
    fun entityState(
        ownerUid: String,
        entityType: SyncEntityType,
        entitySyncId: String
    ): SyncEntityStateDto? {
        val stored = entities[EntityKey(ownerUid, entityType.name, entitySyncId)] ?: return null
        return SyncEntityStateDto(
            // Derivado do "token" desta requisição, como no servidor real.
            ownerUid = ownerUid,
            entityType = entityType.name,
            entitySyncId = entitySyncId,
            entitySchemaVersion = 1,
            serverRevision = stored.revision,
            deleted = stored.deleted,
            payloadHash = if (stored.deleted) null else stored.hash,
            payload = if (stored.deleted) {
                null
            } else {
                stored.payloadText?.let { Json.parseToJsonElement(it) }
            }
        )
    }

    fun pull(ownerUid: String, cursor: Long, limit: Int): SyncPullResponseDto {
        val page = changes
            .filter { it.ownerUid == ownerUid && it.serverSequence > cursor }
            .sortedBy { it.serverSequence }
        val slice = page.take(limit)
        return SyncPullResponseDto(
            changes = slice.map { it.toDto() },
            nextCursor = slice.lastOrNull()?.serverSequence ?: cursor,
            hasMore = page.size > slice.size
        )
    }

    private fun applyOne(
        ownerUid: String,
        deviceId: String,
        mutation: SyncPushMutationDto
    ): SyncMutationResultDto {
        // Exclusão não carrega conteúdo, e o hash dela é vazio — igual ao servidor real.
        val hash = mutation.payload
            ?.let { BackupCanonicalJson.canonicalHash(it).hash }
            .orEmpty()

        // 1. Idempotência primeiro: um reenvio devolve o resultado original.
        ledger[ownerUid to mutation.clientMutationId]?.let { row ->
            val sameIntent = row.entityType == mutation.entityType &&
                row.entitySyncId == mutation.entitySyncId &&
                row.operation == mutation.operation &&
                row.payloadHash == hash
            return if (sameIntent) {
                SyncMutationResultDto(
                    clientMutationId = mutation.clientMutationId,
                    status = SyncMutationStatus.ALREADY_APPLIED.name,
                    serverRevision = row.revision,
                    serverSequence = row.sequence
                )
            } else {
                SyncMutationResultDto(
                    clientMutationId = mutation.clientMutationId,
                    status = SyncMutationStatus.IDEMPOTENCY_CONFLICT.name
                )
            }
        }

        val type = SyncEntityType.entries.firstOrNull { it.name == mutation.entityType }
            ?: return rejected(mutation, SyncMutationStatus.UNSUPPORTED, "UNKNOWN_ENTITY_TYPE")
        if (mutation.entitySchemaVersion != 1) {
            return rejected(
                mutation,
                SyncMutationStatus.UNSUPPORTED,
                "UNSUPPORTED_ENTITY_SCHEMA_VERSION"
            )
        }

        val key = EntityKey(ownerUid, type.name, mutation.entitySyncId)
        val existing = entities[key]

        // 2. Exclusão: tombstone versionado, com as mesmas regras de base do servidor real.
        if (mutation.operation == SyncOperation.DELETE.name) {
            if (!SyncEntityPolicies.isDeleteAllowed(type)) {
                return rejected(mutation, SyncMutationStatus.UNSUPPORTED, "DELETE_NOT_ALLOWED")
            }
            if (existing != null && existing.deleted) {
                return converged(ownerUid, deviceId, mutation, hash, existing)
            }
            val base = mutation.baseRevision ?: 0L
            if (existing == null) {
                return commit(ownerUid, deviceId, mutation, type, hash, 1, deleted = true)
            }
            if (base > existing.revision) {
                return SyncMutationResultDto(
                    clientMutationId = mutation.clientMutationId,
                    status = SyncMutationStatus.INVALID.name,
                    currentRevision = existing.revision,
                    reason = "BASE_REVISION_AHEAD"
                )
            }
            if (base != existing.revision) {
                return SyncMutationResultDto(
                    clientMutationId = mutation.clientMutationId,
                    status = SyncMutationStatus.STALE.name,
                    currentRevision = existing.revision
                )
            }
            return commit(
                ownerUid, deviceId, mutation, type, hash, existing.revision + 1, deleted = true
            )
        }

        // 3. Tombstone: um `UPSERT` contra ele nunca recria a entidade.
        if (existing != null && existing.deleted) {
            return SyncMutationResultDto(
                clientMutationId = mutation.clientMutationId,
                status = SyncMutationStatus.REMOTE_DELETED.name,
                currentRevision = existing.revision,
                reason = "ENTITY_DELETED"
            )
        }

        // 4. Histórico concluído é imutável.
        if (type == SyncEntityType.WORKOUT_SESSION) {
            return when {
                existing == null -> commit(ownerUid, deviceId, mutation, type, hash, 1)
                existing.hash == hash -> converged(ownerUid, deviceId, mutation, hash, existing)
                else -> SyncMutationResultDto(
                    clientMutationId = mutation.clientMutationId,
                    status = SyncMutationStatus.IMMUTABLE_HISTORY_CONFLICT.name,
                    currentRevision = existing.revision,
                    reason = "IMMUTABLE_HISTORY"
                )
            }
        }

        val base = mutation.baseRevision ?: 0L

        if (existing == null) {
            return if (base == 0L) {
                commit(ownerUid, deviceId, mutation, type, hash, 1)
            } else {
                SyncMutationResultDto(
                    clientMutationId = mutation.clientMutationId,
                    status = SyncMutationStatus.STALE.name,
                    currentRevision = 0
                )
            }
        }

        if (base > existing.revision) {
            return SyncMutationResultDto(
                clientMutationId = mutation.clientMutationId,
                status = SyncMutationStatus.INVALID.name,
                currentRevision = existing.revision,
                reason = "BASE_REVISION_AHEAD"
            )
        }
        if (hash == existing.hash) return converged(ownerUid, deviceId, mutation, hash, existing)
        if (base == existing.revision && base > 0) {
            return commit(ownerUid, deviceId, mutation, type, hash, existing.revision + 1)
        }

        return SyncMutationResultDto(
            clientMutationId = mutation.clientMutationId,
            status = SyncMutationStatus.STALE.name,
            currentRevision = existing.revision
        )
    }

    private fun commit(
        ownerUid: String,
        deviceId: String,
        mutation: SyncPushMutationDto,
        type: SyncEntityType,
        hash: String,
        revision: Long,
        deleted: Boolean = false
    ): SyncMutationResultDto {
        val sequence = (changes.lastOrNull()?.serverSequence ?: 0L) + 1
        val canonical = mutation.payload?.let { BackupCanonicalJson.canonicalize(it) }

        changes += StoredChange(
            serverSequence = sequence,
            ownerUid = ownerUid,
            entityType = type.name,
            entitySyncId = mutation.entitySyncId,
            entitySchemaVersion = mutation.entitySchemaVersion,
            revision = revision,
            payloadText = canonical,
            hash = hash,
            originDeviceId = deviceId,
            operation = if (deleted) SyncOperation.DELETE else SyncOperation.UPSERT
        )
        entities[EntityKey(ownerUid, type.name, mutation.entitySyncId)] = StoredEntity(
            revision = revision,
            lastSequence = sequence,
            payloadText = canonical,
            hash = hash,
            deleted = deleted
        )
        ledger[ownerUid to mutation.clientMutationId] = LedgerRow(
            entityType = type.name,
            entitySyncId = mutation.entitySyncId,
            operation = mutation.operation,
            payloadHash = hash,
            revision = revision,
            sequence = sequence
        )
        return SyncMutationResultDto(
            clientMutationId = mutation.clientMutationId,
            status = SyncMutationStatus.APPLIED.name,
            serverRevision = revision,
            serverSequence = sequence
        )
    }

    private fun converged(
        ownerUid: String,
        deviceId: String,
        mutation: SyncPushMutationDto,
        hash: String,
        existing: StoredEntity
    ): SyncMutationResultDto {
        ledger[ownerUid to mutation.clientMutationId] = LedgerRow(
            entityType = mutation.entityType,
            entitySyncId = mutation.entitySyncId,
            operation = mutation.operation,
            payloadHash = hash,
            revision = existing.revision,
            sequence = existing.lastSequence
        )
        return SyncMutationResultDto(
            clientMutationId = mutation.clientMutationId,
            status = SyncMutationStatus.ALREADY_APPLIED.name,
            serverRevision = existing.revision,
            serverSequence = existing.lastSequence
        )
    }

    private fun rejected(
        mutation: SyncPushMutationDto,
        status: SyncMutationStatus,
        reason: String
    ) = SyncMutationResultDto(
        clientMutationId = mutation.clientMutationId,
        status = status.name,
        reason = reason
    )

    private data class EntityKey(val ownerUid: String, val entityType: String, val entitySyncId: String)

    private data class StoredEntity(
        val revision: Long,
        val lastSequence: Long,
        val payloadText: String?,
        val hash: String,
        val deleted: Boolean = false
    )

    private data class LedgerRow(
        val entityType: String,
        val entitySyncId: String,
        val operation: String,
        val payloadHash: String,
        val revision: Long,
        val sequence: Long
    )

    private data class StoredChange(
        val serverSequence: Long,
        val ownerUid: String,
        val entityType: String,
        val entitySyncId: String,
        val entitySchemaVersion: Int,
        val revision: Long,
        val payloadText: String?,
        val hash: String,
        val originDeviceId: String,
        val operation: SyncOperation = SyncOperation.UPSERT
    ) {
        fun toDto(): SyncChangeDto = SyncChangeDto(
            serverSequence = serverSequence,
            entityType = entityType,
            entitySyncId = entitySyncId,
            entitySchemaVersion = entitySchemaVersion,
            serverRevision = revision,
            operation = operation.name,
            payloadHash = hash,
            originDeviceId = originDeviceId,
            createdAt = serverSequence,
            payload = payloadText?.let { Json.parseToJsonElement(it) }
        )
    }
}

/**
 * A fronteira HTTP substituída por chamadas diretas ao [FakeSparkSyncServer].
 *
 * Ela recebe o **texto canônico** que a produção enviaria e o parseia — o mesmo caminho de bytes,
 * sem socket. É isso que permite ao teste provar que o hash calculado no cliente é o mesmo que o
 * servidor calcula.
 */
class FakeSyncApi(
    private val server: FakeSparkSyncServer,
    private val ownerUid: String,
    private val deviceId: String
) : SyncApi {

    override var isConfigured: Boolean = true

    /** Quando ligado, toda chamada falha como se não houvesse rede. Nada chega ao servidor. */
    var offline: Boolean = false

    /**
     * A conta com que esta fronteira autentica (T16.7.1).
     *
     * Trocá-la reproduz o que o interceptor faria depois de um login diferente: o servidor passa a
     * responder como **outra** conta. É assim que o teste de troca de conta durante o voo é
     * montado sem Firebase.
     */
    var authenticatedUid: String = ownerUid

    /** Chamado logo depois de a resposta de estado atual ser produzida, antes de devolvê-la. */
    var onEntityStateResponse: (() -> Unit)? = null

    /** Quantas leituras de estado atual chegaram. Prova que um toque duplo produz **uma**. */
    var entityStateCalls: Int = 0
        private set

    /** Quando ligado, o servidor aplica e a **resposta se perde** — o cenário do §40. */
    var dropNextPushResponse: Boolean = false

    /** Força uma resposta 5xx no próximo push. */
    var unavailableOnNextPush: Boolean = false

    /** Faz o apply da próxima página falhar do lado do cliente, para provar o cursor. */
    var corruptNextPullPage: Boolean = false

    var pushCalls: Int = 0
        private set
    var pullCalls: Int = 0
        private set

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun push(canonicalBody: String): SyncPushOutcome {
        pushCalls++
        if (offline) return SyncPushOutcome.Network
        if (unavailableOnNextPush) {
            unavailableOnNextPush = false
            return SyncPushOutcome.Unavailable
        }

        val request = json.decodeFromString(SyncPushRequestDto.serializer(), canonicalBody)
        val response = server.push(ownerUid, request.deviceId, request.mutations)

        if (dropNextPushResponse) {
            dropNextPushResponse = false
            // O servidor aplicou; o cliente não fica sabendo. É exatamente o caso que o
            // `clientMutationId` existe para resolver.
            return SyncPushOutcome.Network
        }
        return SyncPushOutcome.Success(response)
    }

    override suspend fun pull(cursor: Long, limit: Int): SyncPullOutcome {
        pullCalls++
        if (offline) return SyncPullOutcome.Network

        val page = server.pull(ownerUid, cursor, limit)
        if (corruptNextPullPage && page.changes.isNotEmpty()) {
            corruptNextPullPage = false
            // Uma mudança que o app não sabe ler: `entitySchemaVersion` do futuro.
            return SyncPullOutcome.Success(
                page.copy(changes = page.changes.map { it.copy(entitySchemaVersion = 99) })
            )
        }
        return SyncPullOutcome.Success(page)
    }

    override suspend fun entityState(
        entityType: SyncEntityType,
        entitySyncId: String
    ): SyncEntityStateOutcome {
        entityStateCalls++
        if (offline) return SyncEntityStateOutcome.Network

        // A conta é a que **esta requisição** autenticou — não a dona do dataset local. É essa
        // distinção que o teste de troca de conta precisa exercitar.
        val state = server.entityState(authenticatedUid, entityType, entitySyncId)
        // O gancho roda depois de o servidor responder e antes de o app ver a resposta: é a janela
        // exata em que a sessão pode mudar.
        onEntityStateResponse?.invoke()
        return state?.let { SyncEntityStateOutcome.Success(it) } ?: SyncEntityStateOutcome.NotFound
    }

    /** O `deviceId` desta instalação, para os testes que precisam distinguir origem. */
    fun deviceId(): String = deviceId
}

/** Lê um campo de texto do payload canônico guardado no servidor de teste. */
fun payloadField(payloadText: String?, field: String): String? =
    payloadText
        ?.let { Json.parseToJsonElement(it) as? JsonObject }
        ?.get(field)
        ?.let { it as? JsonPrimitive }
        ?.content

/** Lê a ordem dos exercícios de um payload de treino, para provar convergência de reordenação. */
fun templateExerciseOrder(payload: JsonElement): List<String> =
    payload.jsonObject["exercises"]!!.jsonArray.map {
        it.jsonObject["exercise"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }
