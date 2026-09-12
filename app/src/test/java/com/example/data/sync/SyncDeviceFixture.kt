package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.CloudDataBindingEntity
import com.example.data.backup.CloudDataBindingScopeProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository

/**
 * Um aparelho Spark completo, em memória, para os testes de sincronização (T16.6).
 *
 * ```text
 * SyncDevice A ─┐
 *               ├─▶ FakeSparkSyncServer (uma conta, um change log)
 * SyncDevice B ─┘
 * ```
 *
 * Cada instância tem **o próprio banco Room**, a própria Outbox, o próprio cursor e o próprio
 * `deviceId` — que é exatamente o que dois celulares da mesma pessoa têm. É essa separação que
 * permite provar convergência de verdade em vez de dois objetos escrevendo no mesmo banco.
 *
 * Os repositórios são os de produção: `WorkoutRepository` e `BodyMeasurementRepository` com o
 * `SyncMutationCoordinator` real, para que uma alteração local produza Outbox pelo mesmo caminho
 * que produz no app.
 */
class SyncDevice(
    val server: FakeSparkSyncServer,
    val ownerUid: String,
    val deviceId: String,
    /**
     * O banco deste aparelho.
     *
     * Recebido de fora quando o teste precisa **recriar o aparelho sobre o mesmo estado durável** —
     * é o que um process death faz: os objetos somem, o banco fica. Por padrão, um banco em
     * memória próprio, que é o que dois celulares diferentes têm.
     */
    val database: AppDatabase = Room
        .inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        )
        .allowMainThreadQueries()
        .build()
) {

    val api: FakeSyncApi = FakeSyncApi(server, ownerUid, deviceId)

    /**
     * O `uid` da sessão do Firebase **agora**, como o app o leria (T16.7.1).
     *
     * Começa igual ao dono do dataset, que é o caso normal. Um teste que troca de conta muda este
     * valor — e o repositório o relê **depois** da resposta remota, que é onde a proteção mora.
     */
    var sessionUid: String? = ownerUid

    /** A mesma trava que backup e restore compartilham no app. */
    val operationLock = CloudOperationLock()

    private val transactions = RoomTransactionRunner(database)

    /**
     * O coordenador de mutações real.
     *
     * Exposto porque o `WorkoutRepository` ainda não tem um caminho de "renomear treino" — a
     * edição de nome acontece hoje por DAO dentro de `coordinator.mutate { }`, que é exatamente o
     * padrão de produção (T16.3). O teste usa o mesmo caminho, e não um atalho que não existe.
     */
    val mutations = SyncMutationCoordinator(
        transactions = transactions,
        outboxDao = database.syncOutboxDao(),
        scopeProvider = CloudDataBindingScopeProvider(database.cloudDataBindingDao())
    )

    val workouts: WorkoutRepository = WorkoutRepository(
        database.workoutDao(),
        syncMutations = mutations
    )

    val measurements: BodyMeasurementRepository = BodyMeasurementRepository(
        dao = database.bodyMeasurementDao(),
        syncMutations = mutations
    )

    val aggregates: SyncAggregateSnapshotBuilder = SyncAggregateSnapshotBuilder(
        workoutDao = database.workoutDao(),
        bodyMeasurementDao = database.bodyMeasurementDao()
    )

    /** O mesmo applier que o ciclo de sync e a resolução de conflito compartilham em produção. */
    val applier: SyncRemoteApplier = SyncRemoteApplier(
        transactions = transactions,
        workoutDao = database.workoutDao(),
        bodyMeasurementDao = database.bodyMeasurementDao(),
        outboxDao = database.syncOutboxDao(),
        metadataDao = database.entitySyncMetadataDao(),
        cursorDao = database.syncCursorDao(),
        conflictDao = database.syncConflictDao(),
        snapshotBuilder = aggregates,
        clock = { CLOCK }
    )

    val repository: SyncRepository = SyncRepository(
        bindingDao = database.cloudDataBindingDao(),
        outboxDao = database.syncOutboxDao(),
        metadataDao = database.entitySyncMetadataDao(),
        cursorDao = database.syncCursorDao(),
        conflictDao = database.syncConflictDao(),
        pushBuilder = SyncPushBuilder(
            outboxDao = database.syncOutboxDao(),
            metadataDao = database.entitySyncMetadataDao(),
            snapshotBuilder = aggregates
        ),
        applier = applier,
        api = api,
        conflictResolver = SyncConflictResolver(
            bindingDao = database.cloudDataBindingDao(),
            outboxDao = database.syncOutboxDao(),
            metadataDao = database.entitySyncMetadataDao(),
            conflictDao = database.syncConflictDao(),
            applier = applier,
            snapshotBuilder = aggregates,
            transactions = transactions,
            clock = { CLOCK }
        ),
        deviceId = { deviceId },
        // A sessão **atual** deste aparelho, relida depois de cada resposta remota (T16.7.1).
        // `sessionUid` é var justamente para que um teste possa trocá-la no meio de uma chamada e
        // provar que a resposta da conta B não entra no dataset da conta A.
        accounts = SyncAccountProvider { sessionUid },
        transactions = transactions,
        operationLock = operationLock,
        clock = { CLOCK }
    )

    /**
     * Adota o dataset para a conta — o que a T16.4 faz com "Ativar backup" e confirmação.
     *
     * O teste chama isto explicitamente porque **login não adota**: sem esta linha, nenhuma
     * mutação nasce e nenhum ciclo de sync acontece.
     */
    suspend fun bind(state: CloudSyncState = CloudSyncState.ENABLED) {
        // O programa comum é o baseline que os dois aparelhos já tinham quando adotaram a conta —
        // no app real ele vem do backup (T16.4) ou do restore (T16.5). Sem ele, um treino que
        // chega do outro aparelho não teria onde morar, e o applier recusaria a página inteira.
        ensureProgram()
        database.cloudDataBindingDao().insertIfAbsent(
            CloudDataBindingEntity(
                ownerUid = ownerUid,
                state = state.name,
                boundAt = CLOCK,
                deviceId = deviceId
            )
        )
    }

    /** O catálogo canônico deste aparelho, que o manifesto instalaria. */
    suspend fun installCanonicalExercise(canonicalId: String, name: String): Long =
        database.workoutDao().insertExercise(
            ExerciseEntity(name = name, canonicalId = canonicalId, isUserCreated = false)
        )

    suspend fun createProgram(name: String = "Programa", syncId: String? = null): Long =
        database.workoutDao().insertProgram(
            if (syncId == null) {
                WorkoutProgramEntity(name = name)
            } else {
                WorkoutProgramEntity(name = name, syncId = syncId)
            }
        )

    /**
     * Um programa vindo de manifesto, como o `ProgramImporter` o cria.
     *
     * O que importa aqui é o par de identidades: `externalId` é a identidade **de conteúdo** e é a
     * mesma em todo aparelho que importou o mesmo manifesto; `syncId` é local e nasce diferente em
     * cada um. É essa combinação que produz a colisão de índice único quando o programa chega pela
     * nuvem.
     */
    suspend fun importProgram(
        externalId: String,
        name: String = "Programa importado",
        syncId: String = SyncIds.random()
    ): Long = database.workoutDao().insertProgram(
        WorkoutProgramEntity(name = name, externalId = externalId, syncId = syncId)
    )

    /** Registra a intenção de enviar um agregado que já existe no banco, pelo caminho real. */
    suspend fun recordUpsert(type: SyncEntityType, entitySyncId: String) {
        mutations.mutate { upsert(type, entitySyncId) }
    }

    suspend fun programBySyncId(syncId: String): WorkoutProgramEntity? =
        database.workoutDao().getProgramBySyncId(syncId)

    suspend fun programCount(): Int = database.workoutDao().getAllProgramsSync().size

    suspend fun sync(currentUid: String? = ownerUid): SyncOutcome = repository.syncNow(currentUid)

    // ------------------------------------------------------------------ alterações locais
    //
    // Sempre pelo caminho real: repositório ou `coordinator.mutate { }`. Nada aqui escreve na
    // Outbox à mão — se escrevesse, os testes provariam o dublê e não o app.

    /**
     * O programa que hospeda os treinos do teste.
     *
     * Criado por DAO e com **a mesma identidade global nos dois aparelhos**: é o equivalente ao
     * baseline que a T16.4/T16.5 estabeleceriam. Ele é cenário, e não o que estes testes medem —
     * o que eles medem é o que acontece com o treino que vive dentro dele.
     */
    suspend fun ensureProgram(): Long {
        val existing = database.workoutDao().getProgramBySyncId(SHARED_PROGRAM_SYNC_ID)
        if (existing != null) return existing.id
        return database.workoutDao().insertProgram(
            WorkoutProgramEntity(name = "Programa", syncId = SHARED_PROGRAM_SYNC_ID)
        )
    }

    suspend fun newTemplate(name: String): String {
        val id = workouts.addTemplate(ensureProgram(), name, name.take(1), 0)
        return database.workoutDao().getTemplateById(id)!!.syncId
    }

    suspend fun renameTemplate(entitySyncId: String, name: String) {
        val template = database.workoutDao().getTemplateBySyncId(entitySyncId)!!
        mutations.mutate {
            // `copy` sobre a linha lida: o `syncId` é imutável e nunca é reescrito (T16.3).
            database.workoutDao().updateTemplate(template.copy(name = name))
            upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
        }
    }

    suspend fun deleteTemplate(entitySyncId: String) {
        val template = database.workoutDao().getTemplateBySyncId(entitySyncId)!!
        workouts.deleteTemplate(template)
    }

    suspend fun templateName(entitySyncId: String): String? =
        database.workoutDao().getTemplateBySyncId(entitySyncId)?.name

    suspend fun newMeasurement(weightKg: Float): String {
        val id = measurements.insertMeasurement(
            com.example.data.local.BodyMeasurementEntity(date = CLOCK, weightKg = weightKg)
        )
        return database.bodyMeasurementDao().getMeasurementByIdSync(id)!!.syncId
    }

    suspend fun measurementWeight(entitySyncId: String): Float? =
        database.bodyMeasurementDao().getMeasurementBySyncId(entitySyncId)?.weightKg

    /** Um exercício criado pelo usuário, pelo caminho real do repositório. */
    suspend fun newCustomExercise(name: String): String {
        workouts.addExercise(name = name, muscle = "Peito")
        return database.workoutDao().getAllExercisesSync()
            .first { it.name == name && it.isUserCreated }
            .syncId!!
    }

    suspend fun addExerciseToTemplate(templateSyncId: String, exerciseLocalId: Long, order: Int = 0) {
        val template = database.workoutDao().getTemplateBySyncId(templateSyncId)!!
        workouts.addExerciseToTemplate(template.id, exerciseLocalId, order)
    }

    /** A ordem dos exercícios do treino, por identidade portátil — nunca por `localId`. */
    suspend fun templateExerciseIds(templateSyncId: String): List<String> {
        val template = database.workoutDao().getTemplateBySyncId(templateSyncId)!!
        return database.workoutDao().getTemplateExercisesWithDetails(template.id).map { item ->
            item.exercise.canonicalId ?: item.exercise.syncId.orEmpty()
        }
    }

    /**
     * Uma sessão concluída, escrita como o motor de execução a escreve.
     *
     * Por DAO dentro de `coordinator.mutate { }`: é o padrão da T16.3 — a sessão inteira é **um**
     * agregado, e concluir registra `UPSERT WORKOUT_SESSION`. O que este helper deliberadamente
     * **não** faz é publicar evento de gamificação: quem faz isso é o `WorkoutEngine`, e o teste
     * mede justamente que receber a sessão em outro aparelho não repete nada disso.
     */
    suspend fun completeSession(exerciseLocalId: Long, weight: Float = 100f): String {
        val session = com.example.data.local.WorkoutSessionEntity(
            templateId = null,
            startedAt = CLOCK,
            finishedAt = CLOCK + 3_600_000,
            status = "COMPLETED",
            templateNameSnapshot = "Treino A"
        )
        mutations.mutate {
            val sessionId = database.workoutDao().insertSession(session)
            val exerciseSessionId = database.workoutDao().insertExerciseSession(
                com.example.data.local.ExerciseSessionEntity(
                    sessionId = sessionId,
                    plannedExerciseId = exerciseLocalId,
                    actualExerciseId = exerciseLocalId,
                    exerciseNameSnapshot = "Supino Reto",
                    sortOrder = 0,
                    plannedOrder = 0,
                    executionOrder = 0
                )
            )
            database.workoutDao().insertSetLogs(
                listOf(
                    com.example.data.local.SetLogEntity(
                        exerciseSessionId = exerciseSessionId,
                        setNumber = 1,
                        type = "NORMAL",
                        weight = weight,
                        repetitions = 8,
                        completed = true
                    )
                )
            )
            upsert(SyncEntityType.WORKOUT_SESSION, session.syncId)
        }
        return session.syncId
    }

    /**
     * Começa a executar um treino — o estado que a T16.6 protege de alteração remota.
     *
     * Por DAO: o que importa aqui é a **existência** de uma sessão `IN_PROGRESS` apontando para
     * aquele template, que é o que o applier consulta.
     */
    suspend fun startWorkout(templateSyncId: String): Long {
        val template = database.workoutDao().getTemplateBySyncId(templateSyncId)!!
        return database.workoutDao().insertSession(
            com.example.data.local.WorkoutSessionEntity(
                templateId = template.id,
                startedAt = CLOCK,
                status = "IN_PROGRESS",
                templateNameSnapshot = template.name
            )
        )
    }

    /** Encerra a execução em andamento, sem publicar nada. */
    suspend fun finishWorkout() {
        val active = database.workoutDao().getActiveSession() ?: return
        database.workoutDao().updateSession(
            active.copy(status = "COMPLETED", finishedAt = CLOCK + 1)
        )
    }

    /** Quantas linhas há numa tabela. Usado para provar **ausência** de efeito colateral. */
    fun rowCount(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            it.moveToFirst()
            it.getInt(0)
        }

    suspend fun reorderTemplateExercises(templateSyncId: String) {
        val template = database.workoutDao().getTemplateBySyncId(templateSyncId)!!
        val reordered = database.workoutDao().getTemplateExercisesWithDetails(template.id)
            .reversed()
            .mapIndexed { index, item -> item.templateExercise.copy(sortOrder = index) }
        workouts.updateTemplateExercises(reordered)
    }

    /**
     * O que o restore da T16.5 faz com o estado de sync, dentro do commit dele: cursor, revision
     * conhecida e conflitos são zerados, porque descreviam o dataset que acabou de ser substituído.
     *
     * O teste usa isto para reproduzir "aparelho restaurou um backup antigo" sem subir a máquina de
     * restore inteira — o que importa aqui é o **estado resultante**, e ele é este.
     */
    suspend fun resetSyncStateAsRestoreDoes() {
        database.syncCursorDao().deleteAll()
        database.entitySyncMetadataDao().deleteAll()
        database.syncConflictDao().deleteAll()
    }

    /** Reinsere um treino com a **mesma identidade global**, como um restore faria. */
    suspend fun restoreTemplate(entitySyncId: String, name: String): Long =
        database.workoutDao().insertTemplate(
            com.example.data.local.WorkoutTemplateEntity(
                programId = ensureProgram(),
                name = name,
                shortIdentifier = name.take(1),
                orderInProgram = 0,
                syncId = entitySyncId
            )
        )

    suspend fun pendingCount(): Int = database.syncOutboxDao().pendingCountFor(ownerUid)

    suspend fun blockedCount(): Int = database.syncOutboxDao().blockedCountFor(ownerUid)

    suspend fun conflicts(): List<SyncConflictEntity> =
        database.syncConflictDao().allFor(ownerUid)

    /** Os conflitos como a tela os veria, pelo caminho real do repositório (T16.7). */
    suspend fun conflictSummaries(): List<SyncConflictSummary> = repository.conflicts(ownerUid)

    /** Resolve pelo caminho real: mesma validação de conta, mesma transação. */
    suspend fun resolve(
        id: SyncConflictId,
        choice: SyncConflictChoice,
        currentUid: String? = ownerUid
    ): SyncConflictResolution = repository.resolveConflict(currentUid, id, choice)

    /** Uma medida apagada pelo caminho real do repositório, que registra `DELETE` na Outbox. */
    suspend fun deleteMeasurement(entitySyncId: String) {
        val measurement = database.bodyMeasurementDao().getMeasurementBySyncId(entitySyncId)!!
        measurements.deleteMeasurement(measurement)
    }

    /** Um exercício personalizado apagado pelo caminho real do repositório. */
    suspend fun deleteCustomExercise(entitySyncId: String) {
        val exercise = database.workoutDao().getExerciseBySyncId(entitySyncId)!!
        workouts.deleteExercise(exercise)
    }

    suspend fun templateExists(entitySyncId: String): Boolean =
        database.workoutDao().getTemplateBySyncId(entitySyncId) != null

    suspend fun measurementExists(entitySyncId: String): Boolean =
        database.bodyMeasurementDao().getMeasurementBySyncId(entitySyncId) != null

    suspend fun cursor(): Long =
        database.syncCursorDao().get(ownerUid)?.lastPulledServerSequence ?: 0

    suspend fun revisionOf(type: SyncEntityType, syncId: String): Long? =
        database.entitySyncMetadataDao().get(ownerUid, type.name, syncId)?.lastKnownServerRevision

    fun close() = database.close()

    /**
     * Descarta este "aparelho" sem fechar o banco.
     *
     * Usado quando o banco é de fora e continua vivo — o caso de recriar os objetos sobre o mesmo
     * estado durável.
     */
    fun closeWithoutDatabase() = Unit

    companion object {
        const val CLOCK = 1_700_000_000_000L

        /** O programa comum aos aparelhos do teste — o baseline que os dois já tinham. */
        const val SHARED_PROGRAM_SYNC_ID = "ffffffff-0000-4000-8000-0000000000a1"
    }
}
