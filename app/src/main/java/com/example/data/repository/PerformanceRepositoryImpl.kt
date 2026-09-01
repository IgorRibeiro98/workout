package com.example.data.repository

import com.example.data.local.WorkoutDao
import com.example.domain.evolution.calculator.PerformanceCalculator
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.repository.PerformanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PerformanceRepositoryImpl(
    private val workoutDao: WorkoutDao
) : PerformanceRepository {

    override suspend fun getPerformanceSummary(): WorkoutPerformanceSummary {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateWorkoutPerformanceSummary(sessions)
    }

    override suspend fun getExerciseEvolution(exerciseId: String): ExercisePerformanceEvolution? {
        val all = getAllExercisesEvolution()
        return all.find { it.exerciseId == exerciseId }
    }

    override suspend fun getAllExercisesEvolution(): List<ExercisePerformanceEvolution> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateExerciseEvolutions(sessions)
    }

    override suspend fun getPersonalRecords(): List<PersonalRecord> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculatePersonalRecords(sessions)
    }

    override suspend fun getVolumeHistory(): List<VolumePoint> {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateVolumeHistory(sessions)
    }

    override fun getPerformanceSummaryFlow(): Flow<WorkoutPerformanceSummary> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            PerformanceCalculator.calculateWorkoutPerformanceSummary(sessions)
        }
    }

    override fun getAllExercisesEvolutionFlow(): Flow<List<ExercisePerformanceEvolution>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            PerformanceCalculator.calculateExerciseEvolutions(sessions)
        }
    }

    override fun getPersonalRecordsFlow(): Flow<List<PersonalRecord>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            PerformanceCalculator.calculatePersonalRecords(sessions)
        }
    }

    override fun getVolumeHistoryFlow(): Flow<List<VolumePoint>> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            PerformanceCalculator.calculateVolumeHistory(sessions)
        }
    }
}
