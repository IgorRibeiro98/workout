package com.example.domain.evolution.repository

import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.model.performance.chart.StrengthPoint
import com.example.domain.performance.model.volume.VolumeSummary
import kotlinx.coroutines.flow.Flow

interface PerformanceRepository {
    suspend fun getPerformanceSummary(): WorkoutPerformanceSummary
    suspend fun getVolumeSummary(): VolumeSummary
    suspend fun getExerciseEvolution(exerciseId: String): ExercisePerformanceEvolution?
    suspend fun getAllExercisesEvolution(): List<ExercisePerformanceEvolution>
    suspend fun getPersonalRecords(): List<PersonalRecord>
    suspend fun getVolumeHistory(): List<VolumePoint>
    suspend fun getExerciseStrengthHistory(exerciseId: String): List<StrengthPoint>

    fun getPerformanceSummaryFlow(): Flow<WorkoutPerformanceSummary>
    fun getVolumeSummaryFlow(): Flow<VolumeSummary>
    fun getAllExercisesEvolutionFlow(): Flow<List<ExercisePerformanceEvolution>>
    fun getPersonalRecordsFlow(): Flow<List<PersonalRecord>>
    fun getVolumeHistoryFlow(): Flow<List<VolumePoint>>
    fun getExerciseStrengthHistoryFlow(exerciseId: String): Flow<List<StrengthPoint>>
}
