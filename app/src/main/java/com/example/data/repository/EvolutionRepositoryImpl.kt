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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class EvolutionRepositoryImpl(
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val workoutDao: WorkoutDao
) : EvolutionRepository {

    /**
     * O histórico completo, sem reemitir o que não mudou.
     *
     * O `InvalidationTracker` do Room reemite esta consulta a cada escrita em `workout_sessions`,
     * `exercise_sessions` **ou** `set_logs` — ou seja, a cada série concluída durante um treino,
     * quando o resultado (só sessões `COMPLETED`) continua exatamente o mesmo. Comparar a lista
     * custa muito menos do que recalcular evolução e performance inteiras para cada coletor.
     */
    private val completedHistory =
        workoutDao.getAllCompletedSessionsWithDetailsFlow().distinctUntilChanged()

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
            completedHistory
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
        return completedHistory.map {
            PerformanceCalculator.calculateFromCalendarSummaries(it)
        }
    }

    override fun getConsistencyMetricsFlow(): Flow<ConsistencyMetrics> {
        // Só os horários de início entram no cálculo: a projeção evita montar o grafo inteiro e,
        // por depender apenas de `workout_sessions`, deixa de ser reemitida a cada série gravada.
        return workoutDao.getCompletedSessionStartTimestampsFlow()
            .distinctUntilChanged()
            .map { ConsistencyCalculator.calculate(it) }
    }

    override fun getBodyMeasurementsFlow(): Flow<List<BodyMeasurement>> {
        return bodyMeasurementRepository.allMeasurements.map { it.toDomain() }
    }
}
