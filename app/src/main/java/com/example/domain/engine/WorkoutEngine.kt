package com.example.domain.engine

import com.example.data.local.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.data.datastore.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

class WorkoutEngine(
    private val dao: WorkoutDao,
    private val settingsManager: SettingsManager
) {

    val activeSessionFlow: Flow<WorkoutSessionEntity?> = dao.getActiveSessionFlow()
    val activeSessionWithDetailsFlow: Flow<SessionWithDetails?> = dao.getActiveSessionWithDetailsFlow()
    val activeCheckInFlow: Flow<CheckInEntity?> = dao.getActiveCheckInFlow()

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

    private val _restTimerTarget = MutableStateFlow<Long?>(null)
    val restTimerTarget: Flow<Long?> = _restTimerTarget

    suspend fun startRestTimer(durationSeconds: Int) {
        val target = System.currentTimeMillis() + (durationSeconds * 1000L)
        _restTimerTarget.value = target
        settingsManager.setRestTimerDeadline(target)
    }

    suspend fun adjustRestTimer(secondsToAdd: Int) {
        val currentTarget = _restTimerTarget.value ?: System.currentTimeMillis()
        val newTarget = currentTarget + (secondsToAdd * 1000L)
        _restTimerTarget.value = newTarget
        settingsManager.setRestTimerDeadline(newTarget)
    }

    suspend fun skipRestTimer() {
        _restTimerTarget.value = null
        settingsManager.setRestTimerDeadline(null)
    }

    suspend fun getLastExecutionSetsForExercise(exerciseId: Long): List<SetLogEntity> {
        return dao.getLastExecutionSetsForExercise(exerciseId)
    }

    suspend fun updateSet(setLog: SetLogEntity) {
        dao.updateSetLog(setLog)
        if (setLog.completed) {
            val autoTimer = settingsManager.autoRestTimerOnSetFlow.firstOrNull() ?: true
            if (autoTimer) {
                val defaultRest = settingsManager.defaultRestSecondsFlow.firstOrNull() ?: 90
                startRestTimer(defaultRest)
            }
        }
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
        
        // Always swap in the current session
        dao.updateExerciseSessionActualExercise(
            exerciseSessionId = exerciseSessionId,
            newExerciseId = newExerciseId,
            newName = newExercise.name,
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

        // 1. Create WorkoutSession (Status: IN_PROGRESS)
        val sessionId = dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.IN_PROGRESS.name,
                templateNameSnapshot = templateName
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
        plannedExercises.forEach { plannedWithDetails ->
            val exSessionId = dao.insertExerciseSession(
                ExerciseSessionEntity(
                    sessionId = sessionId,
                    plannedExerciseId = plannedWithDetails.exercise.id,
                    actualExerciseId = plannedWithDetails.exercise.id,
                    exerciseNameSnapshot = plannedWithDetails.exercise.name, // Historic snapshot
                    sortOrder = plannedWithDetails.templateExercise.sortOrder,
                    machineLabelSnapshot = plannedWithDetails.templateExercise.machineLabel,
                    primaryMuscleSnapshot = plannedWithDetails.exercise.primaryMuscle
                )
            )

            val previousSets = dao.getLastExecutionSetsForExercise(plannedWithDetails.exercise.id)
            val setsToCreate = mutableListOf<SetLogEntity>()
            
            val targetSets = if (plannedWithDetails.templateExercise.targetSets > 0) plannedWithDetails.templateExercise.targetSets else 3
            val plannedWeight = plannedWithDetails.templateExercise.plannedWeight ?: 0f
            
            if (previousSets.isNotEmpty()) {
                val lastWorkingSet = previousSets.lastOrNull { it.type != SetType.WARMUP.name } ?: previousSets.last()
                
                for (i in 0 until targetSets) {
                    val prevSet = previousSets.getOrNull(i) ?: lastWorkingSet
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
        evaluatePersonalRecords(sessionId)
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
    }
    
    
    private suspend fun evaluatePersonalRecords(sessionId: Long) {
        val summaries = dao.getAllCompletedSessionsWithDetails()
        val currentSummary = summaries.find { it.session.id == sessionId } ?: return
        
        currentSummary.exercises.forEach { ex ->
            val exerciseId = ex.exerciseSession.actualExerciseId ?: ex.exerciseSession.plannedExerciseId ?: return@forEach
            val completedSets = ex.sets.filter { it.completed }
            if (completedSets.isEmpty()) return@forEach
            
            // Max Weight PR
            val maxWeightThisSession = completedSets.maxOf { it.weight }
            val pastMaxWeight = dao.getHighestPR(exerciseId, com.example.data.local.PRType.MAX_WEIGHT.name)?.value ?: 0f
            if (maxWeightThisSession > pastMaxWeight) {
                dao.insertPersonalRecord(com.example.data.local.PersonalRecordEntity(exerciseId = exerciseId, date = System.currentTimeMillis(), prType = com.example.data.local.PRType.MAX_WEIGHT, value = maxWeightThisSession))
            }
            
            // Max Volume PR
            val volumeThisSession = com.example.domain.engine.VolumeCalculator.calculateVolume(ex.sets).toFloat()
            val pastMaxVolume = dao.getHighestPR(exerciseId, com.example.data.local.PRType.MAX_VOLUME.name)?.value ?: 0f
            if (volumeThisSession > pastMaxVolume) {
                dao.insertPersonalRecord(com.example.data.local.PersonalRecordEntity(exerciseId = exerciseId, date = System.currentTimeMillis(), prType = com.example.data.local.PRType.MAX_VOLUME, value = volumeThisSession))
            }
            
            // 1RM
            val best1RMThisSession = completedSets.maxOf { com.example.domain.engine.VolumeCalculator.calculateOneRepMax(it.weight, it.repetitions) }
            val past1RM = dao.getHighestPR(exerciseId, com.example.data.local.PRType.ONE_REP_MAX.name)?.value ?: 0f
            if (best1RMThisSession > past1RM) {
                dao.insertPersonalRecord(com.example.data.local.PersonalRecordEntity(exerciseId = exerciseId, date = System.currentTimeMillis(), prType = com.example.data.local.PRType.ONE_REP_MAX, value = best1RMThisSession))
            }
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
}
