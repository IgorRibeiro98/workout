package com.example.domain.evolution.repository

import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import kotlinx.coroutines.flow.Flow

interface ConsistencyRepository {
    suspend fun getConsistencySummary(): WorkoutConsistencySummary
    suspend fun getFrequencyHistory(): List<WorkoutFrequencyPoint>
    suspend fun getConsistencyProgress(): ConsistencyProgress
    suspend fun getWeeklyConsistencies(): List<WeeklyConsistency>
    suspend fun getGoalSnapshots(): List<WeeklyGoalSnapshot>
    suspend fun setWeeklyGoal(newGoal: Int)

    fun getConsistencySummaryFlow(): Flow<WorkoutConsistencySummary>
    fun getFrequencyHistoryFlow(): Flow<List<WorkoutFrequencyPoint>>
    fun getWorkoutTimestampsFlow(): Flow<List<Long>>
    fun getConsistencyProgressFlow(): Flow<ConsistencyProgress>
    fun getWeeklyConsistenciesFlow(): Flow<List<WeeklyConsistency>>
    fun getGoalSnapshotsFlow(): Flow<List<WeeklyGoalSnapshot>>
}
