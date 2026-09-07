package com.example.data.sync

import com.example.data.backup.BackupCanonicalJson
import com.example.data.backup.CloudDataBindingDao
import com.example.data.backup.CloudDataBindingEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * O caso de uso do sync incremental no Android (T16.6).
 *
 * ```text
 * 1. valida conta e vínculo         ← só dataset adotado sincroniza, e só com a conta dona
 * 2. push da Outbox                 ← em lotes, em ordem de intenção
 * 3. processa ACKs e conflitos      ← confirma o que subiu, bloqueia o que ficou stale
 * 4. pull do change log             ← página a página, a partir do cursor durável
 * 5. aplica cada página             ← transação: domínio + metadata + cursor
 * 6. repete até hasMore = false
 * ```
 *
 * ## Push antes de pull
 *
 * A Outbox descreve o que este aparelho decidiu; mandá-la primeiro é o que garante que uma
 * alteração local não seja transformada em conflito por uma mudança remota que chegaria no mesmo
 * ciclo. Quando o push encontra `STALE`, o conflito é registrado e o pull seguinte **traz o lado
 * remoto** — sem aplicá-lo por cima do local sujo. É sobre esses dois lados guardados que o usuário
 * decide (T16.7), e é essa decisão que a T16.7.1 passou a confirmar contra o servidor.
 *
 * ## O que este repositório nunca faz
 *
 * - **não resolve conflito.** Sem *last write wins*, sem "reenviar com a revision atual", sem
 *   desempate por `updatedAt`, sem merge por campo. Há teste estrutural sobre isso;
 * - **não apaga a Outbox por erro de rede.** Timeout, 503 e conexão perdida deixam tudo pendente;
 * - **não escreve domínio.** Quem escreve é o [SyncRemoteApplier], em transação, fora do
 *   coordenador de mutações — para que aplicar o que veio do servidor não gere Outbox nova;
 * - **não substitui o backup.** O snapshot completo da T16.4 continua sendo criado só quando o
 *   usuário pede, e um ciclo de sync nunca dispara um.
 */
