package com.example.data.repository

import com.example.data.local.WorkoutDao
import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.domain.evolution.repository.ConsistencyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConsistencyRepositoryImpl(
    private val workoutDao: WorkoutDao
) : ConsistencyRepository {

    override suspend fun getConsistencySummary(): WorkoutConsistencySummary {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return ConsistencyCalculator.calculateConsistencySummary(sessions.map { it.session.startedAt })
    }

    override suspend fun getFrequencyHistory(): List<WorkoutFrequencyPoint> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return ConsistencyCalculator.calculateFrequencyHistory(sessions.map { it.session.startedAt })
    }

    override fun getConsistencySummaryFlow(): Flow<WorkoutConsistencySummary> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            ConsistencyCalculator.calculateConsistencySummary(sessions.map { it.session.startedAt })
        }
    }

    override fun getFrequencyHistoryFlow(): Flow<List<WorkoutFrequencyPoint>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            ConsistencyCalculator.calculateFrequencyHistory(sessions.map { it.session.startedAt })
        }
    }

    override fun getWorkoutTimestampsFlow(): Flow<List<Long>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            sessions.map { it.session.startedAt }
        }
    }
}
