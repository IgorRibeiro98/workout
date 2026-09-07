package com.example.data.sync

import com.example.data.backup.BackupCanonicalJson
import kotlinx.serialization.json.JsonElement

/**
 * Transforma a Outbox em lotes de push (T16.6).
 *
 * ```text
 * sync_outbox (PENDING, em ordem de id)
 *      ↓  coalescência por agregado
 *      ↓  payload montado a partir do Room, agora
 *      ↓  baseRevision = a última revision remota conhecida
 * lotes de no máximo SyncProtocol.PUSH_BATCH_SIZE
 * ```
 *
 * ## Coalescência no envio
 *
 * A T16.3 já coalescia no **registro**: três edições seguidas do mesmo treino, antes de qualquer
 * envio, viram uma entrada. O que sobra para cá são os casos que aquela regra não cobre — uma
 * entrada registrada, depois outra do mesmo agregado com operação diferente no meio, ou entradas
 * que ficaram para trás enquanto a rede não voltava.
 *
 * A regra aqui é a mesma e pelo mesmo motivo: **o payload é montado do Room no momento do envio**,
 * então todas as `UPSERT` pendentes do mesmo agregado resolveriam para exatamente o mesmo
 * conteúdo. Enviar uma e confirmar todas é correto, e é o que evita gastar uma `revision` por
 * edição intermediária que o usuário já abandonou.
 *
 * O que a coalescência **não** faz:
 *
 * - **não atravessa `DELETE`.** Um agregado com exclusão pendente não envia nada nesta versão —
 *   ver abaixo;
 * - **não reaproveita `clientMutationId`.** A entrada efetivamente despachada leva o id dela; as
 *   outras nunca foram enviadas, então nenhuma idempotência é quebrada;
 * - **não junta agregados diferentes.** Cada um tem revision própria.
 *
 * ## `DELETE` não sai daqui
 *
 * A T16.6 não propaga exclusão: sem tombstone, apagar em outro aparelho é uma decisão que a T16.7
 * precisa tomar junto com retenção e prevenção de ressurreição. Então uma entrada `DELETE`
 * permanece `PENDING`, intocada, e **bloqueia o agregado dela** — porque enviar a `UPSERT`
 * anterior a ela ressuscitaria no servidor o que o usuário apagou aqui.
 *
 * Convertê-la silenciosamente em `UPSERT` seria pior de todas as formas possíveis.
 */
class SyncPushBuilder(
    private val outboxDao: SyncOutboxDao,
    private val metadataDao: EntitySyncMetadataDao,
    private val snapshotBuilder: SyncAggregateSnapshotBuilder
) {

    /**
     * O que há para enviar agora, já coalescido e com payload montado.
     *
     * Devolve **tudo** que é enviável; quem fatia em lotes é o repositório, que também precisa
     * parar no meio se a rede cair.
     */
    suspend fun prepare(ownerUid: String): PreparedPush {
        val pending = outboxDao.pendingFor(ownerUid)
        if (pending.isEmpty()) return PreparedPush()

        val grouped = LinkedHashMap<AggregateKey, MutableList<SyncOutboxEntryEntity>>()
        pending.forEach { entry ->
            val type = SyncEntityType.entries.firstOrNull { it.name == entry.entityType }
            // Uma entrada de um tipo que este app não conhece mais só pode ter vindo de uma versão
            // futura do próprio app. Ela fica onde está; inventar um envio seria pior.
            if (type != null) {
                grouped.getOrPut(AggregateKey(type, entry.entitySyncId)) { mutableListOf() } += entry
            }
        }

        val mutations = mutableListOf<PreparedMutation>()
        var deferredDeletes = 0
        var unavailable = 0

        for ((key, entries) in grouped) {
            if (entries.any { it.operation == SyncOperation.DELETE.name }) {
                deferredDeletes += entries.size
                continue
            }

            val envelope = snapshotBuilder.snapshot(key.entityType, key.entitySyncId)
            if (envelope == null) {
                // O agregado não existe mais localmente e não há `DELETE` registrado — só acontece
                // com dado inconsistente. Não se inventa payload: a intenção fica pendente.
                unavailable += entries.size
                continue
            }

            val canonical = BackupCanonicalJson.canonicalHash(envelope.payload)
            val dispatched = entries.last()
            mutations += PreparedMutation(
                // Todas as entradas do agregado são confirmadas juntas: elas descreviam a mesma
                // coisa, e o que sobe é o estado atual, que as satisfaz todas.
                entryIds = entries.map { it.id },
                clientMutationId = dispatched.clientMutationId,
                entityType = key.entityType,
                entitySyncId = key.entitySyncId,
                entitySchemaVersion = envelope.schemaVersion,
                payload = envelope.payload,
                payloadHash = canonical.hash,
                baseRevision = metadataDao
                    .get(ownerUid, key.entityType.name, key.entitySyncId)
                    ?.lastKnownServerRevision
            )
        }

        return PreparedPush(
            mutations = mutations,
            deferredDeletes = deferredDeletes,
            unavailable = unavailable
        )
    }

    private data class AggregateKey(val entityType: SyncEntityType, val entitySyncId: String)
}

/** Uma mutação pronta para subir, e as entradas de Outbox que ela cumpre. */
data class PreparedMutation(
    val entryIds: List<Long>,
    val clientMutationId: String,
    val entityType: SyncEntityType,
    val entitySyncId: String,
    val entitySchemaVersion: Int,
    val payload: JsonElement,
    val payloadHash: String,
    /** A última revision remota conhecida. `null` significa "o servidor ainda não tem isto". */
    val baseRevision: Long?
)

/** O que a Outbox tem para oferecer neste ciclo. */
data class PreparedPush(
    val mutations: List<PreparedMutation> = emptyList(),
    /** Entradas de exclusão que continuam pendentes — a T16.7 é quem as resolve. */
    val deferredDeletes: Int = 0,
    /** Entradas cujo agregado não pôde ser montado. Continuam pendentes. */
    val unavailable: Int = 0
)
