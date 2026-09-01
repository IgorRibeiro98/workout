package com.example.data.repository

import com.example.data.mapper.toDomain
import com.example.domain.evolution.calculator.AchievementCalculator
import com.example.domain.evolution.calculator.BodyEvolutionCalculator
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.repository.AchievementRepository
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.domain.evolution.repository.EvolutionRepository
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

class AchievementRepositoryImpl(
    private val evolutionRepository: EvolutionRepository?,
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase?,
    private val performanceRepository: PerformanceRepository?,
    private val consistencyRepository: ConsistencyRepository?,
    private val bodyMeasurementRepository: BodyMeasurementRepository?
) : AchievementRepository {

    override fun getAchievementsFlow(): Flow<List<Achievement>> {
        val summaryFlow = getEvolutionSummaryUseCase?.asFlow() ?: flowOf(null)
        val perfSummaryFlow = performanceRepository?.getPerformanceSummaryFlow() ?: flowOf(null)
        val exerciseEvolutionsFlow = performanceRepository?.getAllExercisesEvolutionFlow() ?: flowOf(emptyList())
        val prsFlow = performanceRepository?.getPersonalRecordsFlow() ?: flowOf(emptyList())
        val consistencySummaryFlow = consistencyRepository?.getConsistencySummaryFlow() ?: flowOf(null)
        val measurementsFlow = bodyMeasurementRepository?.allMeasurements ?: flowOf(emptyList())

        val perfFlow = combine(perfSummaryFlow, exerciseEvolutionsFlow, prsFlow) { perfSummary, exerciseEvolutions, prs ->
            Triple(perfSummary, exerciseEvolutions, prs)
        }

        return combine(
            summaryFlow,
            perfFlow,
            consistencySummaryFlow,
            measurementsFlow
        ) { summary, perfTuple, consistencySummary, measurementEntities ->
            val (perfSummary, exerciseEvolutions, prs) = perfTuple
            val measurements = measurementEntities.map { it.toDomain() }
            val bodySummary = BodyEvolutionCalculator.calculate(measurements)

            AchievementCalculator.calculateAchievements(
                summary = summary,
                performanceSummary = perfSummary,
                exerciseEvolutions = exerciseEvolutions,
                personalRecords = prs,
                consistencySummary = consistencySummary,
                bodySummary = bodySummary,
                measurements = measurements
            )
        }
    }

    override suspend fun getAchievements(): List<Achievement> {
        return getAchievementsFlow().first()
    }
}
