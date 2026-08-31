package com.example.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CheckInEntity
import com.example.data.local.SessionCalendarSummary
import com.example.data.local.WorkoutSessionEntity
import com.example.domain.engine.MuscleVisualResolver
import com.example.domain.engine.VolumeCalculator
import com.example.domain.engine.WorkoutEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

data class HistoryState(
    val calendarSummaries: List<SessionCalendarSummary> = emptyList(),
    val selectedDate: Date = Date(),
    val sessionsForSelectedDate: List<SessionCalendarSummary> = emptyList(),
    val allCompletedSessions: List<SessionCalendarSummary> = emptyList(),
    val muscleSetsDistribution: Map<String, Int> = emptyMap(),
    val muscleVolumeDistribution: Map<String, Double> = emptyMap(),
    val volumeTimeRange: String = "Esta semana" // "Esta semana", "Este mês", "Tudo"
)

class HistoryViewModel(
    private val workoutEngine: WorkoutEngine
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(Date())
    private val _volumeTimeRange = MutableStateFlow("Esta semana")
    private val _summaries = workoutEngine.getCalendarHistoryFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state: StateFlow<HistoryState> = combine(_selectedDate, _summaries, _volumeTimeRange) { selected, summaries, timeRange ->
        val filtered = summaries.filter { summary ->
            isSameDay(Date(summary.session.startedAt), selected)
        }

        val muscleSets = mutableMapOf<String, Int>()
        val muscleVolume = mutableMapOf<String, Double>()
        
        val now = Calendar.getInstance()
        val rangeStart = Calendar.getInstance().apply {
            when (timeRange) {
                "Esta semana" -> {
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    set(Calendar.HOUR_OF_DAY, 0)
                }
                "Este mês" -> {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                }
                else -> set(Calendar.YEAR, 1970) // Tudo
            }
        }.timeInMillis

        val summariesInRange = summaries.filter { it.session.startedAt >= rangeStart }

        summariesInRange.forEach { summary ->
            summary.exercises.forEach { ex ->
                val muscleName = MuscleVisualResolver.getDisplayName(ex.exerciseSession.primaryMuscleSnapshot)
                // STRICTLY EXCLUDE WARMUP
                val completedSets = ex.sets.filter { it.completed && it.type != com.example.data.local.SetType.WARMUP.name }
                val setsCount = completedSets.size
                val vol = completedSets.sumOf { (it.weight * it.repetitions).toDouble() }

                if (setsCount > 0) {
                    muscleSets[muscleName] = (muscleSets[muscleName] ?: 0) + setsCount
                    muscleVolume[muscleName] = (muscleVolume[muscleName] ?: 0.0) + vol
                }
            }
        }

        HistoryState(
            calendarSummaries = summaries,
            selectedDate = selected,
            sessionsForSelectedDate = filtered,
            allCompletedSessions = summaries.sortedByDescending { it.session.startedAt },
            muscleSetsDistribution = muscleSets,
            muscleVolumeDistribution = muscleVolume,
            volumeTimeRange = timeRange
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState())

    fun selectDate(date: Date) {
        _selectedDate.value = date
    }
    
    fun setVolumeTimeRange(range: String) {
        _volumeTimeRange.value = range
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
}
