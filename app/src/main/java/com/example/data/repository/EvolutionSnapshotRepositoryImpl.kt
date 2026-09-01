package com.example.data.repository

import com.example.data.mapper.toDomain
import com.example.domain.evolution.calculator.BodyEvolutionCalculator
import com.example.domain.evolution.model.EvolutionSnapshot
import com.example.domain.evolution.provider.WorkoutMilestoneProvider
import com.example.domain.evolution.repository.AchievementRepository
import com.example.domain.evolution.repository.ConsistencyRepository
import com.example.domain.evolution.repository.EvolutionSnapshotRepository
import com.example.domain.evolution.repository.PerformanceRepository
import com.example.domain.evolution.usecase.GetEvolutionSummaryUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class EvolutionSnapshotRepositoryImpl(
    private val getEvolutionSummaryUseCase: GetEvolutionSummaryUseCase,
    private val performanceRepository: PerformanceRepository,
    private val consistencyRepository: ConsistencyRepository,
    private val bodyMeasurementRepository: BodyMeasurementRepository,
    private val achievementRepository: AchievementRepository,
    private val workoutMilestoneProvider: WorkoutMilestoneProvider
) : EvolutionSnapshotRepository {

    override fun getSnapshotFlow(): Flow<EvolutionSnapshot> {
        val summaryFlow = getEvolutionSummaryUseCase.asFlow()
        val perfSummaryFlow = performanceRepository.getPerformanceSummaryFlow()
        val exerciseEvolutionsFlow = performanceRepository.getAllExercisesEvolutionFlow()
        val prsFlow = performanceRepository.getPersonalRecordsFlow()
        val consistencySummaryFlow = consistencyRepository.getConsistencySummaryFlow()
        val measurementsFlow = bodyMeasurementRepository.allMeasurements
        val achievementsFlow = achievementRepository.getAchievementsFlow()
        val milestonesFlow = workoutMilestoneProvider.getWorkoutMilestoneDatesFlow()

        val perfGroup = combine(perfSummaryFlow, exerciseEvolutionsFlow, prsFlow) { perfSummary, exerciseEvolutions, prs ->
            Triple(perfSummary, exerciseEvolutions, prs)
        }

        val consistencyGroup = combine(consistencySummaryFlow, milestonesFlow) { consistencySummary, milestones ->
            Pair(consistencySummary, milestones)
        }

        return combine(
            summaryFlow,
            perfGroup,
            consistencyGroup,
            measurementsFlow,
            achievementsFlow
        ) { summary, perfTuple, consistencyTuple, measurementEntities, achievements ->
            val (perfSummary, exerciseEvolutions, prs) = perfTuple
            val (consistencySummary, milestones) = consistencyTuple
            val measurements = measurementEntities.map { it.toDomain() }
            val bodySummary = BodyEvolutionCalculator.calculate(measurements)

            EvolutionSnapshot(
                summary = summary,
                performanceSummary = perfSummary,
                exerciseEvolutions = exerciseEvolutions,
                personalRecords = prs,
                consistencySummary = consistencySummary,
                achievements = achievements,
                bodySummary = bodySummary,
                measurements = measurements,
                firstWorkoutDate = milestones.firstWorkoutDate,
                tenthWorkoutDate = milestones.tenthWorkoutDate,
                fiftiethWorkoutDate = milestones.fiftiethWorkoutDate,
                hundredthWorkoutDate = milestones.hundredthWorkoutDate
            )
        }
    }
}
