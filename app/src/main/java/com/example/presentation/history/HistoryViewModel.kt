package com.example.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CheckInEntity
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetType
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

/**
 * Period the history is being explored through. Deliberately coarse: the goal is to find your
 * evolution quickly, not to build a query builder.
 */
enum class HistoryPeriod(val label: String) {
    WEEK("Semana"),
    MONTH("Mês"),
    YEAR("Ano"),
    ALL("Tudo");

    /** Start of this period, in millis. */
    fun startTimestamp(now: Long = System.currentTimeMillis()): Long {
        if (this == ALL) return 0L
        
        val zoneId = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        
        val startDate = when (this) {
            WEEK -> date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            MONTH -> date.withDayOfMonth(1)
            YEAR -> date.withDayOfYear(1)
            ALL -> date // won't be reached
        }
        
        return startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}

/**
 * Which lens the period is analysed through.
 */
enum class HistoryAnalysis(val label: String) {
    ALL("Todos"),
    STRENGTH("Força"),
    VOLUME("Volume"),
    MUSCLE_GROUPS("Grupos musculares")
}

/** Heaviest working set recorded for one exercise inside the selected period. */
data class StrengthHighlight(
    val exerciseName: String,
    val maxWeight: Float,
    val repsAtMaxWeight: Int
)

/** Aggregate numbers for the selected period, shared by every analysis lens. */
data class PeriodTotals(
    val sessions: Int = 0,
    val completedSets: Int = 0,
    val volumeKg: Double = 0.0,
    val durationMinutes: Long = 0L
)

/**
 * Os números de **uma** sessão, calculados com a mesma regra do período.
 *
 * Eles existem porque o card da lista recalculava tudo dentro da Composable — e sem excluir
 * aquecimento. Resultado: o card do período e a linha da lista mostravam volumes e contagens
 * diferentes para os mesmos treinos, na mesma tela.
 */
data class SessionMetrics(
    /** Séries concluídas, aquecimento fora. */
    val completedSets: Int = 0,
    /** Volume das séries concluídas por carga × repetições, aquecimento e séries por tempo fora. */
    val volumeKg: Double = 0.0,
    /**
     * A sessão tem série planejada que não foi concluída.
     *
     * Aqui o aquecimento **conta**: "parcial" responde "fiz tudo o que estava planejado?", e a
     * regra de excluir aquecimento é sobre volume, distribuição e recordes — não sobre execução.
     */
    val isPartial: Boolean = false
)

data class HistoryState(
    val calendarSummaries: List<SessionCalendarSummary> = emptyList(),
    val selectedDate: Date = Date(),
    val sessionsForSelectedDate: List<SessionCalendarSummary> = emptyList(),
    val allCompletedSessions: List<SessionCalendarSummary> = emptyList(),
    val muscleSetsDistribution: Map<String, Int> = emptyMap(),
    val muscleVolumeDistribution: Map<String, Double> = emptyMap(),
    val period: HistoryPeriod = HistoryPeriod.MONTH,
    val analysis: HistoryAnalysis = HistoryAnalysis.ALL,
    /** Completed sessions inside [period], newest first. */
    val sessionsInPeriod: List<SessionCalendarSummary> = emptyList(),
    val totals: PeriodTotals = PeriodTotals(),
    /** Os mesmos totais, sobre o histórico inteiro — o cabeçalho da tela. */
    val allTimeTotals: PeriodTotals = PeriodTotals(),
    val strengthHighlights: List<StrengthHighlight> = emptyList(),
    /** Métricas por `session.id`, para a lista não recalcular nada em composição. */
    val sessionMetrics: Map<Long, SessionMetrics> = emptyMap(),
    /** Preferência de vibração, lida pela ViewModel — a tela não fala com o `MainApplication`. */
    val hapticEnabled: Boolean = true
)

class HistoryViewModel(
    private val workoutEngine: WorkoutEngine,
    private val settingsManager: com.example.data.datastore.SettingsManager
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(Date())
    private val _period = MutableStateFlow(HistoryPeriod.MONTH)
    private val _analysis = MutableStateFlow(HistoryAnalysis.ALL)
    private val _summaries = workoutEngine.getCalendarHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _hapticEnabled = settingsManager.hapticEnabledFlow
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val state: StateFlow<HistoryState> = combine(
        _selectedDate,
        _summaries,
        _period,
        _analysis,
        _hapticEnabled
    ) { selected, summaries, period, analysis, hapticEnabled ->
        val filtered = summaries.filter { summary ->
            isSameDay(Date(summary.session.startedAt), selected)
        }

        val rangeStart = period.startTimestamp()
        val summariesInRange = summaries
            .filter { it.session.startedAt >= rangeStart }
            .sortedByDescending { it.session.startedAt }

        val muscleSets = mutableMapOf<String, Int>()
        val muscleVolume = mutableMapOf<String, Double>()
        val bestPerExercise = mutableMapOf<String, StrengthHighlight>()
        var completedSetsTotal = 0
        var volumeTotal = 0.0

        summariesInRange.forEach { summary ->
            summary.exercises.forEach { ex ->
                val muscleName = MuscleVisualResolver.getDisplayName(ex.exerciseSession.primaryMuscleSnapshot)
                // Warm-up sets never count towards volume, distribution or records.
                val completedSets = ex.sets.filter { it.completed && it.type != SetType.WARMUP.name }
                if (completedSets.isEmpty()) return@forEach

                val strengthSets = completedSets.filter { !it.isDurationMode }
                val vol = strengthSets.sumOf { (it.weight * it.repetitions).toDouble() }

                muscleSets[muscleName] = (muscleSets[muscleName] ?: 0) + completedSets.size
                muscleVolume[muscleName] = (muscleVolume[muscleName] ?: 0.0) + vol
                completedSetsTotal += completedSets.size
                volumeTotal += vol

                val heaviest = strengthSets.filter { it.weight > 0f }.maxByOrNull { it.weight }
                if (heaviest != null) {
                    val name = ex.exerciseSession.exerciseNameSnapshot
                    val current = bestPerExercise[name]
                    if (current == null || heaviest.weight > current.maxWeight) {
                        bestPerExercise[name] = StrengthHighlight(
                            exerciseName = name,
                            maxWeight = heaviest.weight,
                            repsAtMaxWeight = heaviest.repetitions
                        )
                    }
                }
            }
        }

        val durationMinutes = summariesInRange.sumOf { durationMinutesOf(it) }

        // Uma passada sobre o histórico inteiro, com a mesma regra: é ela que alimenta o card de
        // cada treino e o cabeçalho da tela. Eles recalculavam isso em composição, sem excluir
        // aquecimento, e por isso divergiam do card do período.
        val metrics = summaries.associate { it.session.id to metricsOf(it) }

        HistoryState(
            calendarSummaries = summaries,
            selectedDate = selected,
            sessionsForSelectedDate = filtered,
            allCompletedSessions = summaries.sortedByDescending { it.session.startedAt },
            muscleSetsDistribution = muscleSets,
            muscleVolumeDistribution = muscleVolume,
            period = period,
            analysis = analysis,
            sessionsInPeriod = summariesInRange,
            totals = PeriodTotals(
                sessions = summariesInRange.size,
                completedSets = completedSetsTotal,
                volumeKg = volumeTotal,
                durationMinutes = durationMinutes
            ),
            allTimeTotals = PeriodTotals(
                sessions = summaries.size,
                completedSets = metrics.values.sumOf { it.completedSets },
                volumeKg = metrics.values.sumOf { it.volumeKg },
                durationMinutes = summaries.sumOf { durationMinutesOf(it) }
            ),
            strengthHighlights = bestPerExercise.values
                .sortedByDescending { it.maxWeight }
                .take(MAX_STRENGTH_HIGHLIGHTS),
            sessionMetrics = metrics,
            hapticEnabled = hapticEnabled
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState())

    fun selectDate(date: Date) {
        _selectedDate.value = date
    }

    fun setPeriod(period: HistoryPeriod) {
        _period.value = period
    }

    fun setAnalysis(analysis: HistoryAnalysis) {
        _analysis.value = analysis
    }

    fun deleteSession(session: WorkoutSessionEntity) {
        viewModelScope.launch {
            workoutEngine.deleteHistoricalSession(session)
        }
    }

    fun updateCheckInTime(checkIn: CheckInEntity, newCheckInTime: Long, newCheckOutTime: Long?, gym: String?) {
        viewModelScope.launch {
            workoutEngine.updateCheckInDetails(checkIn.copy(
                checkInTime = newCheckInTime,
                checkOutTime = newCheckOutTime,
                gymName = gym
            ))
        }
    }

    /**
     * Salva a academia do check-in de uma sessão do histórico.
     *
     * Montar a `CheckInEntity` é trabalho de domínio, e estava dentro do `onClick` de um diálogo.
     *
     * ATENÇÃO: `WorkoutEngine.updateCheckInDetails` só **atualiza**. Numa sessão que nunca teve
     * check-in, o `UPDATE` não encontra linha e nada é gravado — comportamento que já era assim
     * antes desta função existir, e que só um caminho de inserção no motor resolve.
     */
    fun saveCheckInGym(summary: SessionCalendarSummary, gymName: String?) {
        val existing = summary.checkIn ?: CheckInEntity(
            sessionId = summary.session.id,
            checkInTime = summary.session.startedAt,
            checkOutTime = summary.session.finishedAt,
            gymName = ""
        )
        updateCheckInTime(
            checkIn = existing,
            newCheckInTime = existing.checkInTime,
            newCheckOutTime = existing.checkOutTime ?: summary.session.finishedAt,
            gym = gymName?.takeIf { it.isNotBlank() }
        )
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun durationMinutesOf(summary: SessionCalendarSummary): Long {
        val finished = summary.session.finishedAt ?: summary.session.startedAt
        return ((finished - summary.session.startedAt) / 60000L).coerceAtLeast(0L)
    }

    private fun metricsOf(summary: SessionCalendarSummary): SessionMetrics {
        var completed = 0
        var volume = 0.0
        var plannedTotal = 0
        var plannedCompleted = 0
        summary.exercises.forEach { ex ->
            ex.sets.forEach { set ->
                plannedTotal++
                if (set.completed) plannedCompleted++
                if (set.completed && set.type != SetType.WARMUP.name) {
                    completed++
                    if (!set.isDurationMode) volume += (set.weight * set.repetitions).toDouble()
                }
            }
        }
        return SessionMetrics(
            completedSets = completed,
            volumeKg = volume,
            isPartial = plannedTotal > 0 && plannedCompleted < plannedTotal
        )
    }

    companion object {
        private const val MAX_STRENGTH_HIGHLIGHTS = 8
    }
}
