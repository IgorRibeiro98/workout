package com.example.data.sync

import com.example.data.backup.CloudDataBindingDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Onde a escolha do usuário vira estado (T16.7).
 *
 * ```text
 * STALE / tombstone
 *      ↓
 * SyncConflict (durável, os dois lados guardados)
 *      ↓
 * decisão do usuário
 *      ├── versão deste aparelho  →  mutação NOVA na Outbox, base = revision remota atual
 *      └── versão da nuvem        →  aplica local, descarta a tentativa, NENHUMA mutação
 * ```
 *
 * ## Por que existe uma classe só para isso
 *
 * Resolver conflito toca em quatro lugares ao mesmo tempo — domínio, Outbox, revision conhecida e
 * a linha de conflito. Espalhado, algum caminho vai gravar três dos quatro: uma mutação sem
 * rebase (que volta stale para sempre), um conflito apagado sem a mutação (perda silenciosa), uma
 * revision atualizada sem a escrita local (o aparelho passa a mentir sobre o que tem).
 *
 * Aqui cada resolução é **uma transação**, e o que ela grava é tudo ou nada.
 *
 * ## O que este resolvedor nunca faz
 *
 * - **não escolhe.** Não existe aqui *last write wins*, desempate por relógio, merge por campo nem
 *   "resolve os antigos automaticamente". Toda entrada é um toque do usuário;
 * - **não força nada no servidor.** "Manter deste aparelho" não é `overwrite=true`: é uma mutação
 *   nova, com `baseRevision` igual à revision remota que o conflito registrou. Se o servidor tiver
 *   andado de novo no meio, ela volta `STALE` e o conflito é atualizado — que é o comportamento
 *   correto, e não um defeito a contornar;
 * - **não fala com a rede.** Uma resolução termina no Room. O que precisa subir sobe no ciclo
 *   seguinte, pelo caminho normal — inclusive se o app morrer no meio, porque o que ficou gravado
 *   foi uma entrada de Outbox durável.
 *
 * ## Onde a confirmação remota entra (T16.7.1)
 *
 * "Usar a versão da nuvem" passou a exigir que o estado remoto seja reconferido antes de virar
 * escrita. Essa confirmação **não** mora aqui: ela é do [SyncRepository], que é a fronteira
 * pública e já conhece a [SyncApi]. Quando ele chama este resolvedor, a pergunta já foi
 * respondida — e o que acontece aqui continua sendo uma transação Room, sem socket no meio.
 *
 * A divisão é deliberada. Um resolvedor que também fizesse HTTP seria cliente HTTP, DAO, regra de
 * domínio e coordenador ao mesmo tempo; e transformaria em "escolhi se a rede estiver boa" as
 * escolhas que **precisam** continuar funcionando offline — manter o local, excluir mesmo assim,
 * confirmar uma exclusão que a nuvem já fez. Ver
 * [SyncConflictChoice.requiresRemotePreflight].
 */
