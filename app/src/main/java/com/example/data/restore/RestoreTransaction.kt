package com.example.data.restore

import com.example.data.backup.CloudDataBindingDao
import com.example.data.backup.CloudDataBindingEntity
import com.example.data.local.BodyMeasurementDao
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.CheckInEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.data.local.SetLogEntity
import com.example.data.local.WeeklyGoalDao
import com.example.data.local.WeeklyGoalHistoryEntity
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.CloudSyncState
import com.example.data.sync.TransactionRunner
import com.example.data.sync.dto.ExerciseRefDto

/**
 * A substituição do dataset local, em **uma** transação (T16.5).
 *
 * ```text
 * BEGIN
 *   apaga o dataset pessoal          (catálogo canônico intacto)
 *   insere os agregados restaurados  (na ordem das dependências)
 *   reconstrói as relações           (por identidade portátil, nunca por localId antigo)
 *   grava o vínculo com a conta
 *   zera a Outbox
 * COMMIT
 * ```
 *
 * Qualquer falha aqui dentro faz o Room desfazer tudo: o aparelho volta a ter exatamente o dataset
 * que tinha. É por isso que a limpeza e a inserção **precisam** estar na mesma transação — apagar
 * em uma e inserir em outra transformaria um erro de escrita em perda total.
 *
 * ## Este é o `MutationOrigin.RESTORE` do Spark
 *
 * A escrita acontece **fora** do `SyncMutationCoordinator`, de propósito e em um único lugar. Pelo
 * coordenador, restaurar 200 sessões registraria 200 `UPSERT` na Outbox — e o app mandaria de volta
 * ao servidor exatamente o snapshot que acabou de receber dele. O que a Outbox precisa dizer depois
 * de um restore é o contrário: *nada mudou desde este snapshot*. Por isso ela é zerada aqui dentro,
 * como parte do mesmo commit.
 *
 * Um teste estrutural cobre isso: depois de um restore, `sync_outbox` está vazia.
 *
 * ## Nenhum efeito colateral de domínio
 *
 * As escritas passam por DAOs, não por `WorkoutRepository`/`WorkoutEngine`. Quem publica evento de
 * gamificação no Spark é o motor de execução, quando o usuário termina uma série ou um treino —
 * inserir uma sessão concluída **não** publica nada. Restaurar histórico não dá XP, não desbloqueia
 * conquista, não celebra recorde e não emite notificação.
 *
 * ## O que ela não faz
 *
 * Não toca no DataStore: não existe transação atravessando Room e DataStore, e fingir que existe
 * seria pior do que assumir a limitação. As preferências são uma fase própria, depois do commit
 * ([RestorePhase.PREFERENCES_APPLIED]), com recuperação própria.
 */
