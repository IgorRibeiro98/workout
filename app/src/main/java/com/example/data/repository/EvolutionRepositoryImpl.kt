package com.example.data.repository

import com.example.data.local.WorkoutDao
import com.example.data.mapper.toDomain
import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.calculator.PerformanceCalculator
import com.example.domain.evolution.calculator.WeightEvolutionCalculator
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.WeightEvolution
import com.example.domain.evolution.repository.EvolutionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class EvolutionRepositoryImpl(
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val workoutDao: WorkoutDao
) : EvolutionRepository {

    override suspend fun getEvolutionSummary(): EvolutionSummary {
        val measurements = bodyMeasurementRepository.getAllMeasurementsSync().toDomain()
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()

        val weightEvolution = WeightEvolutionCalculator.calculateFromMeasurements(measurements)
        val performance = PerformanceCalculator.calculateFromCalendarSummaries(sessions)
        val consistency = ConsistencyCalculator.calculate(sessions.map { it.session.startedAt })

        return EvolutionSummary(
            currentWeight = weightEvolution.currentWeight,
            initialWeight = weightEvolution.firstWeight,
            weightChange = weightEvolution.variation,
            totalWorkoutSessions = performance.totalSessions,
            trainingDays = consistency.trainingDays,
            averageWorkoutsPerWeek = consistency.averageSessionsPerWeek,
            totalExercisesPerformed = performance.totalExercises,
            generatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun getWeightEvolution(): WeightEvolution {
        val measurements = bodyMeasurementRepository.getAllMeasurementsSync().toDomain()
        return WeightEvolutionCalculator.calculateFromMeasurements(measurements)
    }

    override suspend fun getPerformanceEvolution(): PerformanceEvolution {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return PerformanceCalculator.calculateFromCalendarSummaries(sessions)
    }

    override suspend fun getConsistencyMetrics(): ConsistencyMetrics {
        val sessions = workoutDao.getAllCompletedSessionsWithDetails()
        return ConsistencyCalculator.calculate(sessions.map { it.session.startedAt })
    }

    override suspend fun getBodyMeasurements(): List<BodyMeasurement> {
        return bodyMeasurementRepository.getAllMeasurementsSync().toDomain()
    }

    override fun getEvolutionSummaryFlow(): Flow<EvolutionSummary> {
        return combine(
            bodyMeasurementRepository.allMeasurements,
            workoutDao.getAllCompletedSessionsWithDetailsFlow()
        ) { measurementsEntities, sessions ->
            val measurements = measurementsEntities.toDomain()
            val weightEvolution = WeightEvolutionCalculator.calculateFromMeasurements(measurements)
            val performance = PerformanceCalculator.calculateFromCalendarSummaries(sessions)
            val consistency = ConsistencyCalculator.calculate(sessions.map { it.session.startedAt })

            EvolutionSummary(
                currentWeight = weightEvolution.currentWeight,
                initialWeight = weightEvolution.firstWeight,
                weightChange = weightEvolution.variation,
                totalWorkoutSessions = performance.totalSessions,
                trainingDays = consistency.trainingDays,
                averageWorkoutsPerWeek = consistency.averageSessionsPerWeek,
                totalExercisesPerformed = performance.totalExercises,
                generatedAt = System.currentTimeMillis()
            )
        }
    }

    override fun getWeightEvolutionFlow(): Flow<WeightEvolution> {
        return bodyMeasurementRepository.allMeasurements.map {
            WeightEvolutionCalculator.calculateFromMeasurements(it.toDomain())
        }
    }

    override fun getPerformanceEvolutionFlow(): Flow<PerformanceEvolution> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map {
            PerformanceCalculator.calculateFromCalendarSummaries(it)
        }
    }

    override fun getConsistencyMetricsFlow(): Flow<ConsistencyMetrics> {
        return workoutDao.getAllCompletedSessionsWithDetailsFlow().map { sessions ->
            ConsistencyCalculator.calculate(sessions.map { it.session.startedAt })
        }
    }

    override fun getBodyMeasurementsFlow(): Flow<List<BodyMeasurement>> {
        return bodyMeasurementRepository.allMeasurements.map { it.toDomain() }
    }
}
