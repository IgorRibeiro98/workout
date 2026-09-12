package com.example.domain.engine

import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import com.example.domain.gamification.GamificationEventPublisher
import com.example.domain.gamification.GamificationEvents
import com.example.domain.gamification.model.GamificationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Os dois descansos que valem para o exercício em foco: entre séries e depois do exercício.
 *
 * Existe para que a regra viva num lugar só ([WorkoutEngine.resolveRestRecommendation]) e a tela
 * não precise de uma chamada suspensa no meio da composição para saber o que recomendar.
 */
data class RestRecommendation(
    val betweenSets: Int,
    val afterExercise: Int
)

data class SyncResult(
    val updatedCount: Int,
    val skippedCompletedCount: Int = 0,
    val skippedDifferentTypeCount: Int = 0,
    val message: String = ""
)

class WorkoutEngine(
    val dao: WorkoutDao,
    private val settingsManager: SettingsManager,
    // `SupervisorJob` não é decoração (auditoria 2026-09-12): sem ele, uma exceção não capturada
    // em qualquer `launch` futuro cancela o escopo inteiro, e os `launch` seguintes viram no-ops
    // silenciosos — o motor continuaria de pé, aparentemente saudável, sem executar mais nada.
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    // O motor apenas informa fatos. Quem os interpreta (histórico, XP, conquistas) vive fora daqui.
    private val gamificationEvents: GamificationEventPublisher = GamificationEventPublisher.NoOp,
    // Fronteira transacional com a Outbox (T16.3). O padrão não registra nada: executar treino
    // continua sendo operação local, sem conta e sem rede.
    private val syncMutations: SyncMutationCoordinator = SyncMutationCoordinator.disabled()
) {

    val activeSessionFlow: Flow<WorkoutSessionEntity?> = dao.getActiveSessionFlow()
    val activeSessionWithDetailsFlow: Flow<SessionWithDetails?> = dao.getActiveSessionWithDetailsFlow()
    val activeCheckInFlow: Flow<CheckInEntity?> = dao.getActiveCheckInFlow()

    private val _restTimerTarget = MutableStateFlow<Long?>(null)
    val restTimerTarget: Flow<Long?> = _restTimerTarget
    
    val activeResolvedExercises: Flow<List<com.example.domain.model.ResolvedExercise>> = 
        kotlinx.coroutines.flow.combine(
            dao.getActiveExercises(), 
            dao.getAllOverridesFlow(),
            settingsManager.showGifsFlow
        ) { exercises, overrides, showGifs ->
            com.example.domain.engine.ExerciseResolver.resolveAll(exercises, overrides.associateBy { it.exerciseId }, showGifs)
        }

    /**
     * O exercício em foco, resolvido — e **só** ele (auditoria 2026-09-12).
     *
     * [activeResolvedExercises] continua existindo para quem precisa da lista inteira, mas a tela
     * de execução usava aquele fluxo para achar **um** exercício: cada emissão de `exercises`,
     * `overrides` ou `showGifs` rodava `resolveAll` sobre o catálogo completo para descartar tudo
     * menos uma linha. Aqui a resolução acontece uma vez, sobre o exercício certo.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resolvedExerciseFlow(exerciseIdFlow: Flow<Long?>): Flow<com.example.domain.model.ResolvedExercise?> =
        exerciseIdFlow.distinctUntilChanged().flatMapLatest { exerciseId ->
            if (exerciseId == null) {
                flowOf(null)
            } else {
                combine(
                    dao.getActiveExercises(),
                    dao.getAllOverridesFlow(),
                    settingsManager.showGifsFlow
                ) { exercises, overrides, showGifs ->
                    val exercise = exercises.firstOrNull { it.id == exerciseId }
                    if (exercise == null) {
                        null
                    } else {
                        com.example.domain.engine.ExerciseResolver.resolve(
                            exercise,
                            overrides.firstOrNull { it.exerciseId == exerciseId },
                            showGifs
                        )
                    }
                }.distinctUntilChanged()
            }
        }

    /**
     * Serializa as transições de ciclo de vida da sessão.
     *
     * `startSession` checava "existe sessão ativa?" e só depois inseria. Dois toques rápidos em
     * "iniciar treino" — ou um toque e uma retomada — passavam os dois pela checagem e criavam
     * duas sessões `IN_PROGRESS`; o `LIMIT 1` das consultas escondia a segunda, que ficava órfã
     * para sempre.
     */
    private val sessionLifecycleMutex = Mutex()

    init {
        // Process restoration check: restore timer if valid active session and deadline in future
        coroutineScope.launch {
            try {
                val hasSavedDeadline = settingsManager.restTimerDeadlineFlow.firstOrNull() != null
                if (hasSavedDeadline) {
                    restoreTimerState()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Perder a restauração do temporizador não pode impedir o app de abrir; o que não
                // pode é a falha sumir. `printStackTrace` não aparece em nenhum filtro de log.
                android.util.Log.w(TAG, "restauração do temporizador de descanso falhou", e)
            }
        }
    }

    suspend fun restoreTimerState(): Boolean {
        val deadline = settingsManager.restTimerDeadlineFlow.firstOrNull()
        val savedSessionId = settingsManager.restTimerSessionIdFlow.firstOrNull()
        val savedExerciseSessionId = settingsManager.restTimerExerciseSessionIdFlow.firstOrNull()
        val savedTimerType = settingsManager.restTimerTypeFlow.firstOrNull()

        val activeSession = dao.getActiveSession()
        val now = System.currentTimeMillis()

        val validTimerTypes = setOf("REST_SET", "REST_EXERCISE", "CUSTOM")
        val isTimerTypeValid = savedTimerType != null && savedTimerType in validTimerTypes

        val isExerciseSessionValid = if (savedExerciseSessionId != null && activeSession != null) {
            val exSession = dao.getExerciseSessionById(savedExerciseSessionId)
            exSession != null && exSession.sessionId == activeSession.id
        } else {
            false
        }

        val isSessionValid = activeSession != null &&
                activeSession.status == SessionStatus.IN_PROGRESS.name &&
                savedSessionId != null &&
                savedSessionId == activeSession.id

        val isDeadlineValid = deadline != null && deadline > now

        return if (isSessionValid && isExerciseSessionValid && isTimerTypeValid && isDeadlineValid) {
            _restTimerTarget.value = deadline
            true
        } else {
            skipRestTimer()
            false
        }
    }

    suspend fun getActiveExerciseNameForTimer(): String? {
        val savedExerciseSessionId = settingsManager.restTimerExerciseSessionIdFlow.firstOrNull() ?: return null
        val exSession = dao.getExerciseSessionById(savedExerciseSessionId) ?: return null
        return exSession.exerciseNameSnapshot
    }

    suspend fun manualCheckIn(gymName: String? = null) {
        // Já existe check-in aberto: nada muda, e por isso nada é registrado. Uma operação que não
        // altera estado não é uma mutação a sincronizar.
        if (dao.getActiveCheckIn() != null) return
        val checkIn = CheckInEntity(checkInTime = System.currentTimeMillis(), gymName = gymName)
        syncMutations.mutate {
            dao.insertCheckIn(checkIn)
            upsert(SyncEntityType.CHECK_IN, checkIn.syncId)
        }
    }

    suspend fun manualCheckOut() {
        val active = dao.getActiveCheckIn() ?: return
        syncMutations.mutate {
            dao.updateCheckIn(active.copy(checkOutTime = System.currentTimeMillis()))
            upsert(SyncEntityType.CHECK_IN, active.syncId)
        }
    }

    suspend fun startRestTimer(
        durationSeconds: Int,
        workoutSessionId: Long? = null,
        exerciseSessionId: Long? = null,
        timerType: String = "REST_SET"
    ) {
        val target = System.currentTimeMillis() + (durationSeconds * 1000L)
        _restTimerTarget.value = target
        settingsManager.setRestTimerState(
            deadlineMs = target,
            workoutSessionId = workoutSessionId,
            exerciseSessionId = exerciseSessionId,
            timerType = timerType
        )
    }

    /**
     * Estende o descanso em andamento.
     *
     * Sem descanso em andamento, não faz nada e devolve `-1` (auditoria 2026-09-12). A versão
     * anterior usava `?: System.currentTimeMillis()` e **criava** um descanso de 30 s do nada em
     * dois casos reais: um "+30s" tocado numa notificação obsoleta, e o instante entre a morte do
     * processo e o fim do `restoreTimerState()` assíncrono do `init`. Pior, gravava o prazo sem
     * `sessionId` nem tipo — um estado que o próximo `restoreTimerState` descarta, depois de a
     * tela já ter mostrado um descanso que ninguém pediu.
     */
    suspend fun adjustRestTimer(secondsToAdd: Int): Long {
        val currentTarget = _restTimerTarget.value ?: return NO_ACTIVE_REST_TIMER
        val newTarget = (currentTarget + (secondsToAdd * 1000L)).coerceAtLeast(System.currentTimeMillis())
        _restTimerTarget.value = newTarget
        settingsManager.setRestTimerDeadline(newTarget)
        return newTarget
    }

    suspend fun skipRestTimer() {
        _restTimerTarget.value = null
        settingsManager.setRestTimerState(null)
    }

    suspend fun getLastExecutionSetsForExercise(exerciseId: Long): List<SetLogEntity> {
        return dao.getLastExecutionSetsForExercise(exerciseId)
    }

    suspend fun getExerciseExecutionContext(exerciseId: Long, templateId: Long?): com.example.domain.workout.execution.ExerciseExecutionContext {
        val lastSets = dao.getLastExecutionSetsForExercise(exerciseId)
        val lastFinishedAt = dao.getLastSessionFinishedAtForExercise(exerciseId)
        val exercise = dao.getExerciseById(exerciseId)
        val resolvedExercise = exercise?.let { ExerciseResolver.resolve(it, null) }
        val isDuration = resolvedExercise?.executionMode == com.example.domain.model.ExerciseExecutionMode.DURATION || lastSets.any { it.isDurationMode }
        val daysAgo = if (lastFinishedAt != null && lastFinishedAt > 0) {
            val diffMs = System.currentTimeMillis() - lastFinishedAt
            (diffMs / (1000L * 60 * 60 * 24)).coerceAtLeast(0L)
        } else {
            null
        }

        val lastPerformance = if (lastSets.isNotEmpty()) {
            val representativeSet = lastSets.maxByOrNull { it.weight } ?: lastSets.first()
            com.example.domain.workout.execution.PerformanceHistory(
                weight = representativeSet.weight,
                reps = representativeSet.repetitions,
                rir = representativeSet.rir,
                timestamp = lastFinishedAt,
                daysAgo = daysAgo,
                completedSets = lastSets,
                isDurationMode = representativeSet.isDurationMode || isDuration
            )
        } else {
            null
        }

        val bestSet = if (!isDuration) dao.getBestSetLogForExercise(exerciseId) else null
        val highestPr = if (!isDuration) dao.getHighestPR(exerciseId, PRType.MAX_WEIGHT.name) else null
        val personalRecord = when {
            bestSet != null -> com.example.domain.workout.execution.PersonalRecord(
                maxWeight = bestSet.weight,
                repsAtMaxWeight = bestSet.repetitions,
                date = lastFinishedAt
            )
            highestPr != null -> com.example.domain.workout.execution.PersonalRecord(
                maxWeight = highestPr.value,
                repsAtMaxWeight = 1,
                date = highestPr.date
            )
            else -> null
        }

        val templateExercise = if (templateId != null) dao.getTemplateExercise(templateId, exerciseId) else null
        val targetReps = if (templateExercise != null && templateExercise.minReps > 0) {
            templateExercise.minReps..templateExercise.maxReps
        } else {
            null
        }
        val suggestedLoad = templateExercise?.plannedWeight ?: lastPerformance?.weight
        val targetSets = templateExercise?.targetSets

        val maxVolumePr = if (!isDuration) dao.getHighestPR(exerciseId, PRType.MAX_VOLUME.name) else null
        val executionCount = dao.getExerciseExecutionCount(exerciseId)

        val summary = if (executionCount > 0 || personalRecord != null) {
            com.example.domain.workout.execution.ExercisePerformanceSummary(
                maxWeight = personalRecord?.maxWeight,
                maxVolume = maxVolumePr?.value,
                totalExecutions = executionCount
            )
        } else {
            null
        }

        return com.example.domain.workout.execution.ExerciseExecutionContext(
            lastPerformance = lastPerformance,
            personalRecord = personalRecord,
            suggestedLoad = suggestedLoad,
            targetReps = targetReps,
            targetSets = targetSets,
            summary = summary,
            isFirstTime = lastPerformance == null && personalRecord == null,
            isDurationMode = isDuration
        )
    }

    /**
     * Replicates the current set's weight and reps to subsequent pending sets of the same SetType.
     * Does NOT touch completed sets, different SetTypes, or RIR/RPE values.
     */
    suspend fun replicateCurrentSet(exerciseSessionId: Long, currentSet: SetLogEntity): SyncResult {
        val allSets = dao.getSetLogsForExerciseSession(exerciseSessionId)
        val eligibleSets = mutableListOf<SetLogEntity>()
        var skippedCompleted = 0
        var skippedType = 0

        for (set in allSets) {
            if (set.setNumber > currentSet.setNumber) {
                if (set.completed) {
                    skippedCompleted++
                } else if (set.type != currentSet.type) {
                    skippedType++
                } else {
                    eligibleSets.add(
                        set.copy(
                            weight = currentSet.weight,
                            repetitions = currentSet.repetitions,
                            rir = currentSet.rir,
                            rpe = currentSet.rpe
                        )
                    )
                }
            }
        }

        if (eligibleSets.isNotEmpty()) {
            dao.updateSetLogs(eligibleSets)
        }

        return SyncResult(
            updatedCount = eligibleSets.size,
            skippedCompletedCount = skippedCompleted,
            skippedDifferentTypeCount = skippedType
        )
    }

    /**
     * Restores weight and reps from the last completed execution of this exercise onto pending current sets.
     * Maps sets by SetType and position order. Does NOT touch completed sets.
     */
    suspend fun restoreLastExecutionSets(exerciseSessionId: Long, actualExerciseId: Long?): SyncResult {
        val exerciseId = actualExerciseId ?: return SyncResult(updatedCount = 0)
        val prevSets = dao.getLastExecutionSetsForExercise(exerciseId)
        if (prevSets.isEmpty()) {
            return SyncResult(updatedCount = 0)
        }

        val currentSets = dao.getSetLogsForExerciseSession(exerciseSessionId)
        if (currentSets.isEmpty()) {
            return SyncResult(updatedCount = 0)
        }

        val prevByType = prevSets.groupBy { it.type }
        val currentByType = currentSets.groupBy { it.type }
        val eligibleSets = mutableListOf<SetLogEntity>()
        var skippedCompleted = 0

        for ((type, currentGroup) in currentByType) {
            val prevGroup = prevByType[type] ?: emptyList()
            if (prevGroup.isEmpty()) continue

            for ((index, set) in currentGroup.withIndex()) {
                if (set.completed) {
                    skippedCompleted++
                    continue
                }
                val sourceSet = prevGroup.getOrNull(index) ?: prevGroup.lastOrNull()
                if (sourceSet != null) {
                    eligibleSets.add(set.copy(weight = sourceSet.weight, repetitions = sourceSet.repetitions))
                }
            }
        }

        if (eligibleSets.isNotEmpty()) {
            dao.updateSetLogs(eligibleSets)
        }

        return SyncResult(
            updatedCount = eligibleSets.size,
            skippedCompletedCount = skippedCompleted
        )
    }

    /**
     * Quanto descanso vale para este exercício — a **única** implementação da regra.
     *
     * Antes da auditoria de 2026-09-12 existiam duas. O motor decidia o descanso entre exercícios
     * por `defaultExerciseRestSeconds` (2 min por padrão); a tela de transição recomendava o
     * `restDurationSecondsSnapshot` do **próximo** exercício, caindo num `90` escrito à mão. Efeito
     * visível: com o temporizador automático ligado o usuário descansava 120 s, e com ele desligado
     * a mesma transição oferecia 60 s do template. O `?: 90` do motor também era código morto —
     * `defaultRestSecondsFlow` já tem 90 como padrão e nunca devolve nulo.
     *
     * Os dois valores são calculados juntos porque a tela precisa dos dois: ela não sabe, no
     * momento em que compõe, se a próxima ação é entre séries ou entre exercícios.
     */
    suspend fun resolveRestRecommendation(
        actualExerciseId: Long?,
        restDurationSecondsSnapshot: Int?
    ): RestRecommendation {
        val override = actualExerciseId?.let { dao.getOverrideForExercise(it) }
        return RestRecommendation(
            betweenSets = restDurationSecondsSnapshot
                ?: override?.defaultRestSeconds
                ?: settingsManager.defaultRestSecondsFlow.first(),
            afterExercise = settingsManager.defaultExerciseRestSecondsFlow.first()
        )
    }

    /**
     * Updates set log and triggers auto rest timer when completed according to hierarchy:
     * 1. ExerciseSession.restDurationSecondsSnapshot
     * 2. ExerciseUserOverride.defaultRestSeconds
     * 3. Settings.defaultExerciseRestSeconds
     * 4. Settings.defaultRestSeconds
     */
    suspend fun updateSet(setLog: SetLogEntity) {
        dao.updateSetLog(setLog)
        if (setLog.completed) {
            val exSession = dao.getExerciseSessionById(setLog.exerciseSessionId)
            val sessionId = exSession?.sessionId

            // Duas contagens no lugar do grafo inteiro da sessão (auditoria 2026-09-12).
            //
            // Cada série concluída carregava `getSessionWithDetails` — sessão, exercícios e todas
            // as séries — para responder a dois booleanos. `id != :excludingSetId` reproduz o
            // `it.completed || it.id == setLog.id` de antes: a série recém-escrita não conta como
            // pendente nem que a escrita ainda não tenha sido enxergada pela leitura. E um
            // exercício **sem séries** continua contando como pendente, como sempre contou — ver
            // `countPendingExerciseSessions`.
            //
            // O `allExercises.isNotEmpty()` da versão antiga não precisa de equivalente: este
            // caminho só roda porque uma série desta sessão acabou de ser concluída, então a sessão
            // tem pelo menos um exercício.
            val pendingExercises = sessionId?.let {
                dao.countPendingExerciseSessions(it, setLog.id)
            }
            val isEntireWorkoutCompleted = pendingExercises == 0

            if (isEntireWorkoutCompleted) {
                // Entire workout completed! No rest timer should start. Cancel any active timer.
                skipRestTimer()
                return
            }

            val autoTimer = settingsManager.autoRestTimerOnSetFlow.firstOrNull() ?: true
            if (autoTimer) {
                // O exercício em foco tem pelo menos uma série (a que acabou de ser concluída), então
                // "nenhuma pendente" é o mesmo que "concluído" aqui — o caso vazio é impossível.
                val isCurrentExerciseCompleted =
                    dao.countIncompleteSetsForExerciseSession(setLog.exerciseSessionId, setLog.id) == 0

                val recommendation = resolveRestRecommendation(
                    actualExerciseId = exSession?.actualExerciseId,
                    restDurationSecondsSnapshot = exSession?.restDurationSecondsSnapshot
                )
                val restDuration = if (isCurrentExerciseCompleted) {
                    recommendation.afterExercise
                } else {
                    recommendation.betweenSets
                }

                startRestTimer(
                    durationSeconds = restDuration,
                    workoutSessionId = exSession?.sessionId,
                    exerciseSessionId = setLog.exerciseSessionId,
                    timerType = if (isCurrentExerciseCompleted) "REST_EXERCISE" else "REST_SET"
                )
            }
        }
    }

    suspend fun updateExistingWorkoutsRestDuration(newRestSeconds: Int) {
        dao.updateAllTemplateExercisesRestDuration(newRestSeconds)
        dao.updateActiveExerciseSessionsRestDuration(newRestSeconds)
    }

    suspend fun addSet(exerciseSessionId: Long, setNumber: Int, repetitions: Int, weight: Float) {
        dao.insertSetLogs(listOf(
            SetLogEntity(
                exerciseSessionId = exerciseSessionId,
                setNumber = setNumber,
                repetitions = repetitions,
                weight = weight,
                completed = false
            )
        ))
    }

    suspend fun removeSet(setLog: SetLogEntity) {
        dao.deleteSetLog(setLog)
    }

    suspend fun getAlternativesForExercise(exerciseId: Long): List<ExerciseEntity> {
        val current = dao.getExerciseById(exerciseId) ?: return emptyList()
        val result = mutableListOf<ExerciseEntity>()

        // 1. Explicit alternatives
        val explicit = dao.getExplicitAlternatives(exerciseId)
        result.addAll(explicit)

        // 2. Fallback substitution group
        if (!current.substitutionGroup.isNullOrBlank()) {
            val bySubGroup = dao.getAlternativesBySubstitutionGroup(exerciseId)
            result.addAll(bySubGroup)
        }

        // 3. Fallback movement pattern
        if (!current.movementPattern.isNullOrBlank()) {
            val byPattern = dao.getAlternativesByMovementPattern(exerciseId)
            result.addAll(byPattern)
        }

        // 4. Fallback primary muscle
        val byMuscle = dao.getAlternativesByMuscle(exerciseId)
        result.addAll(byMuscle)

        // Filter out self, duplicate IDs, and identical canonical IDs
        return result
            .filter { it.id != exerciseId && (current.canonicalId == null || it.canonicalId != current.canonicalId) }
            .distinctBy { it.id }
    }

    suspend fun swapExercise(
        exerciseSessionId: Long,
        oldExerciseId: Long,
        newExerciseId: Long,
        permanent: Boolean,
        templateId: Long?
    ) {
        val newExercise = dao.getExerciseById(newExerciseId) ?: return
        val exerciseOverride = dao.getOverrideForExercise(newExerciseId)
        val resolvedExercise = com.example.domain.engine.ExerciseResolver.resolve(newExercise, exerciseOverride)
        
        // Always swap in the current session
        dao.updateExerciseSessionActualExercise(
            exerciseSessionId = exerciseSessionId,
            newExerciseId = newExerciseId,
            newName = resolvedExercise.displayName,
            reason = if (permanent) "PERMANENT_SWAP" else "TEMPORARY_SWAP"
        )
        
        // If permanent and template exists, update template
        if (permanent && templateId != null) {
            dao.updateTemplateExercise(templateId, oldExerciseId, newExerciseId)
        }
    }

    /**
     * Começa um treino a partir de um template.
     *
     * Duas correções da auditoria de 2026-09-12 moram nesta assinatura:
     *
     * - **serializada** pelo [sessionLifecycleMutex]: a checagem "existe sessão ativa?" e a
     *   inserção da sessão eram dois passos separados, e dois toques rápidos criavam duas sessões
     *   `IN_PROGRESS` — a segunda invisível para o `LIMIT 1` e órfã para sempre;
     * - **transacional**: eram 3 + N×2 escritas soltas. Uma morte de processo no meio deixava uma
     *   sessão `IN_PROGRESS` sem exercícios, e `getActiveSession() != null` bloqueava qualquer
     *   novo início: o usuário caía em "Treino Vazio" sem outra saída além de cancelar.
     *
     * A preferência de check-in automático é lida **antes** da transação (DataStore é outro
     * armazenamento e não deve ser consultado com um lock de banco aberto), e o evento de
     * gamificação é publicado **depois** do commit — antes dele, "treino iniciado" seria um fato
     * sobre uma sessão que ainda pode não existir.
     */
    suspend fun startSession(templateId: Long) {
        val started = sessionLifecycleMutex.withLock {
            // Prevent concurrent overlapping sessions
            if (dao.getActiveSession() != null) return@withLock null

            val template = dao.getTemplateById(templateId)
            val templateName = template?.name ?: "Treino Customizado"
            val startedAt = System.currentTimeMillis()
            val autoCheckIn = settingsManager.autoCheckInFlow.firstOrNull() ?: true

            val sessionId = syncMutations.mutate {
                startSessionWrites(templateId, templateName, startedAt, autoCheckIn)
            }
            StartedSession(sessionId, startedAt, templateName)
        } ?: return

        publishEvent(
            GamificationEvents.workoutStarted(
                sessionId = started.sessionId,
                timestamp = started.startedAt,
                templateId = templateId,
                templateName = started.templateName
            )
        )
    }

    private data class StartedSession(
        val sessionId: Long,
        val startedAt: Long,
        val templateName: String
    )

    /**
     * As escritas de [startSession], todas dentro da mesma transação.
     *
     * Nenhuma mutação de sync é registrada aqui de propósito: `IN_PROGRESS` é estado de execução
     * **deste** aparelho, e a política por status está em `docs/architecture/sync-protocol.md`. O
     * que se usa da fronteira é só o commit único.
     */
    private suspend fun startSessionWrites(
        templateId: Long,
        templateName: String,
        startedAt: Long,
        autoCheckIn: Boolean
    ): Long {
        // 1. Create WorkoutSession (Status: IN_PROGRESS)
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = startedAt,
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = templateName
            )
        )

        // Auto Check-in Logic
        if (autoCheckIn) {
            val activeCheckIn = dao.getActiveCheckIn()
            if (activeCheckIn != null) {
                // Link existing active check-in to this session
                dao.updateCheckIn(activeCheckIn.copy(sessionId = sessionId))
            } else {
                // Create new check-in
                dao.insertCheckIn(
                    CheckInEntity(
                        checkInTime = System.currentTimeMillis(),
                        sessionId = sessionId
                    )
                )
            }
        }

        // 2. Fetch template exercises (the plan)
        val plannedExercises = dao.getTemplateExercisesWithDetails(templateId)

        // 3. Create ExerciseSessions and SetLogs with robust preload & snapshots
        plannedExercises.forEachIndexed { plannedIndex, plannedWithDetails ->
            // Positions are normalized to 1..N here so that planned and execution order share one
            // base with reorderExercises; template sortOrder may be 0-based, sparse or duplicated.
            val position = plannedIndex + 1
            val exerciseOverride = dao.getOverrideForExercise(plannedWithDetails.exercise.id)
            val resolvedExercise = com.example.domain.engine.ExerciseResolver.resolve(plannedWithDetails.exercise, exerciseOverride)
            val exSessionId = dao.insertExerciseSession(
                ExerciseSessionEntity(
                    sessionId = sessionId,
                    plannedExerciseId = plannedWithDetails.exercise.id,
                    actualExerciseId = plannedWithDetails.exercise.id,
                    exerciseNameSnapshot = resolvedExercise.displayName, // Historic snapshot uses resolved name
                    sortOrder = position,
                    plannedOrder = position,
                    executionOrder = position,
                    machineLabelSnapshot = plannedWithDetails.templateExercise.machineLabel,
                    primaryMuscleSnapshot = plannedWithDetails.exercise.primaryMuscle,
                    restDurationSecondsSnapshot = plannedWithDetails.templateExercise.restDurationSeconds
                )
            )

            val allPreviousSets = dao.getLastExecutionSetsForExercise(plannedWithDetails.exercise.id)
            val previousWorkingSets = allPreviousSets.filter { it.type != SetType.WARMUP.name }
            val setsToCreate = mutableListOf<SetLogEntity>()
            
            val targetSets = if (plannedWithDetails.templateExercise.targetSets > 0) plannedWithDetails.templateExercise.targetSets else 3
            val plannedWeight = plannedWithDetails.templateExercise.plannedWeight ?: 0f
            
            if (previousWorkingSets.isNotEmpty()) {
                val lastWorkingSet = previousWorkingSets.last()
                
                for (i in 0 until targetSets) {
                    val prevSet = previousWorkingSets.getOrNull(i) ?: lastWorkingSet
                    val setWeight = if (prevSet.weight > 0f) prevSet.weight else (plannedWithDetails.templateExercise.plannedWeight ?: 0f)
                    val setReps = if (prevSet.repetitions > 0) prevSet.repetitions else plannedWithDetails.templateExercise.minReps
                    
                    setsToCreate.add(
                        SetLogEntity(
                            exerciseSessionId = exSessionId,
                            setNumber = i + 1,
                            repetitions = setReps,
                            weight = setWeight,
                            type = prevSet.type,
                            completed = false
                        )
                    )
                }
            } else {
                val reps = plannedWithDetails.templateExercise.minReps
                for (i in 1..targetSets) {
                    setsToCreate.add(
                        SetLogEntity(
                            exerciseSessionId = exSessionId,
                            setNumber = i,
                            repetitions = reps,
                            weight = plannedWeight,
                            type = SetType.NORMAL.name,
                            completed = false
                        )
                    )
                }
            }
            dao.insertSetLogs(setsToCreate)
        }

        return sessionId
    }

    suspend fun finishSession(sessionId: Long) = sessionLifecycleMutex.withLock {
        finishSessionLocked(sessionId)
    }

    private suspend fun finishSessionLocked(sessionId: Long) {
        val session = dao.getActiveSession() ?: return
        if (session.id == sessionId) {
            val finishedTime = System.currentTimeMillis()
            // A preferência é lida antes da transação: DataStore é outra fonte de armazenamento e
            // não deve ser consultado com um lock de banco aberto.
            val autoCheckOut = settingsManager.autoCheckOutFlow.firstOrNull() ?: true

            syncMutations.mutate {
                dao.updateSession(
                    session.copy(
                        finishedAt = finishedTime,
                        status = SessionStatus.COMPLETED.name
                    )
                )

                // Auto Check-out Logic
                if (autoCheckOut) {
                    val checkIn = dao.getCheckInForSession(sessionId)
                    if (checkIn != null && checkIn.checkOutTime == null) {
                        dao.updateCheckIn(checkIn.copy(checkOutTime = finishedTime))
                        upsert(SyncEntityType.CHECK_IN, checkIn.syncId)
                    }
                }

                // Concluir é o momento em que a sessão vira histórico. É a única transição de
                // sessão que registra mutação: `IN_PROGRESS` é estado de execução deste aparelho e
                // `CANCELLED` não é histórico de treino — a política por status está em
                // `docs/architecture/sync-protocol.md`.
                upsert(SyncEntityType.WORKOUT_SESSION, session.syncId)
            }
        }
        skipRestTimer()

        // Consulta por id (auditoria 2026-09-12). Isto carregava **todo** o histórico concluído —
        // com o grafo de exercícios e séries de cada treino — só para achar a sessão que acabou de
        // terminar. O custo crescia linearmente com os anos de uso do app, a cada treino.
        val currentSummary = dao.getCompletedSessionSummaryById(sessionId) ?: return

        evaluatePersonalRecords(currentSummary)
        publishWorkoutEvents(currentSummary)
    }

    suspend fun cancelSession(sessionId: Long) = sessionLifecycleMutex.withLock {
        cancelSessionLocked(sessionId)
    }

    private suspend fun cancelSessionLocked(sessionId: Long) {
        val session = dao.getActiveSession() ?: return
        if (session.id == sessionId) {
            dao.updateSession(
                session.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = SessionStatus.CANCELLED.name
                )
            )
        }
        skipRestTimer()
    }
    
    /**
     * Evaluates personal records strictly excluding warm-up sets.
     */
    private suspend fun evaluatePersonalRecords(currentSummary: SessionCalendarSummary) {
        currentSummary.exercises.forEach { ex ->
            val exerciseId = ex.exerciseSession.actualExerciseId ?: ex.exerciseSession.plannedExerciseId ?: return@forEach
            // Strictly exclude warm-up sets from PR calculation
            val workingCompletedSets = ex.sets.filter { it.completed && it.type != SetType.WARMUP.name }
            if (workingCompletedSets.isEmpty()) return@forEach
            
            // Strictly exclude duration-based exercises from Max Weight and 1RM
            val strengthCompletedSets = workingCompletedSets.filter { !it.isDurationMode }
            if (strengthCompletedSets.isNotEmpty()) {
                // Max Weight PR
                val maxWeightThisSession = strengthCompletedSets.maxOf { it.weight }
                registerPersonalRecordIfImproved(
                    exerciseId = exerciseId,
                    prType = com.example.data.local.PRType.MAX_WEIGHT,
                    value = maxWeightThisSession,
                    exerciseName = ex.exerciseSession.exerciseNameSnapshot
                )

                // 1RM Estimated on working sets
                val best1RMThisSession = strengthCompletedSets
                    .filter { it.weight > 0f && it.repetitions > 0 }
                    .maxOfOrNull { VolumeCalculator.calculateOneRepMax(it.weight, it.repetitions) } ?: 0f

                registerPersonalRecordIfImproved(
                    exerciseId = exerciseId,
                    prType = com.example.data.local.PRType.ONE_REP_MAX,
                    value = best1RMThisSession,
                    exerciseName = ex.exerciseSession.exerciseNameSnapshot
                )
            }
            
            // Max Volume PR (Tonnage on working sets - VolumeCalculator automatically excludes duration sets)
            val volumeThisSession = VolumeCalculator.calculateVolume(ex.sets).toFloat()
            registerPersonalRecordIfImproved(
                exerciseId = exerciseId,
                prType = com.example.data.local.PRType.MAX_VOLUME,
                value = volumeThisSession,
                exerciseName = ex.exerciseSession.exerciseNameSnapshot
            )
        }
    }

    /**
     * Único ponto de gravação de recordes pessoais.
     *
     * Grava apenas quando o valor supera o melhor registro anterior e, nesse caso, informa o fato
     * "novo recorde" — o motor não sabe (nem precisa saber) o que será feito com ele.
     *
     * @return `true` quando um novo recorde foi gravado.
     */
    suspend fun registerPersonalRecordIfImproved(
        exerciseId: Long,
        prType: PRType,
        value: Float,
        timestamp: Long = System.currentTimeMillis(),
        exerciseName: String? = null
    ): Boolean {
        if (value <= 0f) return false
        val previousValue = dao.getHighestPR(exerciseId, prType.name)?.value ?: 0f
        if (value <= previousValue) return false

        dao.insertPersonalRecord(
            PersonalRecordEntity(
                exerciseId = exerciseId,
                date = timestamp,
                prType = prType,
                value = value
            )
        )

        publishEvent(
            GamificationEvents.personalRecordCreated(
                exerciseId = exerciseId,
                prType = prType.name,
                value = value,
                previousValue = previousValue,
                timestamp = timestamp,
                exerciseName = exerciseName
            )
        )
        return true
    }

    /**
     * Publica os fatos do treino recém-concluído: exercícios executados, estreias e o encerramento
     * do treino (último, pois é ele que fecha a leitura de consistência do histórico).
     */
    private suspend fun publishWorkoutEvents(summary: SessionCalendarSummary) {
        val session = summary.session
        val finishedAt = session.finishedAt ?: System.currentTimeMillis()
        var completedExercises = 0
        var completedSets = 0

        summary.sortedExercises.forEach { ex ->
            val exerciseId = ex.exerciseSession.actualExerciseId ?: ex.exerciseSession.plannedExerciseId
            val completedSetsForExercise = ex.sets.count { it.completed }
            if (completedSetsForExercise == 0) return@forEach

            completedExercises++
            completedSets += completedSetsForExercise
            if (exerciseId == null) return@forEach

            publishEvent(
                GamificationEvents.exerciseCompleted(
                    exerciseSessionId = ex.exerciseSession.id,
                    exerciseId = exerciseId,
                    sessionId = session.id,
                    timestamp = ex.exerciseSession.finishedAt ?: finishedAt,
                    exerciseName = ex.exerciseSession.exerciseNameSnapshot,
                    completedSets = completedSetsForExercise
                )
            )

            // A sessão atual já está COMPLETED aqui: contagem 1 significa estreia do exercício.
            val executionCount = runCatching { dao.getExerciseExecutionCount(exerciseId) }.getOrDefault(0)
            if (executionCount <= 1) {
                publishEvent(
                    GamificationEvents.firstExerciseCompleted(
                        exerciseId = exerciseId,
                        sessionId = session.id,
                        timestamp = ex.exerciseSession.finishedAt ?: finishedAt,
                        exerciseName = ex.exerciseSession.exerciseNameSnapshot
                    )
                )
            }
        }

        publishEvent(
            GamificationEvents.workoutCompleted(
                sessionId = session.id,
                timestamp = finishedAt,
                templateName = session.templateNameSnapshot,
                completedExercises = completedExercises,
                completedSets = completedSets,
                durationSeconds = ((finishedAt - session.startedAt) / 1000).coerceAtLeast(0)
            )
        )
        
        val workoutExecutionCount = runCatching { dao.getCompletedSessionTimestamps().size }.getOrDefault(0)
        // Note: the current session is already COMPLETED here. So if it's 1, it's the very first.
        if (workoutExecutionCount <= 1) {
            publishEvent(
                GamificationEvents.firstWorkoutCompleted(
                    sessionId = session.id,
                    timestamp = finishedAt
                )
            )
        }
    }

    /** A gamificação nunca pode interromper o treino: falhas ao publicar são contidas aqui. */
    private suspend fun publishEvent(event: GamificationEvent) {
        try {
            gamificationEvents.publish(event)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCalendarHistoryFlow(): Flow<List<SessionCalendarSummary>> {
        return dao.getAllCompletedSessionsWithDetailsFlow()
    }

    /**
     * O resumo de **uma** sessão concluída.
     *
     * A tela de resumo filtrava `getCalendarHistoryFlow()` por id, ou seja, observava o histórico
     * inteiro e o recarregava a cada mudança em qualquer treino para desenhar um só (auditoria
     * 2026-09-12).
     */
    fun getCompletedSessionSummaryFlow(sessionId: Long): Flow<SessionCalendarSummary?> {
        return dao.getCompletedSessionSummaryByIdFlow(sessionId)
    }
    
    /**
     * Apagar histórico é direito do usuário e continua sendo delete **físico local** (T16.3 não
     * muda isso). O tombstone remoto — o que impede o item de ressuscitar vindo de outro aparelho
     * — é assunto da T16.7; aqui a intenção fica registrada como `DELETE` do agregado.
     */
    suspend fun deleteHistoricalSession(session: WorkoutSessionEntity) {
        syncMutations.mutate {
            dao.deleteWorkoutSession(session)
            delete(SyncEntityType.WORKOUT_SESSION, session.syncId)
        }
    }

    /**
     * Grava a academia/observação de um check-in — criando o check-in se ele não existir.
     *
     * O caminho de inserção entrou na auditoria de 2026-09-12. `updateCheckIn` sozinho é um
     * `UPDATE ... WHERE id = ?`, e a tela de histórico monta a entidade com `id = 0` quando a
     * sessão nunca teve check-in (treino iniciado com o check-in automático desligado). O `UPDATE`
     * não encontrava linha nenhuma, a mutação de sync era registrada mesmo assim e o usuário via o
     * diálogo fechar como se tivesse salvado. Nada era gravado, e nada era dito.
     */
    suspend fun updateCheckInDetails(checkIn: CheckInEntity) {
        syncMutations.mutate {
            if (checkIn.id == 0L) {
                dao.insertCheckIn(checkIn)
            } else {
                dao.updateCheckIn(checkIn)
            }
            upsert(SyncEntityType.CHECK_IN, checkIn.syncId)
        }
    }

    suspend fun reorderExercises(sessionId: Long, updatedExercises: List<ExerciseSessionEntity>) {
        val updated = updatedExercises.mapIndexed { index, ex ->
            ex.copy(executionOrder = index + 1, sortOrder = index + 1)
        }
        dao.updateExerciseSessions(updated)
    }

    companion object {
        private const val TAG = "WorkoutEngine"

        /** Devolvido por [adjustRestTimer] quando não há descanso em andamento para estender. */
        const val NO_ACTIVE_REST_TIMER = -1L
    }

}