class RestoreTransaction(
    private val transactions: TransactionRunner,
    private val restoreDao: RestoreDao,
    private val workoutDao: WorkoutDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val weeklyGoalDao: WeeklyGoalDao,
    private val bindingDao: CloudDataBindingDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Aplica [plan] e devolve quantas linhas de cada agregado foram escritas.
     *
     * [binding] decide o que acontece com o vínculo do dataset. Um restore de verdade estabelece
     * (ou confirma) o vínculo com a conta dona do backup; um rollback restaura o vínculo que
     * existia antes — inclusive quando "antes" era *nenhum*.
     */
    suspend fun apply(plan: RestorePlan, binding: RestoreBindingOutcome): RestoreApplied =
        transactions.runInTransaction {
            clearPersonalDataset()

            val customExerciseIds = insertCustomExercises(plan)
            val programIds = insertPrograms(plan)
            val templateIds = insertTemplates(plan, programIds, customExerciseIds)
            val sessionIds = insertSessions(plan, templateIds, customExerciseIds)
            insertCheckIns(plan, sessionIds)
            insertMeasurements(plan)
            insertOverrides(plan, customExerciseIds)
            insertWeeklyGoals(plan)

            applyBinding(binding)

            // A fila só é zerada aqui — dentro do commit que substitui o dataset que ela descrevia.
            restoreDao.deleteAllOutboxEntries()

            // E o estado de sincronização junto (T16.6): revision conhecida, cursor e conflitos
            // descreviam o dataset que acabou de ser substituído. Ver `RestoreDao`.
            restoreDao.deleteAllSyncEntityMetadata()
            restoreDao.deleteAllSyncCursors()
            restoreDao.deleteAllSyncConflicts()

            RestoreApplied(
                programs = programIds.size,
                templates = templateIds.size,
                completedSessions = sessionIds.size,
                customExercises = customExerciseIds.size,
                bodyMeasurements = plan.snapshot.measurements.size,
                checkIns = plan.snapshot.checkIns.size,
                exerciseOverrides = plan.snapshot.overrides.size,
                weeklyGoals = plan.snapshot.weeklyGoals.size
            )
        }

    /**
     * Apaga o dataset pessoal — e nada além dele.
     *
     * Filhos antes de pais, mesmo com `ON DELETE CASCADE`: o cascade existe e funciona, mas a ordem
     * explícita é o que faz esta função ser legível como uma lista do que sai do aparelho.
     */
    private suspend fun clearPersonalDataset() {
        restoreDao.deleteAllSetLogs()
        restoreDao.deleteAllExerciseSessions()
        restoreDao.deleteAllWorkoutSessions()
        restoreDao.deleteAllCheckIns()

        restoreDao.deleteAllTemplateExercises()
        restoreDao.deleteAllTemplates()
        restoreDao.deleteAllPrograms()

        restoreDao.deleteAllBodyMeasurements()
        restoreDao.deleteAllWeeklyGoals()
        restoreDao.deleteAllExerciseOverrides()

        // Depois dos treinos e das customizações: enquanto elas existirem, a chave estrangeira
        // `workout_template_exercises.exerciseId` (RESTRICT) impede remover o exercício.
        restoreDao.deleteAllCustomExercises()

        // Derivados do histórico que acabou de ser substituído.
        restoreDao.deleteAllPersonalRecords()
        restoreDao.deleteAllGamificationEvents()
        restoreDao.deleteAllXpTransactions()
        restoreDao.deleteAllAchievementUnlocks()
    }

    /**
     * Os exercícios criados pelo usuário. Primeiros, porque treinos e customizações os referenciam.
     *
     * O `localId` é novo — atribuído por este banco — e o `syncId` é preservado: é ele que
     * identifica o mesmo exercício em qualquer aparelho. Reaproveitar o `localId` do celular de
     * origem seria depender de um número que não significa nada aqui.
     */
    private suspend fun insertCustomExercises(plan: RestorePlan): Map<String, Long> {
        val ids = mutableMapOf<String, Long>()
        plan.snapshot.customExercises.forEach { dto ->
            val localId = workoutDao.insertExercise(
                ExerciseEntity(
                    name = dto.name,
                    description = dto.description,
                    primaryMuscle = dto.primaryMuscle,
                    equipment = dto.equipment,
                    active = dto.active,
                    rirEnabled = dto.rirEnabled,
                    isBodyweight = dto.isBodyweight,
                    isUserCreated = true,
                    origin = USER_ORIGIN,
                    syncId = dto.syncId
                )
            )
            ids[dto.syncId] = localId
        }
        return ids
    }

    private suspend fun insertPrograms(plan: RestorePlan): Map<String, Long> {
        val ids = mutableMapOf<String, Long>()
        plan.snapshot.programs.forEach { dto ->
            val localId = workoutDao.insertProgram(
                WorkoutProgramEntity(
                    name = dto.name,
                    description = dto.description,
                    isCurrent = dto.isCurrent,
                    externalId = dto.externalId,
                    contentVersion = dto.contentVersion,
                    syncId = dto.syncId
                )
            )
            ids[dto.syncId] = localId
        }
        return ids
    }

    /**
     * Os treinos e seus exercícios.
     *
     * A ordem dos exercícios vem de `position`, que é dado de domínio no contrato — e não da ordem
     * em que o JSON os listou nem do `id` que o banco vai atribuir. É isso que preserva um reorder
     * não trivial ao atravessar dois aparelhos.
     */
    private suspend fun insertTemplates(
        plan: RestorePlan,
        programIds: Map<String, Long>,
        customExerciseIds: Map<String, Long>
    ): Map<String, Long> {
        val ids = mutableMapOf<String, Long>()
        plan.snapshot.templates.forEach { dto ->
            val programId = programIds[dto.programSyncId]
                // Não acontece: a validação semântica já exigiu o programa dentro do snapshot.
                ?: throw RestoreException(RestoreError.INVALID_BACKUP, "programa não resolvido")

            val templateId = workoutDao.insertTemplate(
                WorkoutTemplateEntity(
                    programId = programId,
                    name = dto.name,
                    shortIdentifier = dto.shortIdentifier,
                    orderInProgram = dto.orderInProgram,
                    dayOfWeek = dto.dayOfWeek,
                    syncId = dto.syncId
                )
            )
            ids[dto.syncId] = templateId

            dto.exercises.sortedBy { it.position }.forEach { entry ->
                val exerciseId = resolveExercise(plan, customExerciseIds, entry.exercise)
                    ?: throw RestoreException(
                        RestoreError.MISSING_CATALOG_EXERCISE,
                        "exercício de treino não resolvido"
                    )
                workoutDao.insertTemplateExercise(
                    WorkoutTemplateExerciseEntity(
                        templateId = templateId,
                        exerciseId = exerciseId,
                        sortOrder = entry.position,
                        targetSets = entry.targetSets,
                        minReps = entry.minReps,
                        maxReps = entry.maxReps,
                        restDurationSeconds = entry.restDurationSeconds,
                        plannedWeight = entry.plannedWeight,
                        machineLabel = entry.machineLabel,
                        notes = entry.notes
                    )
                )
            }
        }
        return ids
    }

    /**
     * O histórico, recriado **fielmente**.
     *
     * Nada é recalculado: duração, carga, repetições, RPE/RIR, `startedAt` e `finishedAt` entram
     * como estão no snapshot. Uma sessão concluída é o registro do que aconteceu; "corrigir" um
     * valor aqui seria reescrever o passado no caminho menos visível do app.
     *
     * A referência de exercício é resolvida quando dá, e fica nula quando não dá — sem inventar
     * substituto. A sessão continua íntegra porque carrega `exerciseNameSnapshot`, que é
     * exatamente o campo que existe para isso (T16.3).
     */
    private suspend fun insertSessions(
        plan: RestorePlan,
        templateIds: Map<String, Long>,
        customExerciseIds: Map<String, Long>
    ): Map<String, Long> {
        val ids = mutableMapOf<String, Long>()
        plan.snapshot.sessions.forEach { dto ->
            val sessionId = workoutDao.insertSession(
                WorkoutSessionEntity(
                    // Histórico sobrevive ao treino que o originou: se o template não veio no
                    // snapshot, a sessão entra sem ele em vez de ser recusada.
                    templateId = dto.templateSyncId?.let { templateIds[it] },
                    startedAt = dto.startedAt,
                    finishedAt = dto.finishedAt,
                    status = dto.status,
                    notes = dto.notes,
                    templateNameSnapshot = dto.templateNameSnapshot,
                    syncId = dto.syncId
                )
            )
            ids[dto.syncId] = sessionId

            dto.exercises.sortedBy { it.executionOrder }.forEach { exercise ->
                val exerciseSessionId = workoutDao.insertExerciseSession(
                    ExerciseSessionEntity(
                        sessionId = sessionId,
                        plannedExerciseId = exercise.plannedExercise
                            ?.let { resolveExercise(plan, customExerciseIds, it) },
                        actualExerciseId = exercise.actualExercise
                            ?.let { resolveExercise(plan, customExerciseIds, it) },
                        exerciseNameSnapshot = exercise.exerciseNameSnapshot,
                        sortOrder = exercise.plannedOrder,
                        plannedOrder = exercise.plannedOrder,
                        executionOrder = exercise.executionOrder,
                        startedAt = exercise.startedAt,
                        finishedAt = exercise.finishedAt,
                        notes = exercise.notes,
                        replacementReason = exercise.replacementReason,
                        machineLabelSnapshot = exercise.machineLabelSnapshot,
                        primaryMuscleSnapshot = exercise.primaryMuscleSnapshot,
                        restDurationSecondsSnapshot = exercise.restDurationSecondsSnapshot
                    )
                )

                if (exercise.sets.isNotEmpty()) {
                    workoutDao.insertSetLogs(
                        exercise.sets.sortedBy { it.setNumber }.map { set ->
                            SetLogEntity(
                                exerciseSessionId = exerciseSessionId,
                                setNumber = set.setNumber,
                                type = set.type,
                                weight = set.weight,
                                repetitions = set.repetitions,
                                completed = set.completed,
                                startedAt = set.startedAt,
                                finishedAt = set.finishedAt,
                                rpe = set.rpe,
                                rir = set.rir,
                                durationSeconds = set.durationSeconds
                            )
                        }
                    )
                }
            }
        }
        return ids
    }

    private suspend fun insertCheckIns(plan: RestorePlan, sessionIds: Map<String, Long>) {
        plan.snapshot.checkIns.forEach { dto ->
            workoutDao.insertCheckIn(
                CheckInEntity(
                    checkInTime = dto.checkInTime,
                    checkOutTime = dto.checkOutTime,
                    gymName = dto.gymName,
                    // Pode apontar para uma sessão que o backup não inclui (uma não concluída):
                    // o contrato prevê isso, e o check-in continua válido sem o vínculo.
                    sessionId = dto.sessionSyncId?.let { sessionIds[it] },
                    syncId = dto.syncId
                )
            )
        }
    }

    private suspend fun insertMeasurements(plan: RestorePlan) {
        plan.snapshot.measurements.forEach { dto ->
            bodyMeasurementDao.insertMeasurement(
                BodyMeasurementEntity(
                    date = dto.date,
                    createdAt = dto.createdAt,
                    weightKg = dto.weightKg,
                    heightCm = dto.heightCm,
                    bodyFatPercentage = dto.bodyFatPercentage,
                    waistCm = dto.waistCm,
                    abdomenCm = dto.abdomenCm,
                    chestCm = dto.chestCm,
                    leftArmCm = dto.leftArmCm,
                    rightArmCm = dto.rightArmCm,
                    leftThighCm = dto.leftThighCm,
                    rightThighCm = dto.rightThighCm,
                    calfCm = dto.calfCm,
                    hipCm = dto.hipCm,
                    syncId = dto.syncId
                )
            )
        }
    }

    /**
     * As customizações de exercício.
     *
     * `customPhotoUri` não é restaurado: ele é um `content://` do aparelho que fez o backup, não
     * viaja no snapshot e não resolveria aqui. A foto local **deste** aparelho, quando existe, é
     * dado local — e o restore não a apaga silenciosamente por causa disso.
     */
    private suspend fun insertOverrides(plan: RestorePlan, customExerciseIds: Map<String, Long>) {
        plan.snapshot.overrides.forEach { dto ->
            val exerciseId = resolveExercise(plan, customExerciseIds, dto.exercise)
                ?: throw RestoreException(
                    RestoreError.MISSING_CATALOG_EXERCISE,
                    "exercício customizado não resolvido"
                )
            workoutDao.insertOrUpdateOverride(
                ExerciseUserOverrideEntity(
                    exerciseId = exerciseId,
                    displayName = dto.displayName,
                    notes = dto.notes,
                    customPhotoUri = null,
                    defaultRestSeconds = dto.defaultRestSeconds,
                    updatedAt = dto.updatedAt
                )
            )
        }
    }

    private suspend fun insertWeeklyGoals(plan: RestorePlan) {
        plan.snapshot.weeklyGoals.forEach { dto ->
            weeklyGoalDao.insertGoal(
                WeeklyGoalHistoryEntity(
                    effectiveFromWeekStartEpochDay = dto.effectiveFromWeekStartEpochDay,
                    goal = dto.goal,
                    createdAt = dto.createdAt
                )
            )
        }
    }

    /**
     * O vínculo do dataset, como **parte do commit**.
     *
     * Ele nasce (ou é confirmado) junto com os dados restaurados, e não antes: um vínculo gravado
     * antes de a aplicação dar certo apontaria a conta nova para o dataset velho. Se a transação
     * falhar, o vínculo anterior continua exatamente como estava.
     */
    private suspend fun applyBinding(outcome: RestoreBindingOutcome) {
        when (outcome) {
            is RestoreBindingOutcome.Bind -> {
                bindingDao.insertIfAbsent(
                    CloudDataBindingEntity(
                        ownerUid = outcome.ownerUid,
                        state = CloudSyncState.ENABLED.name,
                        boundAt = clock(),
                        deviceId = outcome.deviceId
                    )
                )
                // O dataset restaurado É um snapshot que o servidor já confirmou. Registrar isso
                // não cria backup nenhum: nenhuma tentativa nasce, nada sobe, e a UI passa a
                // mostrar a data **do servidor** daquele backup.
                bindingDao.markBackupSucceeded(
                    ownerUid = outcome.ownerUid,
                    state = CloudSyncState.ENABLED.name,
                    backupId = outcome.backupId,
                    backupAt = outcome.backupCreatedAt
                )
            }

            RestoreBindingOutcome.Unbind -> bindingDao.deleteBinding()

            RestoreBindingOutcome.Preserve -> Unit
        }
    }

    /**
     * A identidade portátil de um exercício traduzida para o `localId` **deste** banco.
     *
     * Catálogo: pelo `canonicalId`, já resolvido no plano. Criado pelo usuário: pelo `syncId`, que
     * acabou de ser inserido nesta transação. Nunca por nome, nunca por aproximação.
     */
    private fun resolveExercise(
        plan: RestorePlan,
        customExerciseIds: Map<String, Long>,
        ref: ExerciseRefDto
    ): Long? = when (ref.kind) {
        ExerciseRefDto.CANONICAL -> plan.canonicalExerciseIds[ref.id]
        else -> customExerciseIds[ref.id]
    }

    private companion object {
        /** O mesmo `origin` que a criação manual de exercício usa. */
        const val USER_ORIGIN = "USER"
    }
}