class SyncConflictResolver(
    private val bindingDao: CloudDataBindingDao,
    private val outboxDao: SyncOutboxDao,
    private val metadataDao: EntitySyncMetadataDao,
    private val conflictDao: SyncConflictDao,
    private val applier: SyncRemoteApplier,
    private val snapshotBuilder: SyncAggregateSnapshotBuilder,
    private val transactions: TransactionRunner,
    private val idGenerator: IdGenerator = RandomUuidIdGenerator,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { encodeDefaults = true }
) {

    /**
     * Os conflitos desta conta, prontos para a tela.
     *
     * Leitura pura: nenhuma requisição sai daqui, e olhar a lista não resolve nada. O `ownerUid`
     * vem do **vínculo** do dataset, e não da sessão atual — é o que garante que a conta B nunca
     * veja o conflito da conta A, mesmo que os dois tenham usado o mesmo aparelho.
     */
    suspend fun conflicts(currentUid: String?): List<SyncConflictSummary> {
        val binding = bindingDao.get() ?: return emptyList()
        if (binding.state != CloudSyncState.ENABLED.name) return emptyList()
        if (binding.ownerUid.isBlank() || currentUid != binding.ownerUid) return emptyList()

        return conflictDao.allFor(binding.ownerUid).mapNotNull { conflict ->
            val type = typeOf(conflict) ?: return@mapNotNull null
            val local = snapshotBuilder.snapshot(type, conflict.entitySyncId)
            SyncConflictPreview.summarize(conflict, type, local?.payload, remotePayloadOf(conflict))
        }
    }

    /**
     * Aplica a decisão do usuário.
     *
     * A conta é revalidada **aqui**, imediatamente antes de gravar, e não quando a tela abriu:
     * entre abrir a lista e tocar no botão o usuário pode ter saído, entrado com outra conta ou
     * trocado o dataset por um restore. Gravar com a validação de antes escreveria na conta errada.
     */
    suspend fun resolve(
        currentUid: String?,
        id: SyncConflictId,
        choice: SyncConflictChoice
    ): SyncConflictResolution {
        val binding = bindingDao.get() ?: return SyncConflictResolution.NotEnabled
        if (binding.state != CloudSyncState.ENABLED.name) return SyncConflictResolution.NotEnabled
        if (binding.ownerUid.isBlank()) return SyncConflictResolution.NotEnabled
        if (currentUid.isNullOrBlank()) return SyncConflictResolution.AuthRequired
        if (currentUid != binding.ownerUid) return SyncConflictResolution.AccountMismatch

        val ownerUid = binding.ownerUid
        val type = SyncEntityType.entries.firstOrNull { it.name == id.entityType }
            ?: return SyncConflictResolution.NotFound
        val conflict = conflictDao.get(ownerUid, id.entityType, id.entitySyncId)
            ?: return SyncConflictResolution.NotFound

        // A linha guarda o dono, e a comparação é explícita: o conflito de A não pode ser resolvido
        // por B nem por acidente de consulta.
        if (conflict.ownerUid != ownerUid) return SyncConflictResolution.AccountMismatch
        if (conflict.status != SyncConflictStatus.PENDING.name) {
            return SyncConflictResolution.AlreadyResolved
        }
        if (choice !in SyncConflictPreview.choicesFor(conflict, type)) {
            return SyncConflictResolution.NotAvailable
        }

        return when (choice) {
            SyncConflictChoice.KEEP_LOCAL ->
                queueLocalDecision(ownerUid, type, conflict, SyncOperation.UPSERT)

            SyncConflictChoice.CONFIRM_LOCAL_DELETE ->
                queueLocalDecision(ownerUid, type, conflict, SyncOperation.DELETE)

            SyncConflictChoice.USE_REMOTE -> useRemote(ownerUid, type, conflict)

            SyncConflictChoice.CONFIRM_REMOTE_DELETE -> confirmRemoteDelete(ownerUid, type, conflict)

            SyncConflictChoice.KEEP_LOCAL_AS_NEW -> keepLocalAsNew(ownerUid, type, conflict)
        }
    }

    // ------------------------------------------------------------------ manter o local

    /**
     * "Manter deste aparelho" — e "excluir mesmo assim", que é a mesma decisão com outra operação.
     *
     * ```text
     * conflito (remoteRevision = 5)
     *      ↓
     * revision conhecida := 5        ← a base passa a ser a versão que o usuário viu e recusou
     * tentativa anterior descartada  ← ela nasceu de uma base que já não existe
     * mutação NOVA (clientMutationId novo, PENDING)
     * conflito := AWAITING_PUSH
     * ```
     *
     * O `clientMutationId` é novo de propósito: reaproveitar o da tentativa recusada faria o
     * servidor devolver o resultado antigo do ledger — "já apliquei isso" — em vez de julgar a
     * decisão nova. É outra intenção, com outra base.
     *
     * Se o servidor tiver avançado de novo entre a leitura do conflito e o push, esta mutação volta
     * `STALE` e o conflito reabre com a revision nova. Isso é o desenho funcionando, não um erro:
     * o que **não** pode acontecer é a alteração de um terceiro aparelho ser sobrescrita porque o
     * usuário decidiu olhando uma tela desatualizada.
     */
    private suspend fun queueLocalDecision(
        ownerUid: String,
        type: SyncEntityType,
        conflict: SyncConflictEntity,
        operation: SyncOperation
    ): SyncConflictResolution = transactions.runInTransaction {
        // A guarda de idempotência é uma escrita condicional, e não uma leitura seguida de escrita:
        // dois toques rápidos afetam **uma** linha, e o segundo encontra zero e desiste. É o que
        // impede "Manter deste aparelho" tocado duas vezes de virar duas mutações.
        val claimed = conflictDao.markAwaitingPush(ownerUid, type.name, conflict.entitySyncId)
        if (claimed == 0) return@runInTransaction SyncConflictResolution.AlreadyResolved

        metadataDao.upsert(
            EntitySyncMetadataEntity(
                ownerUid = ownerUid,
                entityType = type.name,
                entitySyncId = conflict.entitySyncId,
                lastKnownServerRevision = conflict.remoteRevision ?: 0,
                lastSyncedPayloadHash = conflict.remotePayloadHash,
                lastSyncedAt = clock()
            )
        )
        outboxDao.discardBlockedFor(ownerUid, type.name, conflict.entitySyncId)
        outboxDao.insert(
            SyncOutboxEntryEntity(
                clientMutationId = idGenerator.newId(),
                ownerUid = ownerUid,
                entityType = type.name,
                entitySyncId = conflict.entitySyncId,
                operation = operation.name,
                status = SyncOutboxStatus.PENDING.name,
                createdAt = clock()
            )
        )
        SyncConflictResolution.Queued
    }

    // ------------------------------------------------------------------ usar o remoto

    /**
     * "Usar a versão da nuvem".
     *
     * ```text
     * payload remoto guardado
     *      ↓ decodificação estrita (a mesma do pull)
     * Room
     *      +  revision conhecida := revision remota
     *      +  tentativa local descartada
     *      +  conflito removido
     *      ↓
     * NENHUMA mutação de saída
     * ```
     *
     * A ausência da mutação é o ponto: gerar uma aqui devolveria ao servidor o que acabou de vir
     * dele, gastaria uma revision descrevendo uma mudança que não houve e faria os outros aparelhos
     * baixarem o que já têm — um laço que não fecha.
     *
     * O snapshot remoto guardado é uma cópia **validada**: ele veio de uma página de pull, com o
     * hash canônico que o servidor calculou, e é reconferido aqui antes de virar escrita.
     *
     * Reconferir o hash prova que a cópia não corrompeu — **não** prova que ela ainda é a atual.
     * Essa é outra pergunta, e desde a T16.7.1 ela é feita ao servidor **antes** desta chamada,
     * pelo [SyncRepository]: se a revision remota tiver avançado, este método não chega a ser
     * chamado, o conflito é atualizado e o usuário escolhe de novo. Aplicar aqui uma revision que
     * o servidor já sabe estar superada seria sobrescrever dado local de propósito com uma versão
     * velha.
     */
    private suspend fun useRemote(
        ownerUid: String,
        type: SyncEntityType,
        conflict: SyncConflictEntity
    ): SyncConflictResolution {
        val payload = remotePayloadOf(conflict) ?: return SyncConflictResolution.NoRemoteCopy
        if (!SyncConflictPreview.remoteCopyIsIntact(conflict)) {
            return SyncConflictResolution.NoRemoteCopy
        }

        return try {
            transactions.runInTransaction {
                val written = applier.writeRemoteAggregate(
                    type,
                    conflict.entitySyncId,
                    SyncProtocol.SUPPORTED_ENTITY_SCHEMA_VERSION,
                    payload
                )
                if (!written) return@runInTransaction SyncConflictResolution.NoRemoteCopy

                discardLocalAttempt(ownerUid, type, conflict.entitySyncId)
                metadataDao.upsert(
                    EntitySyncMetadataEntity(
                        ownerUid = ownerUid,
                        entityType = type.name,
                        entitySyncId = conflict.entitySyncId,
                        lastKnownServerRevision = conflict.remoteRevision ?: 0,
                        lastSyncedPayloadHash = conflict.remotePayloadHash,
                        lastSyncedAt = clock()
                    )
                )
                conflictDao.clear(ownerUid, type.name, conflict.entitySyncId)
                SyncConflictResolution.Applied
            }
        } catch (e: SyncRemoteApplyException) {
            // Uma referência do payload remoto não existe neste aparelho (catálogo desatualizado).
            // A transação foi desfeita: o conflito continua inteiro, e nada foi perdido.
            SyncConflictResolution.NoRemoteCopy
        }
    }

    // ------------------------------------------------------------------ exclusão remota

    /**
     * "Confirmar exclusão" — o outro aparelho apagou, e o usuário concorda.
     *
     * Nenhuma mutação nova sai daqui: o servidor **já** tem o tombstone, e mandar um `DELETE` seria
     * pedir de novo o que já foi feito. O que acontece é local: a linha some, a tentativa que
     * estava guardada é descartada (é ela que o usuário acabou de abrir mão) e o conflito fecha.
     */
    private suspend fun confirmRemoteDelete(
        ownerUid: String,
        type: SyncEntityType,
        conflict: SyncConflictEntity
    ): SyncConflictResolution = transactions.runInTransaction {
        when (applier.deleteAggregateLocally(ownerUid, type, conflict.entitySyncId)) {
            SyncLocalDeleteGuard.Deleted -> Unit
            SyncLocalDeleteGuard.StillReferenced ->
                return@runInTransaction SyncConflictResolution.StillReferenced
            SyncLocalDeleteGuard.PendingChildMutations ->
                return@runInTransaction SyncConflictResolution.PendingChildChanges
        }

        discardLocalAttempt(ownerUid, type, conflict.entitySyncId)
        metadataDao.upsert(
            EntitySyncMetadataEntity(
                ownerUid = ownerUid,
                entityType = type.name,
                entitySyncId = conflict.entitySyncId,
                lastKnownServerRevision = conflict.remoteRevision ?: 0,
                lastSyncedPayloadHash = null,
                lastSyncedAt = clock()
            )
        )
        conflictDao.clear(ownerUid, type.name, conflict.entitySyncId)
        SyncConflictResolution.Applied
    }

    /**
     * "Manter neste aparelho" depois de uma exclusão remota — como **entidade nova**.
     *
     * ```text
     * Treino X (syncId antigo, com tombstone na nuvem)
     *      ↓
     * Treino X (syncId NOVO)  +  mutação de criação
     * ```
     *
     * A identidade antiga **não** é reaproveitada, e isso é a decisão mais importante desta tela.
     * O tombstone significa "esta entidade morreu"; reusar o `syncId` dela pediria ao servidor para
     * desdizer isso, e todo aparelho que já aplicou a exclusão veria o item voltar sem ninguém ter
     * pedido. Com identidade nova, o que aconteceu é o que a pessoa realmente quis: ela **recriou**
     * algo a partir do que tinha — a exclusão continua tendo acontecido.
     *
     * O `syncId` de uma entidade continua imutável: nada é reescrito aqui. A linha antiga é apagada
     * e uma linha nova nasce, exatamente como se o usuário tivesse duplicado o item.
     */
    private suspend fun keepLocalAsNew(
        ownerUid: String,
        type: SyncEntityType,
        conflict: SyncConflictEntity
    ): SyncConflictResolution {
        val local = snapshotBuilder.snapshot(type, conflict.entitySyncId)
            ?: return SyncConflictResolution.NoLocalCopy
        val newSyncId = idGenerator.newId()
        val patched = withSyncId(local.payload, newSyncId)
            ?: return SyncConflictResolution.NoLocalCopy

        return try {
            transactions.runInTransaction {
                // A ordem importa: a linha antiga sai antes de a nova entrar, para que um índice
                // único (nome curto de treino, por exemplo) não recuse a cópia por causa dela
                // mesma. Nada aqui sobrevive a uma falha — é uma transação.
                when (applier.deleteAggregateLocally(ownerUid, type, conflict.entitySyncId)) {
                    SyncLocalDeleteGuard.Deleted -> Unit
                    SyncLocalDeleteGuard.StillReferenced ->
                        return@runInTransaction SyncConflictResolution.StillReferenced
                    SyncLocalDeleteGuard.PendingChildMutations ->
                        return@runInTransaction SyncConflictResolution.PendingChildChanges
                }

                val written = applier.writeRemoteAggregate(type, newSyncId, local.schemaVersion, patched)
                if (!written) return@runInTransaction SyncConflictResolution.NoLocalCopy

                discardLocalAttempt(ownerUid, type, conflict.entitySyncId)
                // A identidade antiga morreu: guardar a revision do tombstone impede que qualquer
                // caminho futuro a trate como algo que ainda pode ser enviado.
                metadataDao.upsert(
                    EntitySyncMetadataEntity(
                        ownerUid = ownerUid,
                        entityType = type.name,
                        entitySyncId = conflict.entitySyncId,
                        lastKnownServerRevision = conflict.remoteRevision ?: 0,
                        lastSyncedPayloadHash = null,
                        lastSyncedAt = clock()
                    )
                )
                conflictDao.clear(ownerUid, type.name, conflict.entitySyncId)

                // A entidade nova é uma criação como qualquer outra: `baseRevision` nula, e o
                // servidor decide. Ela não herda revision nem metadata da que morreu.
                outboxDao.insert(
                    SyncOutboxEntryEntity(
                        clientMutationId = idGenerator.newId(),
                        ownerUid = ownerUid,
                        entityType = type.name,
                        entitySyncId = newSyncId,
                        operation = SyncOperation.UPSERT.name,
                        status = SyncOutboxStatus.PENDING.name,
                        createdAt = clock()
                    )
                )
                SyncConflictResolution.Queued
            }
        } catch (e: SyncRemoteApplyException) {
            SyncConflictResolution.NoLocalCopy
        }
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Descarta a tentativa local que estava guardada para aquele agregado.
     *
     * Só acontece dentro de uma resolução em que o usuário abriu mão dela explicitamente — usar a
     * versão da nuvem, ou confirmar a exclusão. Fora daí, uma entrada bloqueada nunca é apagada:
     * ela é a alteração da pessoa.
     */
    private suspend fun discardLocalAttempt(
        ownerUid: String,
        type: SyncEntityType,
        entitySyncId: String
    ) {
        outboxDao.acknowledge(
            ownerUid,
            outboxDao.entriesFor(ownerUid, type.name, entitySyncId).map { it.id }
        )
    }

    private fun typeOf(conflict: SyncConflictEntity): SyncEntityType? =
        SyncEntityType.entries.firstOrNull { it.name == conflict.entityType }

    private fun remotePayloadOf(conflict: SyncConflictEntity) = conflict.remotePayload?.let {
        try {
            json.parseToJsonElement(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * O mesmo conteúdo, com outra identidade global.
     *
     * Substituição de **um** campo no objeto raiz, e não uma reconstrução do DTO: assim a recriação
     * não precisa conhecer o formato de cada agregado, e um campo novo em qualquer payload continua
     * viajando junto sem ninguém lembrar de atualizar este arquivo.
     */
    private fun withSyncId(payload: JsonElement, syncId: String): JsonObject? =
        (payload as? JsonObject)?.let { root ->
            if (!root.containsKey(SYNC_ID_FIELD)) return@let null
            JsonObject(root + (SYNC_ID_FIELD to JsonPrimitive(syncId)))
        }

    private companion object {
        const val SYNC_ID_FIELD = "syncId"
    }
}

/**
 * A identidade de um conflito, do ponto de vista de quem toca no botão.
 *
 * Opaca de propósito: a tela recebe e devolve o mesmo valor, e não precisa saber que ele é um par
 * (tipo do agregado, identidade global). É o que mantém o vocabulário do protocolo fora da UI.
 */
data class SyncConflictId(val entityType: String, val entitySyncId: String)

/** O que o usuário pode escolher. Quais delas existem depende do conflito. */
enum class SyncConflictChoice {

    /** Fica a versão deste aparelho, e ela é enviada como decisão nova. */
    KEEP_LOCAL,

    /** Fica a versão da nuvem, aplicada localmente. Nada é enviado. */
    USE_REMOTE,

    /** O item foi apagado na nuvem e o usuário quer mantê-lo — como entidade nova. */
    KEEP_LOCAL_AS_NEW,

    /** O item foi apagado na nuvem e o usuário concorda: apaga aqui também. */
    CONFIRM_REMOTE_DELETE,

    /** O usuário apagou aqui, a nuvem tem versão mais nova, e ele confirma a exclusão. */
    CONFIRM_LOCAL_DELETE;

    /**
     * Esta escolha só é segura se o estado remoto for reconferido **antes** de virar escrita
     * local (T16.7.1).
     *
     * Vale para uma escolha só, e o motivo é assimétrico:
     *
     * - **[USE_REMOTE] sobrescreve dado local com uma cópia da nuvem.** Aquela cópia veio de uma
     *   página de pull que já passou, e entre a chegada dela e o toque do usuário um terceiro
     *   aparelho pode ter escrito. Aplicar sem perguntar seria gravar aqui, de propósito, uma
     *   versão que o servidor **já sabe** estar superada;
     * - **[KEEP_LOCAL] e [CONFIRM_LOCAL_DELETE] não sobrescrevem nada.** Elas mantêm o que já está
     *   no Room e viram mutação nova, com `baseRevision` igual à revision que o usuário recusou.
     *   Se o servidor tiver andado, o push volta `STALE` e o conflito reabre — a proteção já
     *   existe, e é ela que faz uma decisão tomada em modo avião continuar valendo;
     * - **[CONFIRM_REMOTE_DELETE] depende de um tombstone, e tombstone não muda.** `deleted = 1`
     *   é terminal: o `ON CONFLICT DO UPDATE` do servidor tem `AND sync_entities.deleted = 0`, e
     *   não existe `force` nem endpoint paralelo. Confirmar uma exclusão que a nuvem fez continua
     *   correto em qualquer revision futura, porque nenhuma revision futura desfaz a exclusão.
     *   Exigir rede aqui seria complexidade sem invariante a proteger — e custaria o
     *   funcionamento offline;
     * - **[KEEP_LOCAL_AS_NEW] cria uma entidade com `syncId` novo.** O que o servidor tem na
     *   identidade morta não muda o que essa criação significa.
     */
    val requiresRemotePreflight: Boolean get() = this == USE_REMOTE
}

/** O desfecho de uma resolução. */
sealed interface SyncConflictResolution {

    /** Resolvido inteiramente aqui: nada precisa subir. */
    data object Applied : SyncConflictResolution

    /** A decisão virou uma mutação na fila. Ela sobe no próximo ciclo. */
    data object Queued : SyncConflictResolution

    /** Já resolvido — segundo toque, ou outra tela. Não é erro e nada é feito duas vezes. */
    data object AlreadyResolved : SyncConflictResolution

    /** O conflito não existe mais. */
    data object NotFound : SyncConflictResolution

    /** Esta escolha não se aplica a este conflito. */
    data object NotAvailable : SyncConflictResolution

    /** O dataset deste aparelho não pertence a nenhuma Conta Spark. */
    data object NotEnabled : SyncConflictResolution

    /** Não há sessão conectada agora. */
    data object AuthRequired : SyncConflictResolution

    /** A conta conectada não é a dona destes dados. */
    data object AccountMismatch : SyncConflictResolution

    /** Não há cópia remota válida guardada para aplicar. Sincronize e tente de novo. */
    data object NoRemoteCopy : SyncConflictResolution

    /** Não há mais cópia local para recriar. */
    data object NoLocalCopy : SyncConflictResolution

    /** Outra coisa ainda usa este item — apagar deixaria uma referência quebrada. */
    data object StillReferenced : SyncConflictResolution

    /** Há alterações locais pendentes dentro deste item que seriam perdidas. */
    data object PendingChildChanges : SyncConflictResolution

    /**
     * A versão da nuvem mudou enquanto o usuário decidia (T16.7.1).
     *
     * O conflito foi **atualizado** com o estado atual do servidor e voltou a esperar decisão.
     * Nada local foi escrito, e a escolha anterior não é reaproveitada: ela descrevia um conteúdo
     * que o usuário viu e que já não é o que a nuvem tem. "Ele já escolheu remoto, então aplica a
     * revision nova" seria aplicar algo que ninguém conferiu.
     */
    data object RemoteChanged : SyncConflictResolution

    /**
     * Não foi possível confirmar o estado atual da nuvem (T16.7.1).
     *
     * Rede, servidor indisponível, rate limit, sessão ausente ou backend não configurado. O
     * conflito continua **inteiro** e o Room continua intacto: aplicar a cópia guardada como
     * "plano B" seria exatamente o defeito que a confirmação existe para impedir.
     */
    data object RemoteUnavailable : SyncConflictResolution

    /**
     * O servidor contradiz o que este aparelho tinha guardado sobre o lado remoto (T16.7.1).
     *
     * Um tombstone que voltou vivo, uma resposta sobre outra identidade, ou uma identidade que a
     * conta já não tem. Nenhum destes é um estado que o protocolo produz, e nenhum é reconciliado
     * por heurística: nada é aplicado e nada é apagado.
     */
    data object RemoteInconsistent : SyncConflictResolution
}
