package com.example.domain.engine

import com.example.data.datastore.SettingsManager
import com.example.data.local.*
import com.example.domain.gamification.GamificationEventPublisher
import com.example.domain.gamification.GamificationEvents
import com.example.domain.gamification.model.GamificationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class SyncResult(
    val updatedCount: Int,
    val skippedCompletedCount: Int = 0,
    val skippedDifferentTypeCount: Int = 0,
    val message: String = ""
)

class WorkoutEngine(
    val dao: WorkoutDao,
    private val settingsManager: SettingsManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    // O motor apenas informa fatos. Quem os interpreta (histórico, XP, conquistas) vive fora daqui.
    private val gamificationEvents: GamificationEventPublisher = GamificationEventPublisher.NoOp
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

    init {
        // Process restoration check: restore timer if valid active session and deadline in future
        coroutineScope.launch {
            try {
                val hasSavedDeadline = settingsManager.restTimerDeadlineFlow.firstOrNull() != null
                if (hasSavedDeadline) {
                    restoreTimerState()
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
        val active = dao.getActiveCheckIn()
        if (active == null) {
            dao.insertCheckIn(CheckInEntity(checkInTime = System.currentTimeMillis(), gymName = gymName))
        }
    }

    suspend fun manualCheckOut() {
        val active = dao.getActiveCheckIn()
        if (active != null) {
            dao.updateCheckIn(active.copy(checkOutTime = System.currentTimeMillis()))
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

    suspend fun adjustRestTimer(secondsToAdd: Int): Long {
        val currentTarget = _restTimerTarget.value ?: System.currentTimeMillis()
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
            val sessionWithDetails = sessionId?.let { dao.getSessionWithDetails(it) }
            val allExercises = sessionWithDetails?.exercises ?: emptyList()

            // Check if entire workout is completed
            val isEntireWorkoutCompleted = allExercises.isNotEmpty() && allExercises.all { ex ->
                ex.sets.isNotEmpty() && ex.sets.all { it.completed || it.id == setLog.id }
            }

            if (isEntireWorkoutCompleted) {
                // Entire workout completed! No rest timer should start. Cancel any active timer.
                skipRestTimer()
                return
            }

            val autoTimer = settingsManager.autoRestTimerOnSetFlow.firstOrNull() ?: true
            if (autoTimer) {
                val currentExerciseSets = allExercises.find { it.exerciseSession.id == setLog.exerciseSessionId }?.sets
                val isCurrentExerciseCompleted = currentExerciseSets != null &&
                        currentExerciseSets.isNotEmpty() &&
                        currentExerciseSets.all { it.completed || it.id == setLog.id }

                val override = exSession?.actualExerciseId?.let { dao.getOverrideForExercise(it) }
                
                val restDuration = if (isCurrentExerciseCompleted) {
                    settingsManager.defaultExerciseRestSecondsFlow.firstOrNull()
                        ?: exSession?.restDurationSecondsSnapshot
                        ?: 90
                } else {
                    exSession?.restDurationSecondsSnapshot
                        ?: override?.defaultRestSeconds
                        ?: settingsManager.defaultRestSecondsFlow.firstOrNull()
                        ?: 90
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

    suspend fun startSession(templateId: Long) {
        // Prevent concurrent overlapping sessions
        if (dao.getActiveSession() != null) return
        
        val template = dao.getTemplateById(templateId)
        val templateName = template?.name ?: "Treino Customizado"
        val startedAt = System.currentTimeMillis()

        // 1. Create WorkoutSession (Status: IN_PROGRESS)
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = startedAt,
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = templateName
            )
        )

        publishEvent(
            GamificationEvents.workoutStarted(
                sessionId = sessionId,
                timestamp = startedAt,
                templateId = templateId,
                templateName = templateName
            )
        )

        // Auto Check-in Logic
        val autoCheckIn = settingsManager.autoCheckInFlow.firstOrNull() ?: true
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
    }

    suspend fun finishSession(sessionId: Long) {
        val session = dao.getActiveSession() ?: return
        if (session.id == sessionId) {
            val finishedTime = System.currentTimeMillis()
            dao.updateSession(
                session.copy(
                    finishedAt = finishedTime,
                    status = SessionStatus.COMPLETED.name
                )
            )
            
            // Auto Check-out Logic
            val autoCheckOut = settingsManager.autoCheckOutFlow.firstOrNull() ?: true
            if (autoCheckOut) {
                val checkIn = dao.getCheckInForSession(sessionId)
                if (checkIn != null && checkIn.checkOutTime == null) {
                    dao.updateCheckIn(checkIn.copy(checkOutTime = finishedTime))
                }
            }
        }
        skipRestTimer()

        val summaries = dao.getAllCompletedSessionsWithDetails()
        val currentSummary = summaries.find { it.session.id == sessionId } ?: return

        evaluatePersonalRecords(currentSummary)
        publishWorkoutEvents(currentSummary)
    }

    suspend fun cancelSession(sessionId: Long) {
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
    
    suspend fun deleteHistoricalSession(session: WorkoutSessionEntity) {
        dao.deleteWorkoutSession(session)
    }
    
    suspend fun updateCheckInDetails(checkIn: CheckInEntity) {
        dao.updateCheckIn(checkIn)
    }

    suspend fun reorderExercises(sessionId: Long, updatedExercises: List<ExerciseSessionEntity>) {
        val updated = updatedExercises.mapIndexed { index, ex ->
            ex.copy(executionOrder = index + 1, sortOrder = index + 1)
        }
        dao.updateExerciseSessions(updated)
    }
}