/** Quantas linhas o restore escreveu, por agregado. Serve à verificação, não à UI. */
data class RestoreApplied(
    val programs: Int,
    val templates: Int,
    val completedSessions: Int,
    val customExercises: Int,
    val bodyMeasurements: Int,
    val checkIns: Int,
    val exerciseOverrides: Int,
    val weeklyGoals: Int
)

/** O que a transação faz com o vínculo do dataset. */
sealed interface RestoreBindingOutcome {

    /**
     * O dataset passa a pertencer (ou continua pertencendo) a [ownerUid].
     *
     * Usado no restore de verdade: os dados restaurados são inequivocamente daquela conta, porque
     * vieram do backup dela.
     */
    data class Bind(
        val ownerUid: String,
        val deviceId: String,
        val backupId: String,
        val backupCreatedAt: Long
    ) : RestoreBindingOutcome

    /**
     * O vínculo é removido.
     *
     * Só no rollback de um dataset que era **sem dono** antes da tentativa: desfazer a restauração
     * e deixar o vínculo para trás faria a conta apontar para dados que não são do backup dela.
     */
    data object Unbind : RestoreBindingOutcome

    /** O vínculo fica exatamente como está. É o rollback de um dataset que já tinha dono. */
    data object Preserve : RestoreBindingOutcome
}
