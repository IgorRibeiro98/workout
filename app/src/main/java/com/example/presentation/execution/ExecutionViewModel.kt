package com.example.presentation.execution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.ExerciseSessionWithSets
import com.example.data.local.SessionWithDetails
import com.example.data.local.SetLogEntity
import com.example.domain.engine.WorkoutEngine
import com.example.service.WorkoutNotificationManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ExecutionState(
    val sessionWithDetails: SessionWithDetails? = null,
    val currentExerciseIndex: Int = 0,
    val isLoading: Boolean = true,
    val previousExecutionSets: List<SetLogEntity> = emptyList()
) {
    val currentExercise: ExerciseSessionWithSets?
        get() = sessionWithDetails?.exercises?.getOrNull(currentExerciseIndex)

    val isLastExercise: Boolean
        get() = currentExerciseIndex == (sessionWithDetails?.exercises?.size ?: 1) - 1

    val isFirstExercise: Boolean
        get() = currentExerciseIndex == 0
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

    private val _currentExerciseIndex = MutableStateFlow(0)
    
    private val _previousExecutionSets = MutableStateFlow<List<SetLogEntity>>(emptyList())

    val state: StateFlow<ExecutionState> = combine(
        workoutEngine.activeSessionWithDetailsFlow,
        _currentExerciseIndex,
        _previousExecutionSets
    ) { sessionWithDetails, index, previousSets ->
        // Ensure index is within bounds if exercises change
        val safeIndex = if (sessionWithDetails != null && sessionWithDetails.exercises.isNotEmpty()) {
            index.coerceIn(0, sessionWithDetails.exercises.size - 1)
        } else {
            0
        }
        
        ExecutionState(
            sessionWithDetails = sessionWithDetails,
            currentExerciseIndex = safeIndex,
            isLoading = sessionWithDetails == null,
            previousExecutionSets = previousSets
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExecutionState())

    val restTimerTarget = workoutEngine.restTimerTarget
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            state.map { it.currentExercise?.exerciseSession?.actualExerciseId }.distinctUntilChanged().collect { exerciseId ->
                if (exerciseId != null) {
                    val prevSets = workoutEngine.getLastExecutionSetsForExercise(exerciseId)
                    _previousExecutionSets.value = prevSets
                } else {
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
        viewModelScope.launch {
            workoutEngine.updateSet(setLog.copy(completed = true, finishedAt = System.currentTimeMillis()))
        }
    }
    
    fun uncompleteSet(setLog: SetLogEntity) {
        viewModelScope.launch {
            workoutEngine.updateSet(setLog.copy(completed = false, finishedAt = null))
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

    // Alternatives
    private val _alternatives = MutableStateFlow<List<com.example.data.local.ExerciseEntity>>(emptyList())
    val alternatives: StateFlow<List<com.example.data.local.ExerciseEntity>> = _alternatives

    fun loadAlternatives() {
        val exerciseId = state.value.currentExercise?.exerciseSession?.actualExerciseId ?: return
        viewModelScope.launch {
            _alternatives.value = workoutEngine.getAlternativesForExercise(exerciseId)
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
}
