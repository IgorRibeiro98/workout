package com.example.presentation.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.datastore.SettingsManager
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.WorkoutEngine
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
    val consistencyProgress: ConsistencyProgress? = null,
    /**
     * Duração estimada do próximo treino, em minutos.
     *
     * A regra é da ViewModel e não da Composable: quanto tempo um treino leva é conhecimento de
     * domínio, e escrito dentro do `Text` ele já havia virado um número mágico invisível.
     */
    val estimatedMinutes: Int = DEFAULT_ESTIMATED_MINUTES
)

/**
 * Um acontecimento pontual da Home — o que a tela precisa **fazer** uma vez, e não o que ela
 * mostra. Estado não serve aqui: "treino finalizado" mostrado de novo numa recomposição seria um
 * aviso repetido, e navegar a partir de estado abriria a execução duas vezes.
 */
sealed interface TodayEvent {
    /** A sessão existe e está ativa: a tela pode abrir a execução. */
    object WorkoutStarted : TodayEvent
    data class WorkoutStartFailed(val message: String) : TodayEvent
    object WorkoutFinished : TodayEvent
    data class WorkoutFinishFailed(val message: String) : TodayEvent
}

/** Duração estimada quando ainda não se sabe quantos exercícios o treino tem. */
private const val DEFAULT_ESTIMATED_MINUTES = 45

/** Minutos por exercício e o aquecimento/deslocamento somados uma vez por treino. */
private const val MINUTES_PER_EXERCISE = 8
private const val FIXED_OVERHEAD_MINUTES = 10

private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * Teto de espera até reavaliar a semana corrente.
 *
 * A espera normal vai até a virada da semana, mas mudança de fuso, horário de verão e ajuste manual
 * do relógio movem o alvo. Acordar de hora em hora custa nada — `distinctUntilChanged` garante que
 * nada a jusante recarregue enquanto o valor não muda.
 */
private const val WEEK_RECHECK_CAP_MILLIS = 60L * 60 * 1000

/** Estimativa de duração do treino a partir da contagem de exercícios. */
internal fun estimateWorkoutMinutes(exerciseCount: Int): Int =
    if (exerciseCount > 0) exerciseCount * MINUTES_PER_EXERCISE + FIXED_OVERHEAD_MINUTES
    else DEFAULT_ESTIMATED_MINUTES

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

    private val _isStartingWorkout = MutableStateFlow(false)

    /** Verdadeiro enquanto a sessão está sendo criada. O segundo toque no botão não faz nada. */
    val isStartingWorkout: StateFlow<Boolean> = _isStartingWorkout.asStateFlow()

    private val _isFinishingWorkout = MutableStateFlow(false)

    /** Verdadeiro enquanto o treino está sendo finalizado. */
    val isFinishingWorkout: StateFlow<Boolean> = _isFinishingWorkout.asStateFlow()

    private val _events = MutableSharedFlow<TodayEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TodayEvent> = _events.asSharedFlow()

    val xpGainFlow = xpTransactionRepository?.newTransactions

    /**
     * O início da semana corrente, reavaliado sozinho.
     *
     * Esta ViewModel vive enquanto a rota "Hoje" vive, o que na prática é o processo inteiro. Com o
     * valor calculado uma vez no `init`, o app aberto na virada de domingo para segunda continuava
     * contando treinos e volume da semana passada até alguém matar o processo.
     */
    private val weekStartFlow: Flow<Long> = flow {
        while (true) {
            val start = getStartOfWeekTimestamp()
            emit(start)
            val untilNextWeek = (start + WEEK_MILLIS) - System.currentTimeMillis()
            delay(untilNextWeek.coerceIn(1_000L, WEEK_RECHECK_CAP_MILLIS))
        }
    }.distinctUntilChanged()

    init {
        loadTodayData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadTodayData() {
        viewModelScope.launch {
            xpTransactionRepository?.getUserProgress()?.collect { progress ->
                _state.update { it.copy(userProgress = progress) }
            }
        }

        viewModelScope.launch {
            val statsEngine = com.example.domain.engine.StatsEngine(repository.dao)

            val bodyMeasurementsFlow = bodyMeasurementRepository?.allMeasurements ?: flowOf(emptyList())
            val consistencyProgressFlow = consistencyRepository?.getConsistencyProgressFlow() ?: flowOf(null)

            // `flatMapLatest`: quando a semana vira, a coleta anterior é cancelada e o pipeline é
            // remontado com a nova janela. Durante a semana isto não acontece nenhuma vez.
            weekStartFlow.flatMapLatest { startOfWeek ->
            val endOfWeek = startOfWeek + WEEK_MILLIS - 1
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
                consistencyProgressFlow
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
                val consistencyProgress = args[10] as ConsistencyProgress?

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
                
                val streakWeeks = consistencyProgress?.currentStreakWeeks ?: 0
                val effectiveWeeklyGoal = consistencyProgress?.currentWeekGoal ?: goal
                val effectiveWeeklyCompleted = consistencyProgress?.currentWeekCompleted ?: weeklyCount

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
                        consistencyProgress = consistencyProgress,
                        estimatedMinutes = estimateWorkoutMinutes(exerciseCount)
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
            }
            }.collect()
        }
    }

    /**
     * Cria a sessão e só então avisa a tela para abrir a execução.
     *
     * Antes disto a Composable navegava na mesma linha da chamada, sem esperar nada: um toque duplo
     * empilhava duas rotas de execução, e um erro ao criar a sessão levava o usuário para uma tela
     * de treino que não existia.
     */
    fun startWorkout(templateId: Long) {
        if (_isStartingWorkout.value) return
        _isStartingWorkout.value = true
        viewModelScope.launch {
            try {
                workoutEngine.startSession(templateId)
                _events.tryEmit(TodayEvent.WorkoutStarted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    TodayEvent.WorkoutStartFailed(
                        e.message ?: "Não foi possível iniciar o treino."
                    )
                )
            } finally {
                _isStartingWorkout.value = false
            }
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

    /**
     * Finaliza a sessão ativa.
     *
     * O aviso de "Treino finalizado." sai daqui, depois de o motor confirmar: na tela, ele era
     * mostrado na mesma linha do pedido, antes de qualquer coisa ter acontecido — e aparecia igual
     * se a finalização falhasse.
     */
    fun finishActiveWorkout(sessionId: Long) {
        if (_isFinishingWorkout.value) return
        _isFinishingWorkout.value = true
        viewModelScope.launch {
            try {
                workoutEngine.finishSession(sessionId)
                settingsManager.setOverrideTemplateId(null)
                _events.tryEmit(TodayEvent.WorkoutFinished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(
                    TodayEvent.WorkoutFinishFailed(
                        e.message ?: "Não foi possível finalizar o treino."
                    )
                )
            } finally {
                _isFinishingWorkout.value = false
            }
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
