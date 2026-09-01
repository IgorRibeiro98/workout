package com.example.domain.evolution.repository

import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import kotlinx.coroutines.flow.Flow

interface ConsistencyRepository {
    suspend fun getConsistencySummary(): WorkoutConsistencySummary
    suspend fun getFrequencyHistory(): List<WorkoutFrequencyPoint>

    fun getConsistencySummaryFlow(): Flow<WorkoutConsistencySummary>
    fun getFrequencyHistoryFlow(): Flow<List<WorkoutFrequencyPoint>>
}
