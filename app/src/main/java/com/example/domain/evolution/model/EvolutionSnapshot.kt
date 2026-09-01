package com.example.domain.evolution.model

import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary

data class EvolutionSnapshot(
    val summary: EvolutionSummary?,
    val performanceSummary: WorkoutPerformanceSummary?,
    val exerciseEvolutions: List<ExercisePerformanceEvolution> = emptyList(),
    val personalRecords: List<PersonalRecord> = emptyList(),
    val consistencySummary: WorkoutConsistencySummary?,
    val achievements: List<Achievement> = emptyList(),
    val bodySummary: BodyEvolutionSummary?,
    val measurements: List<BodyMeasurement> = emptyList(),
    val firstWorkoutDate: Long? = null,
    val tenthWorkoutDate: Long? = null,
    val fiftiethWorkoutDate: Long? = null,
    val hundredthWorkoutDate: Long? = null
)
