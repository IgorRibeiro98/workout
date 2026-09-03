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
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (this) {
            WEEK -> cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
            YEAR -> cal.set(Calendar.DAY_OF_YEAR, 1)
            ALL -> Unit
        }
        return cal.timeInMillis
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
    val strengthHighlights: List<StrengthHighlight> = emptyList()
)

class HistoryViewModel(
    private val workoutEngine: WorkoutEngine
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(Date())
    private val _period = MutableStateFlow(HistoryPeriod.MONTH)
    private val _analysis = MutableStateFlow(HistoryAnalysis.ALL)
    private val _summaries = workoutEngine.getCalendarHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state: StateFlow<HistoryState> = combine(
        _selectedDate,
        _summaries,
        _period,
        _analysis
    ) { selected, summaries, period, analysis ->
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

        val durationMinutes = summariesInRange.sumOf { s ->
            val finished = s.session.finishedAt ?: s.session.startedAt
            ((finished - s.session.startedAt) / 60000L).coerceAtLeast(0L)
        }

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
            strengthHighlights = bestPerExercise.values
                .sortedByDescending { it.maxWeight }
                .take(MAX_STRENGTH_HIGHLIGHTS)
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

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    companion object {
        private const val MAX_STRENGTH_HIGHLIGHTS = 8
    }
}
