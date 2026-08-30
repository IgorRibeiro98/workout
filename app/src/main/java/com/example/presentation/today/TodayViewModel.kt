package com.example.presentation.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

data class SequenceItemData(
    val template: WorkoutTemplateEntity,
    val isPast: Boolean,
    val isCurrent: Boolean
)

data class TodayState(
    val nextTemplate: WorkoutTemplateEntity? = null,
    val nextTemplateExerciseCount: Int = 0,
    val predominantMuscles: List<String> = emptyList(),
    val weeklyCompleted: Int = 0,
    val weeklyGoal: Int = 5,
    val allTemplates: List<WorkoutTemplateEntity> = emptyList(),
    val activeSession: WorkoutSessionEntity? = null,
    val activeCheckIn: com.example.data.local.CheckInEntity? = null,
    val lastSession: WorkoutSessionEntity? = null,
    val stats: com.example.domain.engine.WeeklyStats? = null,
    val sequence: List<SequenceItemData> = emptyList()
)

class TodayViewModel(
    private val repository: WorkoutRepository,
    private val settingsManager: SettingsManager,
    private val workoutEngine: WorkoutEngine
) : ViewModel() {

    private val _state = MutableStateFlow(TodayState())
    val state: StateFlow<TodayState> = _state.asStateFlow()

    init {
        loadTodayData()
    }

    private fun loadTodayData() {
        viewModelScope.launch {
            val startOfWeek = getStartOfWeekTimestamp()
            val endOfWeek = startOfWeek + 7 * 24 * 60 * 60 * 1000L - 1
            val statsEngine = com.example.domain.engine.StatsEngine(repository.dao)

            combine(
                repository.currentProgram,
                repository.getWeeklyCompletedSessionsCount(startOfWeek),
                settingsManager.weeklyGoalFlow,
                workoutEngine.activeSessionFlow,
                workoutEngine.activeCheckInFlow,
                statsEngine.getWeeklyStatsFlow(startOfWeek, endOfWeek),
                settingsManager.overrideTemplateIdFlow
            ) { args ->
                val program = args[0] as WorkoutProgramEntity?
                val weeklyCount = args[1] as Int
                val goal = args[2] as Int
                val activeSession = args[3] as WorkoutSessionEntity?
                val activeCheckIn = args[4] as com.example.data.local.CheckInEntity?
                val stats = args[5] as com.example.domain.engine.WeeklyStats
                val overrideTemplateId = args[6] as Long?

                if (program != null) {
                    val templates = repository.getTemplatesForProgram(program.id).first()
                    val lastSession = repository.getLastCompletedSession()
                    
                    var nextTemplate: WorkoutTemplateEntity? = templates.firstOrNull()
                    var nextTemplateIndex = 0
                    
                    if (lastSession != null && lastSession.templateId != null && templates.isNotEmpty()) {
                        val lastIndex = templates.indexOfFirst { it.id == lastSession.templateId }
                        if (lastIndex != -1) {
                            nextTemplateIndex = (lastIndex + 1) % templates.size
                            nextTemplate = templates[nextTemplateIndex]
                        }
                    }
                    
                    val sequenceList = mutableListOf<SequenceItemData>()
                    if (templates.isNotEmpty()) {
                        for (i in -1..2) {
                            val idx = (nextTemplateIndex + i + templates.size) % templates.size
                            val template = templates[idx]
                            sequenceList.add(
                                SequenceItemData(
                                    template = template,
                                    isPast = i < 0,
                                    isCurrent = i == 0
                                )
                            )
                        }
                    }
                    
                    if (overrideTemplateId != null) {
                        val overrideTpl = templates.find { it.id == overrideTemplateId }
                        if (overrideTpl != null) {
                            nextTemplate = overrideTpl
                        }
                    }
                    
                    var exerciseCount = 0
                    var predominantMuscles = emptyList<String>()
                    if (nextTemplate != null) {
                        val exercises = repository.dao.getTemplateExercisesWithDetails(nextTemplate.id)
                        exerciseCount = exercises.size
                        predominantMuscles = com.example.domain.engine.MuscleVisualResolver.getPredominantMuscles(
                            exercises.map { it.exercise.primaryMuscle }
                        )
                    }
                    
                    _state.value = TodayState(
                        nextTemplate = nextTemplate,
                        nextTemplateExerciseCount = exerciseCount,
                        predominantMuscles = predominantMuscles,
                        weeklyCompleted = weeklyCount,
                        weeklyGoal = goal,
                        allTemplates = templates,
                        activeSession = activeSession,
                        activeCheckIn = activeCheckIn,
                        lastSession = lastSession,
                        stats = stats,
                        sequence = sequenceList
                    )
                } else {
                    _state.value = TodayState(weeklyGoal = goal, activeSession = activeSession, activeCheckIn = activeCheckIn, stats = stats)
                }
            }.collect()
        }
    }

    fun startWorkout(templateId: Long) {
        viewModelScope.launch {
            workoutEngine.startSession(templateId)
        }
    }

    fun manualCheckIn() {
        viewModelScope.launch {
            workoutEngine.manualCheckIn()
        }
    }

    fun manualCheckOut() {
        viewModelScope.launch {
            workoutEngine.manualCheckOut()
        }
    }

    fun finishActiveWorkout(sessionId: Long) {
        viewModelScope.launch {
            workoutEngine.finishSession(sessionId)
            settingsManager.setOverrideTemplateId(null)
        }
    }

    fun cancelActiveWorkout(sessionId: Long) {
        viewModelScope.launch {
            workoutEngine.cancelSession(sessionId)
            settingsManager.setOverrideTemplateId(null)
        }
    }
    
    fun overrideTodayTemplate(templateId: Long?) {
        viewModelScope.launch {
            settingsManager.setOverrideTemplateId(templateId)
        }
    }

    private fun getStartOfWeekTimestamp(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis
    }
}
