package com.example.presentation.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

data class SequenceItemData(
    val template: WorkoutTemplateEntity,
    val isPast: Boolean,
    val isCurrent: Boolean
)

/**
 * The single thing worth celebrating on the Home screen.
 *
 * "Hoje" answers "qual treino eu faço agora?" — a dashboard belongs to Evolução. One highlight is
 * the most the screen shows about the past, and only when there is something real to show.
 */
data class TodayHighlight(
    val emoji: String,
    val text: String
)

data class TodayState(
    val nextTemplate: WorkoutTemplateEntity? = null,
    val nextTemplateExerciseCount: Int = 0,
    val predominantMuscles: List<String> = emptyList(),
    val weeklyCompleted: Int = 0,
    val weeklyGoal: Int = 3,
    val allTemplates: List<WorkoutTemplateEntity> = emptyList(),
    val activeSession: WorkoutSessionEntity? = null,
    val activeCheckIn: com.example.data.local.CheckInEntity? = null,
    val lastSession: WorkoutSessionEntity? = null,
    val stats: com.example.domain.engine.WeeklyStats? = null,
    val sequence: List<SequenceItemData> = emptyList(),
    val activeWeeksCount: Int = 0,
    val totalWorkoutsCompleted: Int = 0,
    val latestBodyWeightKg: Float? = null,
    val weightChangeKg: Float? = null,
    val recentMilestoneText: String? = null,
    val weeklyVolumeKg: Float = 0f,
    val streakWeeks: Int = 0,
    val highlight: TodayHighlight? = null,
    val userProgress: com.example.domain.gamification.model.UserProgress? = null,
    val consistencyProgress: ConsistencyProgress? = null
)

