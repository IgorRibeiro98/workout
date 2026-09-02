package com.example.presentation.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionWithDetails
import com.example.data.local.SetLogEntity
import com.example.domain.engine.SyncResult
import com.example.domain.engine.WorkoutEngine
import com.example.service.WorkoutNotificationManager
import com.example.domain.workout.execution.ExerciseExecutionStatus
import com.example.domain.workout.execution.WorkoutExerciseExecution
import com.example.domain.workout.execution.WorkoutExecutionOrderManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ExecutionPhase {
    ACTIVE_SET,
    RESTING,
    EXERCISE_TRANSITION,
    WORKOUT_COMPLETE
}

enum class FeedbackType {
    NEW_RECORD,
    PROGRESSION,
    GOAL_ACHIEVED,
    FIRST_TIME,
    NORMAL
}

data class SetCompletionFeedback(
    val type: FeedbackType,
    val title: String,
    val subtitle: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ExecutionState(
    val sessionWithDetails: SessionWithDetails? = null,
    val currentExerciseIndex: Int = 0,
    val isLoading: Boolean = true,
    val previousExecutionSets: List<SetLogEntity> = emptyList(),
    val currentResolvedExercise: com.example.domain.model.ResolvedExercise? = null,
    val exerciseExecutionContext: com.example.domain.workout.execution.ExerciseExecutionContext? = null,
    val isResting: Boolean = false,
    val pendingMoveConfirmation: WorkoutExerciseExecution? = null,
    val lastSetFeedback: SetCompletionFeedback? = null
) {
    val currentExercise: ExerciseSessionWithSets?
        get() = sessionWithDetails?.exercises?.getOrNull(currentExerciseIndex)

    val isLastExercise: Boolean
        get() = currentExerciseIndex >= (sessionWithDetails?.exercises?.size ?: 1) - 1

    val isFirstExercise: Boolean
        get() = currentExerciseIndex == 0

    val activeSetIndex: Int?
        get() = currentExercise?.sets?.indexOfFirst { !it.completed }?.takeIf { it >= 0 }

    val activeSet: SetLogEntity?
        get() = activeSetIndex?.let { currentExercise?.sets?.getOrNull(it) }

    val isExerciseCompleted: Boolean
        get() = currentExercise?.sets?.isNotEmpty() == true && currentExercise?.sets?.all { it.completed } == true

    val isAllExercisesCompleted: Boolean
        get() = sessionWithDetails?.exercises?.isNotEmpty() == true &&
                sessionWithDetails.exercises.all { ex -> ex.sets.isNotEmpty() && ex.sets.all { it.completed } }

    val isOrderAdapted: Boolean
        get() = sessionWithDetails?.exercises?.any { it.exerciseSession.executionOrder != it.exerciseSession.plannedOrder } == true

    val exerciseExecutions: List<WorkoutExerciseExecution>
        get() = sessionWithDetails?.exercises?.mapIndexed { idx, ex ->
            val totalSets = ex.sets.size
            val completedCount = ex.sets.count { it.completed }
            val status = when {
                totalSets > 0 && completedCount == totalSets -> ExerciseExecutionStatus.COMPLETED
                idx == currentExerciseIndex || completedCount > 0 -> ExerciseExecutionStatus.IN_PROGRESS
                else -> ExerciseExecutionStatus.PENDING
            }
            val exIdStr = ex.exerciseSession.id.toString()
            WorkoutExerciseExecution(
                exerciseSessionId = ex.exerciseSession.id,
                exerciseId = exIdStr,
                name = ex.exerciseSession.exerciseNameSnapshot,
                plannedOrder = ex.exerciseSession.plannedOrder,
                executionOrder = ex.exerciseSession.executionOrder,
                status = status
            )
        } ?: emptyList()

    val phase: ExecutionPhase
        get() = when {
            isAllExercisesCompleted -> ExecutionPhase.WORKOUT_COMPLETE
            isResting -> ExecutionPhase.RESTING
            isExerciseCompleted -> ExecutionPhase.EXERCISE_TRANSITION
            else -> ExecutionPhase.ACTIVE_SET
        }
}

class ExecutionViewModel(
    private val workoutEngine: WorkoutEngine,
    private val notificationManager: WorkoutNotificationManager,
    val settingsManager: SettingsManager
) : ViewModel() {

    val keepScreenOn = settingsManager.keepScreenOnFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val hapticEnabled = settingsManager.hapticEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val soundEnabled = settingsManager.soundEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val preAlertEnabled = settingsManager.preAlertEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val rirRpeEnabled = settingsManager.rirRpeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showGifs = settingsManager.showGifsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showCoachTip = settingsManager.showCoachTipFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _currentExerciseIndex = MutableStateFlow(0)
    
    private val _previousExecutionSets = MutableStateFlow<List<SetLogEntity>>(emptyList())
    private val _exerciseExecutionContext = MutableStateFlow<com.example.domain.workout.execution.ExerciseExecutionContext?>(null)
    private val _setFeedback = MutableStateFlow<SetCompletionFeedback?>(null)

    val restTimerTarget = workoutEngine.restTimerTarget
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var hasAutoRestoredIndex = false
    private var lastSessionId: Long? = null

    private val _pendingMoveConfirmation = MutableStateFlow<WorkoutExerciseExecution?>(null)

    private val baseSessionState = combine(
        workoutEngine.activeSessionWithDetailsFlow,
        _currentExerciseIndex,
        _previousExecutionSets,
        _exerciseExecutionContext,
        workoutEngine.activeResolvedExercises
    ) { rawSessionWithDetails, index, previousSets, exerciseContext, resolvedExercises ->
        val sessionWithDetails = rawSessionWithDetails?.let { session ->
            session.copy(
                exercises = session.exercises.sortedBy { it.exerciseSession.executionOrder }
            )
        }
        // Auto restore index to first incomplete exercise on session initial load
        val activeSessionId = sessionWithDetails?.session?.id
        if (sessionWithDetails != null && sessionWithDetails.exercises.isNotEmpty()) {
            if (activeSessionId != lastSessionId) {
                lastSessionId = activeSessionId
                hasAutoRestoredIndex = false
            }

            if (!hasAutoRestoredIndex) {
                val firstIncomplete = sessionWithDetails.exercises.indexOfFirst { ex ->
                    ex.sets.any { !it.completed }
                }
                if (firstIncomplete >= 0) {
                    _currentExerciseIndex.value = firstIncomplete
                }
                hasAutoRestoredIndex = true
            }
        }

        val safeIndex = if (sessionWithDetails != null && sessionWithDetails.exercises.isNotEmpty()) {
            _currentExerciseIndex.value.coerceIn(0, sessionWithDetails.exercises.size - 1)
        } else {
            0
        }
        
        val currentExSession = sessionWithDetails?.exercises?.getOrNull(safeIndex)
        val currentResolvedEx = currentExSession?.exerciseSession?.actualExerciseId?.let { id ->
            resolvedExercises.find { it.id == id }
        }
        
        ExecutionState(
            sessionWithDetails = sessionWithDetails,
            currentExerciseIndex = safeIndex,
            isLoading = sessionWithDetails == null,
            previousExecutionSets = previousSets,
            currentResolvedExercise = currentResolvedEx,
            exerciseExecutionContext = exerciseContext
        )
    }

    val state: StateFlow<ExecutionState> = combine(
        baseSessionState,
        restTimerTarget,
        _setFeedback,
        _pendingMoveConfirmation
    ) { baseState, timerTarget, feedback, pendingMove ->
        val isTimerActive = timerTarget != null && timerTarget > System.currentTimeMillis()
        baseState.copy(
            isResting = isTimerActive,
            lastSetFeedback = feedback,
            pendingMoveConfirmation = pendingMove
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExecutionState())

    init {
        viewModelScope.launch {
            combine(
                state.map { it.currentExercise?.exerciseSession?.actualExerciseId }.distinctUntilChanged(),
                state.map { it.sessionWithDetails?.session?.templateId }.distinctUntilChanged()
            ) { exerciseId, templateId ->
                Pair(exerciseId, templateId)
            }.collect { (exerciseId, templateId) ->
                if (exerciseId != null) {
                    val context = workoutEngine.getExerciseExecutionContext(exerciseId, templateId)
                    _exerciseExecutionContext.value = context
                    val prevSets = workoutEngine.getLastExecutionSetsForExercise(exerciseId)
                    _previousExecutionSets.value = prevSets
                } else {
                    _exerciseExecutionContext.value = null
                    _previousExecutionSets.value = emptyList()
                }
            }
        }
        
        viewModelScope.launch {
            restTimerTarget.collect { target ->
                if (target != null) {
                    val exName = state.value.currentExercise?.exerciseSession?.exerciseNameSnapshot ?: "Exercício"
                    notificationManager.showTimerNotification(exName, target)
                } else {
                    notificationManager.cancelNotification()
                }
            }
        }
    }

    fun selectExercise(index: Int) {
        val maxIndex = (state.value.sessionWithDetails?.exercises?.size ?: 1) - 1
        if (index in 0..maxIndex) {
            _currentExerciseIndex.value = index
        }
    }

    fun setPendingMoveConfirmation(execution: WorkoutExerciseExecution?) {
        _pendingMoveConfirmation.value = execution
    }

    fun dismissPendingMoveConfirmation() {
        _pendingMoveConfirmation.value = null
    }

    fun moveExercise(exerciseId: String, newPosition: Int) {
        val currentSession = state.value.sessionWithDetails ?: return
        val executions = state.value.exerciseExecutions
        val reorderedExecutions = WorkoutExecutionOrderManager.moveExercise(executions, exerciseId, newPosition)

        val map = currentSession.exercises.associateBy {
            it.exerciseSession.id.toString()
        }

        val updatedEntities = reorderedExecutions.mapNotNull { ex ->
            map[ex.exerciseId]?.exerciseSession?.copy(
                executionOrder = ex.executionOrder,
                sortOrder = ex.executionOrder
            )
        }

        viewModelScope.launch {
            workoutEngine.reorderExercises(currentSession.session.id, updatedEntities)
        }
    }

    fun moveExerciseToLater(exerciseId: String) {
        val currentSession = state.value.sessionWithDetails ?: return
        val executions = state.value.exerciseExecutions
        val reorderedExecutions = WorkoutExecutionOrderManager.moveExerciseToLater(executions, exerciseId)

        val map = currentSession.exercises.associateBy {
            it.exerciseSession.id.toString()
        }

        val updatedEntities = reorderedExecutions.mapNotNull { ex ->
            map[ex.exerciseId]?.exerciseSession?.copy(
                executionOrder = ex.executionOrder,
                sortOrder = ex.executionOrder
            )
        }

        viewModelScope.launch {
            workoutEngine.reorderExercises(currentSession.session.id, updatedEntities)
        }
    }

    fun nextExercise() {
        val maxIndex = (state.value.sessionWithDetails?.exercises?.size ?: 1) - 1
        if (_currentExerciseIndex.value < maxIndex) {
            _currentExerciseIndex.value += 1
        }
    }

    fun previousExercise() {
        if (_currentExerciseIndex.value > 0) {
            _currentExerciseIndex.value -= 1
        }
    }

    fun updateSet(setLog: SetLogEntity) {
        viewModelScope.launch {
            workoutEngine.updateSet(setLog)
        }
    }

    fun completeSet(setLog: SetLogEntity) {
        val currentContext = state.value.exerciseExecutionContext
        val lastPerf = currentContext?.lastPerformance
        val pr = currentContext?.personalRecord
        val targetWeight = currentContext?.suggestedLoad ?: 0f
        val targetReps = currentContext?.targetReps

        val feedback = when {
            // Caso 1: Novo recorde pessoal de carga (maior que o maior PR histórico)
            pr != null && setLog.weight > pr.maxWeight && setLog.weight > 0f -> {
                val diff = setLog.weight - pr.maxWeight
                val diffStr = if (diff % 1f == 0f) "+${diff.toInt()}kg" else "+${diff}kg"
                SetCompletionFeedback(
                    type = FeedbackType.NEW_RECORD,
                    title = "🔥 Novo recorde!",
                    subtitle = "$diffStr comparado ao melhor histórico"
                )
            }
            // Caso 2A: Evolução de carga em relação ao último treino
            lastPerf != null && setLog.weight > lastPerf.weight && setLog.weight > 0f -> {
                val diff = setLog.weight - lastPerf.weight
                val diffStr = if (diff % 1f == 0f) "+${diff.toInt()}kg" else "+${diff}kg"
                SetCompletionFeedback(
                    type = FeedbackType.PROGRESSION,
                    title = "🚀 Evolução de carga!",
                    subtitle = "↑ $diffStr desde o último treino"
                )
            }
            // Caso 2B: Evolução de repetições com a mesma carga em relação ao último treino
            lastPerf != null && setLog.weight >= lastPerf.weight && setLog.repetitions > lastPerf.reps -> {
                val diffReps = setLog.repetitions - lastPerf.reps
                SetCompletionFeedback(
                    type = FeedbackType.PROGRESSION,
                    title = "💪 Evolução!",
                    subtitle = "↑ +$diffReps reps comparado ao último treino"
                )
            }
            // Caso 4: Primeira execução
            currentContext?.isFirstTime == true || (lastPerf == null && pr == null) -> {
                SetCompletionFeedback(
                    type = FeedbackType.FIRST_TIME,
                    title = "Histórico iniciado ✨",
                    subtitle = "Primeira execução deste exercício"
                )
            }
            // Prescrição atingida
            (targetReps != null && setLog.repetitions in targetReps.first..targetReps.last) ||
            (targetWeight > 0f && setLog.weight >= targetWeight) -> {
                SetCompletionFeedback(
                    type = FeedbackType.GOAL_ACHIEVED,
                    title = "Prescrição atingida 💪",
                    subtitle = "Dentro da faixa esperada"
                )
            }
            // Caso 3: Dentro da média / Mantendo consistência
            else -> {
                SetCompletionFeedback(
                    type = FeedbackType.NORMAL,
                    title = "Série registrada",
                    subtitle = "Mantendo consistência"
                )
            }
        }

        _setFeedback.value = feedback

        viewModelScope.launch {
            workoutEngine.updateSet(setLog.copy(completed = true, finishedAt = System.currentTimeMillis()))
            
            // Record new PR if max weight exceeded
            val exerciseId = state.value.currentExercise?.exerciseSession?.actualExerciseId
            if (exerciseId != null && setLog.weight > 0f) {
                val highestPR = workoutEngine.dao.getHighestPR(exerciseId, com.example.data.local.PRType.MAX_WEIGHT.name)
                if (highestPR == null || setLog.weight > highestPR.value) {
                    workoutEngine.dao.insertPersonalRecord(
                        com.example.data.local.PersonalRecordEntity(
                            exerciseId = exerciseId,
                            date = System.currentTimeMillis(),
                            prType = com.example.data.local.PRType.MAX_WEIGHT,
                            value = setLog.weight
                        )
                    )
                }
            }

            // Auto dismiss feedback after delay
            kotlinx.coroutines.delay(3500)
            if (_setFeedback.value == feedback) {
                _setFeedback.value = null
            }
        }
    }

    fun dismissFeedback() {
        _setFeedback.value = null
    }
    
    fun uncompleteSet(setLog: SetLogEntity) {
        viewModelScope.launch {
            workoutEngine.updateSet(setLog.copy(completed = false, finishedAt = null))
        }
    }

    fun replicateCurrentSet(onResult: (SyncResult) -> Unit) {
        val currentEx = state.value.currentExercise ?: return
        val currentSet = state.value.activeSet ?: currentEx.sets.firstOrNull { !it.completed } ?: currentEx.sets.lastOrNull() ?: return
        viewModelScope.launch {
            val result = workoutEngine.replicateCurrentSet(currentEx.exerciseSession.id, currentSet)
            onResult(result)
        }
    }

    fun restoreLastExecutionValues(onResult: (SyncResult) -> Unit) {
        val currentEx = state.value.currentExercise ?: return
        val actualExId = currentEx.exerciseSession.actualExerciseId ?: currentEx.exerciseSession.plannedExerciseId
        viewModelScope.launch {
            val result = workoutEngine.restoreLastExecutionSets(currentEx.exerciseSession.id, actualExId)
            onResult(result)
        }
    }

    fun addSet() {
        val currentEx = state.value.currentExercise ?: return
        val currentSets = currentEx.sets
        val newSetNumber = if (currentSets.isEmpty()) 1 else currentSets.maxOf { it.setNumber } + 1
        
        val lastSet = currentSets.lastOrNull()
        val reps = lastSet?.repetitions ?: 10
        val weight = lastSet?.weight ?: 0f

        viewModelScope.launch {
            workoutEngine.addSet(currentEx.exerciseSession.id, newSetNumber, reps, weight)
        }
    }

    fun removeSet(setLog: SetLogEntity) {
        viewModelScope.launch {
            workoutEngine.removeSet(setLog)
        }
    }

    fun adjustRestTimer(seconds: Int) {
        viewModelScope.launch {
            workoutEngine.adjustRestTimer(seconds)
        }
    }

    fun startRestTimer(durationSeconds: Int) {
        viewModelScope.launch {
            val currentEx = state.value.currentExercise
            workoutEngine.startRestTimer(
                durationSeconds = durationSeconds,
                workoutSessionId = state.value.sessionWithDetails?.session?.id,
                exerciseSessionId = currentEx?.exerciseSession?.id,
                timerType = "REST_EXERCISE"
            )
        }
    }

    fun skipRestTimer() {
        viewModelScope.launch {
            workoutEngine.skipRestTimer()
        }
    }

    fun finishSession() {
        val sessionId = state.value.sessionWithDetails?.session?.id ?: return
        viewModelScope.launch {
            workoutEngine.finishSession(sessionId)
            notificationManager.cancelNotification()
        }
    }

    fun cancelSession() {
        val sessionId = state.value.sessionWithDetails?.session?.id ?: return
        viewModelScope.launch {
            workoutEngine.cancelSession(sessionId)
            notificationManager.cancelNotification()
        }
    }

    // Alternatives
    private val _alternatives = MutableStateFlow<List<com.example.domain.model.ResolvedExercise>>(emptyList())
    val alternatives: StateFlow<List<com.example.domain.model.ResolvedExercise>> = _alternatives

    fun loadAlternatives() {
        val exerciseId = state.value.currentExercise?.exerciseSession?.actualExerciseId ?: return
        viewModelScope.launch {
            val exList = workoutEngine.getAlternativesForExercise(exerciseId)
            val showGifsValue = showGifs.value
            val resolved = exList.map { ex ->
                val override = workoutEngine.dao.getOverrideForExercise(ex.id)
                com.example.domain.engine.ExerciseResolver.resolve(ex, override, showGifsValue)
            }
            _alternatives.value = resolved
        }
    }

    fun swapCurrentExercise(newExerciseId: Long, permanent: Boolean) {
        val session = state.value.sessionWithDetails ?: return
        val currentEx = state.value.currentExercise ?: return
        
        viewModelScope.launch {
            workoutEngine.swapExercise(
                exerciseSessionId = currentEx.exerciseSession.id,
                oldExerciseId = currentEx.exerciseSession.actualExerciseId ?: 0,
                newExerciseId = newExerciseId,
                permanent = permanent,
                templateId = session.session.templateId
            )
            // Clear alternatives list after swapping
            _alternatives.value = emptyList()
        }
    }

    fun clearAlternatives() {
        _alternatives.value = emptyList()
    }

    fun getPremiumInfo(exerciseId: Long): Flow<com.example.presentation.exercises.PremiumExerciseInfo?> = flow {
        val education = workoutEngine.dao.getExerciseEducation(exerciseId)
        val media = workoutEngine.dao.getExerciseMedia(exerciseId)
        val progression = workoutEngine.dao.getExerciseProgression(exerciseId)
        val safety = workoutEngine.dao.getExerciseSafety(exerciseId)
        val substitution = workoutEngine.dao.getExerciseSubstitutionPremium(exerciseId)
        val aiContext = workoutEngine.dao.getExerciseAiContext(exerciseId)
        val biomechanics = workoutEngine.dao.getExerciseBiomechanics(exerciseId)
        val execution = workoutEngine.dao.getExerciseExecution(exerciseId)

        if (education == null && media == null && progression == null && safety == null &&
            substitution == null && aiContext == null && biomechanics == null && execution == null) {
            emit(null)
        } else {
            emit(
                com.example.presentation.exercises.PremiumExerciseInfo(
                    education = education,
                    media = media,
                    progression = progression,
                    safety = safety,
                    substitution = substitution,
                    aiContext = aiContext,
                    biomechanics = biomechanics,
                    execution = execution
                )
            )
        }
    }
}

