package com.example.data.sync

/**
 * Onde uma alteração de domínio declara que mexeu em um agregado sincronizável.
 *
 * O repositório diz **o que** mudou; quando (ou se) isso vira linha na Outbox é decisão do
 * [SyncMutationCoordinator]. A UI não conhece este tipo.
 */
interface SyncMutationScope {

    /**
     * `true` quando a nuvem está associada a uma conta e a intenção será registrada.
     *
     * Serve para que uma chamada cara de resolução de `syncId` possa ser evitada — as sobrecargas
     * com lambda já fazem isso sozinhas, então raramente é necessário consultar diretamente.
     */
    val isRecording: Boolean

    /** O agregado existe e seu estado atual mudou. */
    suspend fun upsert(entityType: SyncEntityType, entitySyncId: String)

    /**
     * Idem, resolvendo a identidade só se ela for realmente registrada.
     *
     * `null` significa "o agregado não existe mais / não é sincronizável" e não registra nada.
     */
    suspend fun upsert(entityType: SyncEntityType, entitySyncId: suspend () -> String?)

    /** O agregado foi removido localmente. */
    suspend fun delete(entityType: SyncEntityType, entitySyncId: String)

    /** Idem, resolvendo a identidade sob demanda. */
    suspend fun delete(entityType: SyncEntityType, entitySyncId: suspend () -> String?)
}

/**
 * A fronteira transacional entre domínio e Outbox (T16.3).
 *
 * ```text
 * UI → ViewModel/UseCase → Repository → coordinator.mutate {
 *                                          BEGIN TRANSACTION
 *                                          alterar entidade        (dado de domínio)
 *                                          upsert(...)/delete(...) (intenção de sync)
 *                                          COMMIT
 *                                        }
 * ```
 *
 * Um coordenador, e não código de Outbox copiado em cada repositório: senão o repositório A, o B e
 * o C teriam três versões levemente diferentes da mesma regra, e a terceira esqueceria a
 * transação.
 *
 * ## As três garantias
 *
 * 1. **Atomicidade.** A entrada é gravada dentro da transação da alteração. Falhou o domínio,
 *    falhou a Outbox, ou o processo morreu no meio — nenhum dos dois persiste.
 * 2. **Regra de domínio rejeitou, nada acontece.** Se o bloco lançar (ou simplesmente não declarar
 *    mutação nenhuma), não há intenção de sync para uma operação que não aconteceu.
 * 3. **Nuvem desligada não produz fila.** Sem conta associada, nenhuma linha nasce — o primeiro
 *    backup da T16.4 é um snapshot completo, não a reprodução de um histórico de mutações.
 */
