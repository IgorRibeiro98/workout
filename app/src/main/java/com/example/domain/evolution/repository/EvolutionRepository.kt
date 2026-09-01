package com.example.domain.evolution.repository

import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.WeightEvolution
import kotlinx.coroutines.flow.Flow

interface EvolutionRepository {
    suspend fun getEvolutionSummary(): EvolutionSummary
    suspend fun getWeightEvolution(): WeightEvolution
    suspend fun getPerformanceEvolution(): PerformanceEvolution
    suspend fun getConsistencyMetrics(): ConsistencyMetrics
    suspend fun getBodyMeasurements(): List<BodyMeasurement>

    fun getEvolutionSummaryFlow(): Flow<EvolutionSummary>
    fun getWeightEvolutionFlow(): Flow<WeightEvolution>
    fun getPerformanceEvolutionFlow(): Flow<PerformanceEvolution>
    fun getConsistencyMetricsFlow(): Flow<ConsistencyMetrics>
    fun getBodyMeasurementsFlow(): Flow<List<BodyMeasurement>>
}