class TodayViewModel(
    private val repository: WorkoutRepository,
    private val settingsManager: SettingsManager,
    private val workoutEngine: WorkoutEngine,
    private val bodyMeasurementRepository: com.example.data.repository.BodyMeasurementRepository? = null,
    private val xpTransactionRepository: com.example.domain.gamification.repository.XpTransactionRepository? = null,
    private val consistencyRepository: ConsistencyRepository? = null
) : ViewModel() {

    private val _state = MutableStateFlow(TodayState())
    val state: StateFlow<TodayState> = _state.asStateFlow()
    
    val xpGainFlow = xpTransactionRepository?.newTransactions

    init {
        loadTodayData()
    }

    private fun loadTodayData() {
        viewModelScope.launch {
            xpTransactionRepository?.getUserProgress()?.collect { progress ->
                _state.update { it.copy(userProgress = progress) }
            }
        }

        viewModelScope.launch {
            val startOfWeek = getStartOfWeekTimestamp()
            val endOfWeek = startOfWeek + 7 * 24 * 60 * 60 * 1000L - 1
            val statsEngine = com.example.domain.engine.StatsEngine(repository.dao)

            val bodyMeasurementsFlow = bodyMeasurementRepository?.allMeasurements ?: flowOf(emptyList())
            val goalSnapshotsFlow = consistencyRepository?.getGoalSnapshotsFlow() ?: flowOf(emptyList())

            combine(
                repository.currentProgram,
                repository.getWeeklyCompletedSessionsCount(startOfWeek),
                settingsManager.weeklyGoalFlow,
                workoutEngine.activeSessionFlow,
                workoutEngine.activeCheckInFlow,
                statsEngine.getWeeklyStatsFlow(startOfWeek, endOfWeek),
                settingsManager.overrideTemplateIdFlow,
                repository.dao.getAllCompletedSessionsWithDetailsFlow(),
                repository.dao.getRecentPRsFlow(),
                bodyMeasurementsFlow,
                goalSnapshotsFlow
            ) { args ->
                val program = args[0] as WorkoutProgramEntity?
                val weeklyCount = args[1] as Int
                val goal = args[2] as Int
                val activeSession = args[3] as WorkoutSessionEntity?
                val activeCheckIn = args[4] as com.example.data.local.CheckInEntity?
                val stats = args[5] as com.example.domain.engine.WeeklyStats
                val overrideTemplateId = args[6] as Long?
                val completedSessions = args[7] as List<com.example.data.local.SessionCalendarSummary>
                val recentPRs = args[8] as List<com.example.data.local.PersonalRecordEntity>
                val bodyMeasurements = args[9] as List<com.example.data.local.BodyMeasurementEntity>
                @Suppress("UNCHECKED_CAST")
                val goalSnapshots = args[10] as List<com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot>

                val totalWorkouts = completedSessions.size
                val timestamps = completedSessions.map { it.session.startedAt }
                val activeWeeks = if (completedSessions.isNotEmpty()) {
                    val minTs = timestamps.minOrNull() ?: System.currentTimeMillis()
                    val diffDays = ((System.currentTimeMillis() - minTs) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
                    (diffDays / 7 + 1).toInt()
                } else {
                    0
                }

                val recentMilestone = TodayHighlightCalculator.formatRecentMilestone(recentPRs)
                
                // Calculate weekly consistencies & progress using canonical ConsistencyCalculator
                val weeklyConsistencies = ConsistencyCalculator.calculateWeeklyConsistencies(
                    timestamps = timestamps,
                    goalSnapshots = goalSnapshots,
                    defaultGoal = goal,
                    referenceDate = LocalDate.now(),
                    zoneId = ZoneId.systemDefault()
                )
                val consistencyProgress = ConsistencyCalculator.calculateProgress(weeklyConsistencies, LocalDate.now())
                val streakWeeks = consistencyProgress.currentStreakWeeks
                val currentWeekConsistency = weeklyConsistencies.lastOrNull()
                val effectiveWeeklyGoal = currentWeekConsistency?.goal ?: goal
                val effectiveWeeklyCompleted = currentWeekConsistency?.completedWorkouts ?: weeklyCount

                val highlight = TodayHighlightCalculator.buildHighlight(streakWeeks, recentMilestone)

                val latestWeight = bodyMeasurements.firstOrNull()?.weightKg
                val firstWeight = bodyMeasurements.lastOrNull()?.weightKg
                val weightDiff = if (latestWeight != null && firstWeight != null && bodyMeasurements.size >= 2) {
                    latestWeight - firstWeight
                } else null

                val weeklyVolume = stats.volume.toFloat()

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
                        weeklyCompleted = effectiveWeeklyCompleted,
                        weeklyGoal = effectiveWeeklyGoal,
                        allTemplates = templates,
                        activeSession = activeSession,
                        activeCheckIn = activeCheckIn,
                        lastSession = lastSession,
                        stats = stats,
                        sequence = sequenceList,
                        activeWeeksCount = activeWeeks,
                        totalWorkoutsCompleted = totalWorkouts,
                        latestBodyWeightKg = latestWeight,
                        weightChangeKg = weightDiff,
                        recentMilestoneText = recentMilestone,
                        weeklyVolumeKg = weeklyVolume,
                        streakWeeks = streakWeeks,
                        highlight = highlight,
                        userProgress = _state.value.userProgress,
                        consistencyProgress = consistencyProgress
                    )
                } else {
                    _state.value = TodayState(
                        weeklyCompleted = effectiveWeeklyCompleted,
                        weeklyGoal = effectiveWeeklyGoal,
                        activeSession = activeSession,
                        activeCheckIn = activeCheckIn,
                        stats = stats,
                        activeWeeksCount = activeWeeks,
                        totalWorkoutsCompleted = totalWorkouts,
                        latestBodyWeightKg = latestWeight,
                        weightChangeKg = weightDiff,
                        recentMilestoneText = recentMilestone,
                        weeklyVolumeKg = weeklyVolume,
                        streakWeeks = streakWeeks,
                        highlight = highlight,
                        userProgress = _state.value.userProgress,
                        consistencyProgress = consistencyProgress
                    )
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

    fun updateWeeklyGoal(newGoal: Int) {
        viewModelScope.launch {
            consistencyRepository?.setWeeklyGoal(newGoal) ?: settingsManager.setWeeklyGoal(newGoal)
        }
    }

    private fun getStartOfWeekTimestamp(): Long {
        val zoneId = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zoneId)
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return monday.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}
