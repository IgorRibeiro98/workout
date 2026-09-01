package com.example.domain.engine

import com.example.data.local.SessionCalendarSummary
import com.example.data.local.SetType
import com.example.data.local.WorkoutDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StatsEngine(private val dao: WorkoutDao) {
    fun getWeeklyStatsFlow(startOfWeek: Long, endOfWeek: Long): Flow<WeeklyStats> {
        return dao.getAllCompletedSessionsWithDetailsFlow().map { summaries ->
            val thisWeek = summaries.filter { it.session.startedAt in startOfWeek..endOfWeek }
            val prevWeekStart = startOfWeek - 7 * 24 * 60 * 60 * 1000L
            val prevWeekEnd = startOfWeek - 1 // not overlapping
            val prevWeek = summaries.filter { it.session.startedAt in prevWeekStart..prevWeekEnd }
            
            val durationThisWeek = thisWeek.sumOf { (it.session.finishedAt ?: it.session.startedAt) - it.session.startedAt }
            val setsThisWeek = thisWeek.sumOf { s -> s.exercises.sumOf { ex -> ex.sets.count { it.completed } } }
            val volumeThisWeek = thisWeek.sumOf { s -> s.exercises.sumOf { ex -> VolumeCalculator.calculateVolume(ex.sets).toLong() } }
            
            val volumePrevWeek = prevWeek.sumOf { s -> s.exercises.sumOf { ex -> VolumeCalculator.calculateVolume(ex.sets).toLong() } }
            
            val comparison = if (volumePrevWeek > 0) ((volumeThisWeek - volumePrevWeek).toFloat() / volumePrevWeek) * 100 else 0f
            
            val muscleSets = mutableMapOf<String, Int>()
            val muscleVolume = mutableMapOf<String, Double>()
            
            thisWeek.forEach { session ->
                session.exercises.forEach { ex ->
                    val muscleName = MuscleVisualResolver.getDisplayName(ex.exerciseSession.primaryMuscleSnapshot)
                    val completedWorkingSets = ex.sets.filter { it.completed && it.type != SetType.WARMUP.name }
                    val completedAllSets = ex.sets.filter { it.completed }
                    
                    val setCount = if (completedWorkingSets.isNotEmpty()) completedWorkingSets.size else completedAllSets.size
                    val exVol = com.example.domain.performance.calculator.VolumeCalculator.calculateSetsVolume(ex.sets)
                    
                    muscleSets[muscleName] = (muscleSets[muscleName] ?: 0) + setCount
                    muscleVolume[muscleName] = (muscleVolume[muscleName] ?: 0.0) + exVol
                }
            }
            
            WeeklyStats(
                workoutsCount = thisWeek.size,
                durationMs = durationThisWeek,
                setsCount = setsThisWeek,
                volume = volumeThisWeek,
                volumeComparison = comparison,
                muscleSets = muscleSets,
                muscleVolume = muscleVolume
            )
        }
    }
}

data class WeeklyStats(
    val workoutsCount: Int,
    val durationMs: Long,
    val setsCount: Int,
    val volume: Long,
    val volumeComparison: Float,
    val muscleSets: Map<String, Int>,
    val muscleVolume: Map<String, Double> = emptyMap()
)