class SyncMutationCoordinator(
    private val transactions: TransactionRunner,
    private val outboxDao: SyncOutboxDao?,
    private val scopeProvider: CloudSyncScopeProvider,
    private val idGenerator: IdGenerator = RandomUuidIdGenerator,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Avisado **depois do commit** quando alguma intenção foi realmente registrada (T16.6).
     *
     * É o gancho que faz uma alteração local agendar um ciclo de sync — e ele fica aqui, e não em
     * cada repositório, porque este é o único lugar que sabe se a mutação foi mesmo gravada.
     *
     * Depois do commit de propósito: agendar dentro da transação avisaria sobre uma alteração que
     * ainda podia ser desfeita. E é um **agendamento**, nunca uma requisição HTTP — quem decide
     * quando falar com o servidor é o `SyncCoordinator`, não o repositório que salvou o treino.
     *
     * O padrão não faz nada: o Spark sem nuvem continua exatamente como era.
     */
    private val onMutationsRecorded: () -> Unit = {}
) {

    /**
     * Executa [block] e registra, na mesma transação, as mutações que ele declarar.
     *
     * O estado da nuvem é lido **antes** de abrir a transação: consultar DataStore com uma
     * transação Room aberta seria I/O de outra fonte dentro de um lock de banco.
     */
    suspend fun <R> mutate(block: suspend SyncMutationScope.() -> R): R {
        val scope = scopeProvider.current()
        val ownerUid = scope.recordingOwnerUid
        if (ownerUid != null && outboxDao == null) {
            // Falha alta de propósito: nuvem ativa sem Outbox significaria alterações perdidas em
            // silêncio, que é exatamente o defeito que o Transactional Outbox existe para evitar.
            error("Cloud sync is active for a account but no outbox DAO was provided")
        }
        val recording = RecordingScope(ownerUid)
        val result = transactions.runInTransaction {
            val value = recording.block()
            recording.flush()
            value
        }
        // Fora da transação, e só se alguma entrada nasceu: uma operação que não mudou estado não
        // agenda nada.
        if (recording.recorded) onMutationsRecorded()
        return result
    }

    private inner class RecordingScope(private val ownerUid: String?) : SyncMutationScope {

        /**
         * As intenções deste bloco, em ordem de primeiro toque.
         *
         * Um mapa, e não uma lista: uma operação que altera dezenas de linhas do mesmo agregado
         * — reordenar exercícios, aplicar descanso padrão a todas as séries — é **uma** mutação
         * daquele agregado, não dezenas. Reescrever a operação preserva a última intenção
         * (`UPSERT` seguido de `DELETE` no mesmo bloco resulta em `DELETE`).
         */
        private val intents = LinkedHashMap<AggregateKey, SyncOperation>()

        /** `true` quando [flush] chegou a inserir alguma entrada. Lido depois do commit. */
        var recorded: Boolean = false
            private set

        override val isRecording: Boolean get() = ownerUid != null

        override suspend fun upsert(entityType: SyncEntityType, entitySyncId: String) =
            record(entityType, entitySyncId, SyncOperation.UPSERT)

        override suspend fun upsert(entityType: SyncEntityType, entitySyncId: suspend () -> String?) {
            if (!isRecording) return
            entitySyncId()?.let { record(entityType, it, SyncOperation.UPSERT) }
        }

        override suspend fun delete(entityType: SyncEntityType, entitySyncId: String) =
            record(entityType, entitySyncId, SyncOperation.DELETE)

        override suspend fun delete(entityType: SyncEntityType, entitySyncId: suspend () -> String?) {
            if (!isRecording) return
            entitySyncId()?.let { record(entityType, it, SyncOperation.DELETE) }
        }

        private fun record(entityType: SyncEntityType, entitySyncId: String, operation: SyncOperation) {
            if (!isRecording || entitySyncId.isBlank()) return
            intents[AggregateKey(entityType, entitySyncId)] = operation
        }

        /**
         * Grava as intenções — ainda dentro da transação aberta por [mutate].
         *
         * Coalescência: se a última entrada daquele agregado já é uma pendente dizendo a mesma
         * coisa, nada é inserido. Três edições do treino ABC antes de qualquer envio viram um
         * `UPSERT ABC`, e não três — o payload é montado a partir do Room na hora do push, então
         * as três resolveriam para exatamente o mesmo conteúdo.
         *
         * O que a coalescência **não** faz: atravessar operações diferentes (um `DELETE` nunca
         * absorve o `UPSERT` anterior, nem o contrário) e nem tocar em entrada que não esteja
         * `PENDING`. Quando existir envio em andamento (T16.6), reaproveitar uma entrada já
         * despachada quebraria a idempotência que o `clientMutationId` garante.
         */
        suspend fun flush() {
            val uid = ownerUid ?: return
            val dao = outboxDao ?: return
            val now = clock()
            intents.forEach { (key, operation) ->
                val latest = dao.latestFor(uid, key.entityType.name, key.entitySyncId)
                val alreadyRecorded = latest != null &&
                    latest.status == SyncOutboxStatus.PENDING.name &&
                    latest.operation == operation.name
                if (alreadyRecorded) return@forEach
                recorded = true
                dao.insert(
                    SyncOutboxEntryEntity(
                        clientMutationId = idGenerator.newId(),
                        ownerUid = uid,
                        entityType = key.entityType.name,
                        entitySyncId = key.entitySyncId,
                        operation = operation.name,
                        status = SyncOutboxStatus.PENDING.name,
                        createdAt = now
                    )
                )
            }
        }
    }

    private data class AggregateKey(val entityType: SyncEntityType, val entitySyncId: String)

    companion object {

        /**
         * O coordenador do Spark sem nuvem: executa a alteração e não registra nada.
         *
         * É o padrão de todos os repositórios, e o que mantém criação e edição locais funcionando
         * sem conta, sem backend e sem internet.
         */
        fun disabled(): SyncMutationCoordinator = SyncMutationCoordinator(
            transactions = TransactionRunner.Direct,
            outboxDao = null,
            scopeProvider = CloudSyncScopeProvider.Disabled
        )
    }
}
