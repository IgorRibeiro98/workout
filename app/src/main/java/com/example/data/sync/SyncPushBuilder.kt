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
 * ## `DELETE` sai daqui desde a T16.7
 *
 * A T16.6 não propagava exclusão: sem tombstone no servidor, apagar em outro aparelho seria uma
 * decisão sem política de retenção nem prevenção de ressurreição. Agora o servidor tem tombstone,
 * e a exclusão viaja como qualquer outra mudança.
 *
 * A coalescência decide pela **última** entrada do agregado, e não pela primeira:
 *
 * ```text
 * UPSERT, UPSERT, DELETE   →   uma mutação DELETE       (o estado final é "não existe")
 * DELETE, UPSERT           →   uma mutação UPSERT        (a entidade voltou a existir aqui)
 * ```
 *
 * É a mesma regra que já valia para o conteúdo: o que sobe é **o que o Room diz agora**, e as
 * entradas intermediárias descreviam estados que o usuário já abandonou. Todas são confirmadas
 * juntas porque a mutação despachada as satisfaz todas.
 *
 * Uma exclusão de agregado cuja política proíbe `DELETE` remoto ([SyncEntityPolicies]) **não** é
 * enviada: ela ficaria pendente para sempre recebendo `UNSUPPORTED` do servidor. Ela é contada
 * como adiada, e a exclusão local continua valendo — o que não acontece é a propagação.
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
            val dispatched = entries.last()

            if (dispatched.operation == SyncOperation.DELETE.name) {
                if (!SyncEntityPolicies.isDeleteAllowed(key.entityType)) {
                    // O servidor recusaria com `DELETE_NOT_ALLOWED`, e reenviar produziria a mesma
                    // recusa para sempre. A exclusão local continua feita; ela é que não viaja.
                    deferredDeletes += entries.size
                    continue
                }
                mutations += PreparedMutation(
                    entryIds = entries.map { it.id },
                    clientMutationId = dispatched.clientMutationId,
                    entityType = key.entityType,
                    entitySyncId = key.entitySyncId,
                    // A exclusão não carrega conteúdo, então não há schema de payload a declarar.
                    // A versão vai assim mesmo porque o envelope da mutação a exige, e é a versão
                    // que este app fala.
                    entitySchemaVersion = SyncProtocol.SUPPORTED_ENTITY_SCHEMA_VERSION,
                    operation = SyncOperation.DELETE,
                    payload = null,
                    payloadHash = "",
                    baseRevision = metadataDao
                        .get(ownerUid, key.entityType.name, key.entitySyncId)
                        ?.lastKnownServerRevision
                )
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
            mutations += PreparedMutation(
                // Todas as entradas do agregado são confirmadas juntas: elas descreviam a mesma
                // coisa, e o que sobe é o estado atual, que as satisfaz todas.
                entryIds = entries.map { it.id },
                clientMutationId = dispatched.clientMutationId,
                entityType = key.entityType,
                entitySyncId = key.entitySyncId,
                entitySchemaVersion = envelope.schemaVersion,
                operation = SyncOperation.UPSERT,
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

    /**
     * O hash canônico do agregado **como ele está agora**, ou `null` se ele não existe mais aqui.
     *
     * Mesma montagem e mesmo hash de [prepare] — de propósito. É o que permite ao repositório, ao
     * confirmar um push, perguntar "o que subiu ainda descreve o Room?" sem inventar uma segunda
     * forma canônica que divergiria da primeira na primeira mudança de contrato.
     */
    suspend fun currentPayloadHash(entityType: SyncEntityType, entitySyncId: String): String? =
        snapshotBuilder.snapshot(entityType, entitySyncId)
            ?.let { BackupCanonicalJson.canonicalHash(it.payload).hash }

    private data class AggregateKey(val entityType: SyncEntityType, val entitySyncId: String)
}

/** Uma mutação pronta para subir, e as entradas de Outbox que ela cumpre. */
data class PreparedMutation(
    val entryIds: List<Long>,
    val clientMutationId: String,
    val entityType: SyncEntityType,
    val entitySyncId: String,
    val entitySchemaVersion: Int,
    val operation: SyncOperation,
    /** `null` numa exclusão: ela não afirma conteúdo. */
    val payload: JsonElement?,
    /** Vazio numa exclusão, pelo mesmo motivo. */
    val payloadHash: String,
    /** A última revision remota conhecida. `null` significa "o servidor ainda não tem isto". */
    val baseRevision: Long?
)

/** O que a Outbox tem para oferecer neste ciclo. */
data class PreparedPush(
    val mutations: List<PreparedMutation> = emptyList(),
    /**
     * Entradas de exclusão que não viajam porque a política do agregado não permite (T16.7).
     *
     * Nenhum agregado do Spark cai aqui hoje pelo caminho do app — só `CHECK_IN` proíbe exclusão
     * remota, e não existe tela que apague um check-in. O contador existe para que, se algum dia
     * existir, a limitação seja **visível** em vez de silenciosa.
     */
    val deferredDeletes: Int = 0,
    /** Entradas cujo agregado não pôde ser montado. Continuam pendentes. */
    val unavailable: Int = 0
)