class SyncRepository(
    private val bindingDao: CloudDataBindingDao,
    private val outboxDao: SyncOutboxDao,
    private val metadataDao: EntitySyncMetadataDao,
    private val cursorDao: SyncCursorDao,
    private val conflictDao: SyncConflictDao,
    private val pushBuilder: SyncPushBuilder,
    private val applier: SyncRemoteApplier,
    private val api: SyncApi,
    /**
     * Quem aplica a decisão do usuário sobre um conflito (T16.7).
     *
     * Fica atrás do repositório porque a tela já conversa com ele: um segundo objeto público para
     * a mesma conversa faria a UI precisar saber quando falar com qual.
     */
    private val conflictResolver: SyncConflictResolver,
    /**
     * A identidade da instalação (T16.3), sob demanda.
     *
     * Uma função e não o `DeviceIdProvider`: ele vive no DataStore, e o sync não precisa conhecer
     * onde a identidade mora — só precisa dela no momento de montar o corpo do push.
     */
    private val deviceId: suspend () -> String,
    /**
     * A sessão do Firebase **agora**, sob demanda (T16.7.1).
     *
     * O repositório já recebe um `currentUid` de quem chama, e ainda assim precisa desta leitura:
     * aquele valor é um retrato de **antes** da requisição HTTP. Entre o começo de uma consulta
     * remota e a resposta dela, o usuário pode ter saído e entrado com outra conta — e uma
     * resposta obtida como B jamais pode virar escrita no dataset de A. A conta é reconferida
     * imediatamente antes de qualquer aplicação, com o valor lido **depois** da resposta.
     */
    private val accounts: SyncAccountProvider,
    private val transactions: TransactionRunner,
    /**
     * A trava compartilhada com backup e restore (T16.4/T16.5).
     *
     * Os três disputam o mesmo banco. Um ciclo de sync aplicando mudanças remotas no meio de uma
     * substituição de dataset produziria um estado que nunca existiu em lugar nenhum.
     */
    private val operationLock: CloudOperationLock = CloudOperationLock(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { encodeDefaults = true }
) {

    val isConfigured: Boolean get() = api.isConfigured

    /**
     * Uma resolução de conflito por vez, neste processo (T16.7.1).
     *
     * Não é a `CloudOperationLock` de propósito: aquela recusa quem chega, e recusar a segunda
     * metade de um toque duplo faria a tela mostrar erro para algo que já deu certo. Aqui a
     * segunda entra **depois** da primeira, encontra o conflito já resolvido e desiste em
     * silêncio — que é o que o usuário espera de tocar duas vezes no mesmo botão.
     */
    private val resolutionMutex = Mutex()

    /** O vínculo atual do dataset, ou `null` se ele ainda não tem dono. */
    suspend fun binding(): CloudDataBindingEntity? = bindingDao.get()

    /**
     * O que a tela mostra. **Leitura pura** — nenhuma requisição sai daqui.
     *
     * `ownerUid` só vem preenchido quando o dataset está de fato apto a sincronizar: vínculo
     * existente **e** em `ENABLED`. Uma adoção em andamento (`PREPARING`) ainda não tem baseline no
     * servidor, e mostrar "atualizado" ali seria dizer que algo convergiu quando nada subiu.
     */
    suspend fun snapshot(): SyncSnapshot {
        val binding = bindingDao.get() ?: return SyncSnapshot()
        if (binding.state != CloudSyncState.ENABLED.name) return SyncSnapshot()
        if (binding.ownerUid.isBlank()) return SyncSnapshot()
        val ownerUid = binding.ownerUid
        val cursor = cursorDao.get(ownerUid)
        return SyncSnapshot(
            ownerUid = ownerUid,
            pending = outboxDao.pendingCountFor(ownerUid),
            blocked = outboxDao.blockedCountFor(ownerUid),
            conflicts = conflictDao.countFor(ownerUid),
            lastSyncedAt = cursor?.lastSyncedAt,
            cursor = cursor?.lastPulledServerSequence ?: 0
        )
    }

    /**
     * Os conflitos que esperam decisão, prontos para a tela (T16.7).
     *
     * Leitura pura: abrir a lista não sincroniza, não resolve e não envia nada.
     */
    suspend fun conflicts(currentUid: String?): List<SyncConflictSummary> =
        conflictResolver.conflicts(currentUid)

    /**
     * Aplica a decisão do usuário sobre um conflito (T16.7, revisada na T16.7.1).
     *
     * ```text
     * escolha do usuário
     *   ├── depende do estado remoto?  (só "usar a versão da nuvem")
     *   │      ↓ sim
     *   │   GET /v1/sync/entities/...        ← o estado de AGORA
     *   │      ↓
     *   │   revalida a conta DEPOIS da resposta
     *   │      ↓
     *   │   ainda é a mesma revision?
     *   │      ├── sim  →  SyncConflictResolver  →  transação Room
     *   │      └── não  →  conflito atualizado, PENDING, nada aplicado
     *   │
     *   └── não  →  SyncConflictResolver  →  transação Room     ← funciona offline
     * ```
     *
     * ## Por que a confirmação vive aqui
     *
     * O [SyncConflictResolver] continua sendo só transação: ele não conhece [SyncApi] e não abre
     * conexão. Este repositório já é a fronteira pública que a tela usa e já conhece o transporte,
     * então é ele quem faz a pergunta e ele quem decide se a decisão pode virar escrita.
     *
     * ## Um toque, uma operação
     *
     * O `Mutex` serializa resoluções deste aparelho. Dois toques rápidos em "Usar versão da nuvem"
     * produzem **uma** consulta remota e **uma** aplicação: o segundo entra depois do commit do
     * primeiro, encontra o conflito já resolvido e desiste. A trava é de processo e não substitui
     * a idempotência durável do banco — ela evita a corrida que a escrita condicional não pega,
     * que é a de duas requisições HTTP simultâneas para a mesma decisão.
     */
    suspend fun resolveConflict(
        currentUid: String?,
        id: SyncConflictId,
        choice: SyncConflictChoice
    ): SyncConflictResolution = resolutionMutex.withLock {
        if (!choice.requiresRemotePreflight) {
            // Manter o local, excluir mesmo assim, confirmar uma exclusão que a nuvem já fez,
            // recriar como item novo: nenhuma delas sobrescreve dado local com conteúdo remoto, e
            // todas continuam funcionando em modo avião. Pedir rede aqui só para "padronizar"
            // transformaria "escolhi" em "escolhi se a rede estiver boa".
            return@withLock conflictResolver.resolve(currentUid, id, choice)
        }
        resolveAgainstCurrentRemote(currentUid, id, choice)
    }

    /**
     * "Usar a versão da nuvem", com o estado remoto reconferido antes de virar escrita (T16.7.1).
     *
     * As pré-condições são checadas **antes** da rede, para que um conflito já resolvido ou de
     * outra conta não vire requisição. E a conta é checada **de novo** depois da resposta, porque
     * é entre esses dois pontos que ela pode ter mudado.
     */
    private suspend fun resolveAgainstCurrentRemote(
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
        if (conflict.status != SyncConflictStatus.PENDING.name) {
            return SyncConflictResolution.AlreadyResolved
        }
        if (choice !in SyncConflictPreview.choicesFor(conflict, type)) {
            return SyncConflictResolution.NotAvailable
        }

        // Sem endereço de backend não há como confirmar nada — e sem confirmação, nada é aplicado.
        if (!api.isConfigured) return SyncConflictResolution.RemoteUnavailable

        val state = when (val outcome = api.entityState(type, conflict.entitySyncId)) {
            is SyncEntityStateOutcome.Success -> outcome.state

            // O servidor respondeu, e a resposta é "esta identidade não existe nesta conta". Não
            // dá para provar que a cópia guardada é a atual, então ela não é aplicada.
            SyncEntityStateOutcome.NotFound -> return SyncConflictResolution.RemoteInconsistent

            // Falha de transporte, sessão ausente ou recusa. **Nada** muda: nem o Room, nem a
            // Outbox, nem o conflito. Aplicar o snapshot guardado como plano B seria justamente o
            // que a confirmação existe para impedir.
            SyncEntityStateOutcome.Network,
            SyncEntityStateOutcome.Unavailable,
            SyncEntityStateOutcome.RateLimited,
            SyncEntityStateOutcome.AuthRequired,
            SyncEntityStateOutcome.NotConfigured,
            is SyncEntityStateOutcome.Rejected -> return SyncConflictResolution.RemoteUnavailable
        }

        // ---------------------------------------------------------------- revalidação de conta
        //
        // Três coisas precisam ser a mesma conta, e a prova é feita **depois** da resposta:
        //
        //   1. quem o servidor autenticou nesta requisição  (`state.ownerUid`, do token verificado)
        //   2. o dono do dataset deste aparelho             (`cloud_data_binding`, relido agora)
        //   3. a sessão do Firebase neste instante          (`accounts.currentUid()`)
        //
        // O `currentUid` que chegou aqui é anterior à requisição e não serve para isso: uma troca
        // de conta durante o voo o deixaria descrevendo uma sessão que já não existe.
        val bindingNow = bindingDao.get()
        if (bindingNow == null ||
            bindingNow.state != CloudSyncState.ENABLED.name ||
            bindingNow.ownerUid != ownerUid
        ) {
            return SyncConflictResolution.AccountMismatch
        }
        val liveUid = accounts.currentUid()
        if (liveUid.isNullOrBlank()) return SyncConflictResolution.AuthRequired
        if (liveUid != ownerUid) return SyncConflictResolution.AccountMismatch
        if (state.ownerUid != ownerUid) return SyncConflictResolution.AccountMismatch

        return when (SyncRemotePreflight.evaluate(conflict, type, state)) {
            // A cópia guardada é a atual. A decisão do usuário descreve o estado real do servidor,
            // e pode virar escrita — pelo mesmo resolvedor transacional de sempre.
            SyncRemotePreflightVerdict.Current ->
                conflictResolver.resolve(liveUid, id, choice)

            SyncRemotePreflightVerdict.Changed -> {
                refreshRemoteSide(ownerUid, type, conflict, state)
                SyncConflictResolution.RemoteChanged
            }

            SyncRemotePreflightVerdict.Resurrected,
            SyncRemotePreflightVerdict.Mismatched ->
                SyncConflictResolution.RemoteInconsistent
        }
    }

    /**
     * Substitui o lado remoto do conflito pelo estado atual do servidor (T16.7.1).
     *
     * O lado **local** é preservado inteiro: a entidade no Room, a entrada bloqueada da Outbox, a
     * `baseRevision`, o hash local e a tentativa que ficou guardada. O que muda é a versão da
     * nuvem entre as quais o usuário escolhe — e o `status`, que volta para `PENDING` porque a
     * decisão anterior descrevia um conteúdo que já não é o que a nuvem tem.
     */
    private suspend fun refreshRemoteSide(
        ownerUid: String,
        type: SyncEntityType,
        conflict: SyncConflictEntity,
        state: SyncEntityStateDto
    ) {
        transactions.runInTransaction {
            conflictDao.upsert(
                conflict.copy(
                    // Um tombstone muda **o que o conflito é**: as escolhas que faziam sentido
                    // deixam de fazer, e a tela passa a oferecer as da exclusão. É a mesma regra
                    // que o pull já aplica quando o tombstone chega pelo change log.
                    kind = if (state.deleted) {
                        SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED.name
                    } else {
                        conflict.kind
                    },
                    status = SyncConflictStatus.PENDING.name,
                    remoteRevision = state.serverRevision,
                    // Esta leitura é por identidade, não por cursor: ela não tem posição no change
                    // log, e inventar uma seria mentir sobre de onde o conteúdo veio.
                    remoteServerSequence = conflict.remoteServerSequence,
                    remotePayloadHash = state.payloadHash,
                    // Num tombstone não há conteúdo a guardar, e o que já estava guardado é
                    // preservado: ele continua sendo a única cópia daquele conteúdo aqui. Sem
                    // hash, "usar a versão da nuvem" deixa de ser oferecida — que é o correto para
                    // algo que a nuvem não tem mais.
                    remotePayload = state.payload
                        ?.let { BackupCanonicalJson.canonicalize(it) }
                        ?: conflict.remotePayload
                )
            )
        }
    }

    /**
     * Um ciclo completo de sincronização.
     *
     * [currentUid] é o `uid` da sessão Firebase **atual**. Ele não decide de quem é o dataset —
     * isso é do vínculo — e serve só para detectar ausência de sessão e descompasso de conta.
     */
    suspend fun syncNow(currentUid: String?): SyncOutcome =
        operationLock.tryRun { runCycle(currentUid) } ?: SyncOutcome.AlreadyRunning

    private suspend fun runCycle(currentUid: String?): SyncOutcome {
        if (!api.isConfigured) return SyncOutcome.NotConfigured

        val binding = bindingDao.get() ?: return SyncOutcome.NotEnabled
        // `PREPARING` é adoção em andamento: o primeiro backup ainda não foi confirmado, e o
        // servidor não tem baseline nenhum desta conta. Sincronizar aqui subiria metade de um
        // dataset que o usuário ainda não terminou de associar.
        if (binding.state != CloudSyncState.ENABLED.name) return SyncOutcome.NotEnabled
        if (binding.ownerUid.isBlank()) return SyncOutcome.NotEnabled

        if (currentUid.isNullOrBlank()) return SyncOutcome.AuthRequired
        // Estar logado não basta: o dataset precisa ser **desta** conta. Sincronizar dados de A
        // para a conta B é o defeito que o vínculo existe para impedir.
        if (currentUid != binding.ownerUid) return SyncOutcome.AccountMismatch(binding.ownerUid)

        val ownerUid = binding.ownerUid
        val push = pushPhase(ownerUid, deviceId())
        if (push.failure != null) return push.failure

        val pull = pullPhase(ownerUid)
        if (pull.failure != null) return pull.failure

        // "Sincronizado agora" é o relógio deste aparelho e serve **só** para a frase da tela.
        // Ele nunca ordena nada: quem ordena é a sequência do servidor.
        cursorDao.upsert(
            SyncCursorEntity(
                ownerUid = ownerUid,
                lastPulledServerSequence = pull.cursor,
                lastSyncedAt = clock()
            )
        )

        return SyncOutcome.Success(
            pushed = push.pushed,
            applied = pull.applied,
            converged = pull.converged,
            // Agregados em conflito **ao fim do ciclo**, e não eventos de conflito: o mesmo treino
            // pode ser detectado duas vezes no mesmo ciclo (stale no push, remoto adiante no
            // pull) e continua sendo **um** item que precisa de atenção.
            conflicts = conflictDao.countFor(ownerUid),
            deferredDeletes = push.deferredDeletes,
            pausedAt = pull.stop
        )
    }

    // ------------------------------------------------------------------ push

    private suspend fun pushPhase(ownerUid: String, deviceId: String): PushPhase {
        val prepared = pushBuilder.prepare(ownerUid)
        if (prepared.mutations.isEmpty()) {
            return PushPhase(deferredDeletes = prepared.deferredDeletes)
        }

        var pushed = 0
        var conflicts = 0

        for (batch in prepared.mutations.chunked(SyncProtocol.PUSH_BATCH_SIZE)) {
            val body = canonicalBody(deviceId, batch)
            when (val outcome = api.push(body)) {
                is SyncPushOutcome.Success -> {
                    val processed = processResults(ownerUid, batch, outcome.response)
                    pushed += processed.acknowledged
                    conflicts += processed.conflicts
                }

                // Falha de transporte: **nada** é apagado e nada é bloqueado. As entradas
                // continuam pendentes, e o reenvio é seguro pelo `clientMutationId`.
                SyncPushOutcome.Network -> return PushPhase(
                    pushed, conflicts, prepared.deferredDeletes, SyncOutcome.Offline
                )

                SyncPushOutcome.Unavailable -> return PushPhase(
                    pushed, conflicts, prepared.deferredDeletes, SyncOutcome.Unavailable
                )

                SyncPushOutcome.RateLimited -> return PushPhase(
                    pushed, conflicts, prepared.deferredDeletes, SyncOutcome.RateLimited
                )

                SyncPushOutcome.AuthRequired -> return PushPhase(
                    pushed, conflicts, prepared.deferredDeletes, SyncOutcome.AuthRequired
                )

                SyncPushOutcome.NotConfigured -> return PushPhase(
                    pushed, conflicts, prepared.deferredDeletes, SyncOutcome.NotConfigured
                )

                is SyncPushOutcome.Rejected -> return PushPhase(
                    pushed, conflicts, prepared.deferredDeletes, SyncOutcome.Rejected(outcome.code)
                )
            }
        }

        return PushPhase(pushed, conflicts, prepared.deferredDeletes)
    }

    /**
     * O corpo canônico do push.
     *
     * O texto enviado é exatamente o que foi hasheado: o servidor canonicaliza o que recebe e
     * chega ao mesmo `payloadHash` sem que nenhum dos dois precise imitar o formatador de ponto
     * flutuante do outro. É a mesma decisão da T16.4 (`BackupCanonicalJson`).
     */
    private fun canonicalBody(deviceId: String, batch: List<PreparedMutation>): String {
        val request = SyncPushRequestDto(
            deviceId = deviceId,
            mutations = batch.map { mutation ->
                SyncPushMutationDto(
                    clientMutationId = mutation.clientMutationId,
                    entityType = mutation.entityType.name,
                    entitySyncId = mutation.entitySyncId,
                    entitySchemaVersion = mutation.entitySchemaVersion,
                    operation = mutation.operation.name,
                    baseRevision = mutation.baseRevision,
                    payload = mutation.payload
                )
            }
        )
        return BackupCanonicalJson.canonicalize(json.encodeToJsonElement(request))
    }

    /**
     * O que fazer com cada desfecho — **depois** de o servidor responder, nunca antes.
     *
     * Uma entrada só sai de `PENDING` com resultado confiável. Um resultado ausente, ou um status
     * que este app não conhece, deixa a entrada exatamente como estava: reenviar é barato e
     * seguro; apagar uma alteração que talvez não tenha subido não tem conserto.
     */
    private suspend fun processResults(
        ownerUid: String,
        batch: List<PreparedMutation>,
        response: SyncPushResponseDto
    ): ProcessedResults {
        val byId = response.results.associateBy { it.clientMutationId }
        var acknowledged = 0
        var conflicts = 0
        val now = clock()

        for (mutation in batch) {
            val result = byId[mutation.clientMutationId] ?: continue
            when (SyncMutationStatus.from(result.status)) {
                SyncMutationStatus.APPLIED, SyncMutationStatus.ALREADY_APPLIED -> {
                    val revision = result.serverRevision ?: continue
                    transactions.runInTransaction {
                        // A revision conhecida e a saída da fila são gravadas juntas: uma sem a
                        // outra faria o próximo push nascer com `baseRevision` errada.
                        //
                        // Uma exclusão confirmada também guarda a revision do **tombstone**: é ela
                        // que faz o eco daquela mudança, quando ela voltar no pull, ser
                        // reconhecido em vez de reprocessado.
                        metadataDao.upsert(
                            EntitySyncMetadataEntity(
                                ownerUid = ownerUid,
                                entityType = mutation.entityType.name,
                                entitySyncId = mutation.entitySyncId,
                                lastKnownServerRevision = revision,
                                lastSyncedPayloadHash = mutation.payloadHash.ifEmpty { null },
                                lastSyncedAt = now
                            )
                        )
                        outboxDao.acknowledge(ownerUid, mutation.entryIds)
                        conflictDao.clear(
                            ownerUid,
                            mutation.entityType.name,
                            mutation.entitySyncId
                        )
                    }
                    acknowledged++
                }

                SyncMutationStatus.STALE -> {
                    // **A revision conhecida não é atualizada aqui, de propósito.** Gravar
                    // `currentRevision` faria o próximo push nascer com a base do servidor e
                    // sobrescrever a alteração do outro aparelho — que é exatamente o
                    // last-write-wins que esta tarefa proíbe.
                    //
                    // Uma exclusão stale é um conflito **diferente** de uma edição stale: aqui o
                    // usuário mandou apagar e o servidor tem uma versão mais nova que ele nunca
                    // viu. Chamar os dois de "mudança stale" faria a tela oferecer a escolha
                    // errada.
                    block(
                        ownerUid,
                        mutation,
                        if (mutation.operation == SyncOperation.DELETE) {
                            SyncConflictKind.LOCAL_DELETED_REMOTE_MODIFIED
                        } else {
                            SyncConflictKind.STALE_LOCAL_CHANGE
                        },
                        result.status,
                        result.currentRevision,
                        now
                    )
                    conflicts++
                }

                SyncMutationStatus.REMOTE_DELETED -> {
                    // Outro aparelho excluiu esta entidade. A alteração local **não** é aplicada
                    // no servidor (isso a ressuscitaria) e **não** é apagada aqui (isso perderia
                    // o trabalho da pessoa). As duas intenções ficam guardadas até ela escolher.
                    block(
                        ownerUid,
                        mutation,
                        SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED,
                        result.reason ?: result.status,
                        result.currentRevision,
                        now
                    )
                    conflicts++
                }

                SyncMutationStatus.IMMUTABLE_HISTORY_CONFLICT -> {
                    block(
                        ownerUid,
                        mutation,
                        SyncConflictKind.IMMUTABLE_HISTORY,
                        result.status,
                        result.currentRevision,
                        now
                    )
                    conflicts++
                }

                SyncMutationStatus.IDEMPOTENCY_CONFLICT -> {
                    block(
                        ownerUid,
                        mutation,
                        SyncConflictKind.IDEMPOTENCY,
                        result.status,
                        null,
                        now
                    )
                    conflicts++
                }

                SyncMutationStatus.INVALID, SyncMutationStatus.UNSUPPORTED -> {
                    // Reenviar o mesmo produziria a mesma recusa. Sem retry automático (§97).
                    block(
                        ownerUid,
                        mutation,
                        SyncConflictKind.REJECTED_BY_SERVER,
                        result.reason ?: result.status,
                        null,
                        now
                    )
                    conflicts++
                }

                // Status de um servidor mais novo: não confirmamos o que não entendemos.
                SyncMutationStatus.UNKNOWN -> Unit
            }
        }

        return ProcessedResults(acknowledged, conflicts)
    }

    private suspend fun block(
        ownerUid: String,
        mutation: PreparedMutation,
        kind: SyncConflictKind,
        reason: String,
        remoteRevision: Long?,
        now: Long
    ) {
        transactions.runInTransaction {
            // O lado remoto guardado de um conflito anterior é preservado: ele foi obtido de uma
            // página de pull que já passou, e o cursor não volta. Perdê-lo aqui tiraria do usuário
            // justamente a versão entre as quais ele precisa escolher (§57).
            val existing = conflictDao.get(ownerUid, mutation.entityType.name, mutation.entitySyncId)
            outboxDao.block(ownerUid, mutation.entryIds, reason, now)
            conflictDao.upsert(
                SyncConflictEntity(
                    ownerUid = ownerUid,
                    entityType = mutation.entityType.name,
                    entitySyncId = mutation.entitySyncId,
                    kind = kind.name,
                    // Uma tentativa que voltou a falhar volta a esperar o usuário. Se ela ficasse
                    // em `AWAITING_PUSH`, a escolha anterior — que o servidor recusou — bloquearia
                    // a próxima para sempre.
                    status = SyncConflictStatus.PENDING.name,
                    baseRevision = mutation.baseRevision,
                    localPayloadHash = mutation.payloadHash.ifEmpty { null },
                    remoteRevision = remoteRevision ?: existing?.remoteRevision,
                    remoteServerSequence = existing?.remoteServerSequence,
                    remotePayloadHash = existing?.remotePayloadHash,
                    remotePayload = existing?.remotePayload,
                    clientMutationId = mutation.clientMutationId,
                    detectedAt = existing?.detectedAt ?: now
                )
            )
        }
    }

    // ------------------------------------------------------------------ pull

    private suspend fun pullPhase(ownerUid: String): PullPhase {
        var cursor = cursorDao.get(ownerUid)?.lastPulledServerSequence ?: 0
        var applied = 0
        var converged = 0
        var conflicts = 0

        repeat(SyncProtocol.MAX_PULL_PAGES_PER_CYCLE) {
            when (val outcome = api.pull(cursor, SyncProtocol.PULL_PAGE_SIZE)) {
                is SyncPullOutcome.Success -> {
                    val page = outcome.response
                    if (page.changes.isEmpty()) {
                        return PullPhase(cursor, applied, converged, conflicts)
                    }

                    val result = applier.apply(ownerUid, page.changes)
                    applied += result.applied
                    converged += result.converged
                    conflicts += result.conflicts
                    // O cursor só anda quando a transação de apply confirmou. Se ela falhou, ele
                    // fica onde estava e a mesma página volta no próximo ciclo.
                    result.cursor?.let { cursor = it }

                    if (result.stop != null) {
                        return PullPhase(cursor, applied, converged, conflicts, stop = result.stop)
                    }
                    if (!page.hasMore) {
                        return PullPhase(cursor, applied, converged, conflicts)
                    }
                }

                SyncPullOutcome.Network ->
                    return PullPhase(cursor, applied, converged, conflicts, failure = SyncOutcome.Offline)

                SyncPullOutcome.Unavailable ->
                    return PullPhase(cursor, applied, converged, conflicts, failure = SyncOutcome.Unavailable)

                SyncPullOutcome.RateLimited ->
                    return PullPhase(cursor, applied, converged, conflicts, failure = SyncOutcome.RateLimited)

                SyncPullOutcome.AuthRequired ->
                    return PullPhase(cursor, applied, converged, conflicts, failure = SyncOutcome.AuthRequired)

                SyncPullOutcome.NotConfigured ->
                    return PullPhase(cursor, applied, converged, conflicts, failure = SyncOutcome.NotConfigured)

                is SyncPullOutcome.Rejected ->
                    // Inclui `INVALID_CURSOR`. O cursor **não** é zerado em silêncio: recomeçar
                    // sozinho faria o aparelho reprocessar a conta inteira sem ninguém saber.
                    return PullPhase(
                        cursor, applied, converged, conflicts,
                        failure = SyncOutcome.Rejected(outcome.code)
                    )
            }
        }

        return PullPhase(cursor, applied, converged, conflicts)
    }

    private data class PushPhase(
        val pushed: Int = 0,
        val conflicts: Int = 0,
        val deferredDeletes: Int = 0,
        val failure: SyncOutcome? = null
    )

    private data class PullPhase(
        val cursor: Long = 0,
        val applied: Int = 0,
        val converged: Int = 0,
        val conflicts: Int = 0,
        val stop: SyncApplyStop? = null,
        val failure: SyncOutcome? = null
    )

    private data class ProcessedResults(val acknowledged: Int, val conflicts: Int)
}
