package com.example.data.repository

import com.example.data.mapper.toDomain
import com.example.domain.evolution.calculator.BodyEvolutionCalculator
import com.example.domain.evolution.calculator.TimelineCalculator
import com.example.domain.evolution.model.timeline.EvolutionTimelineEvent
import com.example.domain.evolution.repository.AchievementRepository
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.evolution.repository.TimelineRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

class TimelineRepositoryImpl(
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase?,
    private val performanceRepository: PerformanceRepository?,
    private val consistencyRepository: ConsistencyRepository?,
    private val bodyMeasurementRepository: BodyMeasurementRepository?,
    private val achievementRepository: AchievementRepository?
) : TimelineRepository {

    override fun getTimelineFlow(): Flow<List<EvolutionTimelineEvent>> {
        val summaryFlow = getEvolutionSummaryUseCase?.asFlow() ?: flowOf(null)
        val perfSummaryFlow = performanceRepository?.getPerformanceSummaryFlow() ?: flowOf(null)
        val exerciseEvolutionsFlow = performanceRepository?.getAllExercisesEvolutionFlow() ?: flowOf(emptyList())
        val prsFlow = performanceRepository?.getPersonalRecordsFlow() ?: flowOf(emptyList())
        val consistencySummaryFlow = consistencyRepository?.getConsistencySummaryFlow() ?: flowOf(null)
        val measurementsFlow = bodyMeasurementRepository?.allMeasurements ?: flowOf(emptyList())
        val achievementsFlow = achievementRepository?.getAchievementsFlow() ?: flowOf(emptyList())

        val perfFlow = combine(perfSummaryFlow, exerciseEvolutionsFlow, prsFlow) { perfSummary, exerciseEvolutions, prs ->
            Triple(perfSummary, exerciseEvolutions, prs)
        }

        return combine(
            summaryFlow,
            perfFlow,
            consistencySummaryFlow,
            measurementsFlow,
            achievementsFlow
        ) { summary, perfTuple, consistencySummary, measurementEntities, achievements ->
            val (perfSummary, exerciseEvolutions, prs) = perfTuple
            val measurements = measurementEntities.map { it.toDomain() }
            val bodySummary = BodyEvolutionCalculator.calculate(measurements)

            TimelineCalculator.calculateTimelineEvents(
                summary = summary,
                performanceSummary = perfSummary,
                exerciseEvolutions = exerciseEvolutions,
                personalRecords = prs,
                consistencySummary = consistencySummary,
                achievements = achievements,
                bodySummary = bodySummary,
                measurements = measurements
            )
        }
    }
}
