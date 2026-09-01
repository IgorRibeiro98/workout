package com.example.domain.evolution.repository

import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import kotlinx.coroutines.flow.Flow

interface PerformanceRepository {
    suspend fun getPerformanceSummary(): WorkoutPerformanceSummary
    suspend fun getExerciseEvolution(exerciseId: String): ExercisePerformanceEvolution?
    suspend fun getAllExercisesEvolution(): List<ExercisePerformanceEvolution>
    suspend fun getPersonalRecords(): List<PersonalRecord>
    suspend fun getVolumeHistory(): List<VolumePoint>

    fun getPerformanceSummaryFlow(): Flow<WorkoutPerformanceSummary>
    fun getAllExercisesEvolutionFlow(): Flow<List<ExercisePerformanceEvolution>>
    fun getPersonalRecordsFlow(): Flow<List<PersonalRecord>>
    fun getVolumeHistoryFlow(): Flow<List<VolumePoint>>
}
