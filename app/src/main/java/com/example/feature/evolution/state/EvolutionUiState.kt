package com.example.feature.evolution.state

import com.example.domain.evolution.model.BMICategory
import com.example.domain.evolution.model.BodyEvolutionSummary
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.PerformanceEvolution
import com.example.domain.evolution.model.WeightEvolution
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.VolumePoint
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.model.performance.chart.StrengthPoint

data class EvolutionUiState(
    val isLoading: Boolean = false,
    val summary: EvolutionSummary? = null,
    val performance: PerformanceEvolution? = null,
    val consistency: ConsistencyMetrics? = null,
    val consistencySummary: WorkoutConsistencySummary? = null,
    val frequencyHistory: List<WorkoutFrequencyPoint> = emptyList(),
    val workoutTimestamps: List<Long> = emptyList(),
    val weightEvolution: WeightEvolution? = null,
    val measurements: List<BodyMeasurement> = emptyList(),
    val bodyEvolutionSummary: BodyEvolutionSummary? = null,
    val currentWeight: Float? = null,
    val initialWeight: Float? = null,
    val weightVariation: Float? = null,
    val currentHeight: Float? = null,
    val bmi: Float? = null,
    val bmiCategory: BMICategory? = null,
    val performanceSummary: WorkoutPerformanceSummary? = null,
    val exerciseEvolutions: List<ExercisePerformanceEvolution> = emptyList(),
    val personalRecords: List<PersonalRecord> = emptyList(),
    val volumeHistory: List<VolumePoint> = emptyList(),
    val selectedExerciseId: String? = null,
    val selectedExerciseName: String? = null,
    val strengthHistory: List<StrengthPoint> = emptyList(),
    val error: String? = null
) {
    val isEmpty: Boolean
        get() {
            if (isLoading) return false
            val hasWeight = (summary?.currentWeight != null && summary.currentWeight > 0f) ||
                    (weightEvolution?.currentWeight != null && weightEvolution.currentWeight > 0f) ||
                    (currentWeight != null && currentWeight > 0f) ||
                    (bodyEvolutionSummary?.currentWeight != null && bodyEvolutionSummary.currentWeight > 0f)
            val hasWorkouts = (summary?.totalWorkoutSessions ?: 0) > 0 || 
                    (performance?.totalSessions ?: 0) > 0 || 
                    (performanceSummary?.totalSessions ?: 0) > 0
            val hasExercises = (summary?.totalExercisesPerformed ?: 0) > 0 || 
                    (performance?.totalExercises ?: 0) > 0 ||
                    exerciseEvolutions.isNotEmpty()
            val hasTrainingDays = (summary?.trainingDays ?: 0) > 0 || (consistency?.trainingDays ?: 0) > 0
            val hasMeasurements = measurements.isNotEmpty()
            val hasPRs = personalRecords.isNotEmpty()
            return !hasWeight && !hasWorkouts && !hasExercises && !hasTrainingDays && !hasMeasurements && !hasPRs
        }
}
