package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.BodyEvolutionSummary
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary

object AchievementCalculator {

    fun calculateAchievements(
        summary: EvolutionSummary?,
        performanceSummary: WorkoutPerformanceSummary?,
        exerciseEvolutions: List<ExercisePerformanceEvolution> = emptyList(),
        personalRecords: List<PersonalRecord> = emptyList(),
        consistencySummary: WorkoutConsistencySummary?,
        bodySummary: BodyEvolutionSummary?,
        measurements: List<BodyMeasurement> = emptyList()
    ): List<Achievement> {
        val achievements = mutableListOf<Achievement>()

        val totalSessions = summary?.totalWorkoutSessions
            ?: consistencySummary?.totalSessions
            ?: performanceSummary?.totalSessions
            ?: 0

        val longestStreak = consistencySummary?.longestStreak ?: summary?.trainingDays ?: 0
        val prCount = personalRecords.size

        val maxWeightIncrease = exerciseEvolutions.mapNotNull {
            val best = it.bestWeight ?: it.currentWeight ?: 0f
            val first = it.firstWeight ?: 0f
            if (best > 0f && first > 0f) best - first else null
        }.maxOrNull() ?: 0f

        val measurementCount = measurements.size

        val weightChange = bodySummary?.weightVariation
            ?: summary?.weightChange
            ?: 0f

        val now = System.currentTimeMillis()
        val defaultUnlockedTime = consistencySummary?.lastWorkoutDate ?: summary?.generatedAt ?: now

        // --- TRAINING ---
        val unlocked1 = totalSessions >= 1
        achievements.add(
            Achievement(
                id = "first_workout",
                title = "Primeiro treino",
                description = "Complete seu primeiro treino",
                icon = "🏆",
                category = AchievementCategory.TRAINING,
                unlockedAt = if (unlocked1) defaultUnlockedTime else null,
                progress = if (unlocked1) 1.0f else (totalSessions.toFloat() / 1f).coerceIn(0f, 1f),
                currentProgress = totalSessions.coerceAtMost(1),
                targetProgress = 1
            )
        )

        val unlocked10 = totalSessions >= 10
        achievements.add(
            Achievement(
                id = "10_workouts",
                title = "10 treinos",
                description = "Complete 10 treinos realizados",
                icon = "🏆",
                category = AchievementCategory.TRAINING,
                unlockedAt = if (unlocked10) defaultUnlockedTime else null,
                progress = (totalSessions.toFloat() / 10f).coerceIn(0f, 1f),
                currentProgress = totalSessions.coerceAtMost(10),
                targetProgress = 10
            )
        )

        val unlocked50 = totalSessions >= 50
        achievements.add(
            Achievement(
                id = "50_workouts",
                title = "50 treinos",
                description = "Complete 50 treinos realizados",
                icon = "🏆",
                category = AchievementCategory.TRAINING,
                unlockedAt = if (unlocked50) defaultUnlockedTime else null,
                progress = (totalSessions.toFloat() / 50f).coerceIn(0f, 1f),
                currentProgress = totalSessions.coerceAtMost(50),
                targetProgress = 50
            )
        )

        // --- CONSISTENCY ---
        val unlocked7Days = longestStreak >= 7
        achievements.add(
            Achievement(
                id = "first_week",
                title = "Primeira semana",
                description = "Mantenha uma sequência de 7 dias consistentes",
                icon = "🏆",
                category = AchievementCategory.CONSISTENCY,
                unlockedAt = if (unlocked7Days) defaultUnlockedTime else null,
                progress = (longestStreak.toFloat() / 7f).coerceIn(0f, 1f),
                currentProgress = longestStreak.coerceAtMost(7),
                targetProgress = 7
            )
        )

        val unlocked30Days = longestStreak >= 30
        achievements.add(
            Achievement(
                id = "30_days_consistent",
                title = "30 dias consistente",
                description = "Mantenha 30 dias de consistência",
                icon = "🏆",
                category = AchievementCategory.CONSISTENCY,
                unlockedAt = if (unlocked30Days) defaultUnlockedTime else null,
                progress = (longestStreak.toFloat() / 30f).coerceIn(0f, 1f),
                currentProgress = longestStreak.coerceAtMost(30),
                targetProgress = 30
            )
        )

        // --- PERFORMANCE ---
        val unlockedPR = prCount > 0
        achievements.add(
            Achievement(
                id = "first_pr",
                title = "Primeiro PR",
                description = "Conquiste seu primeiro recorde pessoal",
                icon = "🏆",
                category = AchievementCategory.PERFORMANCE,
                unlockedAt = if (unlockedPR) (personalRecords.firstOrNull()?.achievedAt ?: defaultUnlockedTime) else null,
                progress = if (unlockedPR) 1.0f else 0.0f,
                currentProgress = if (unlockedPR) 1 else 0,
                targetProgress = 1
            )
        )

        val unlocked10kg = maxWeightIncrease >= 10f
        achievements.add(
            Achievement(
                id = "load_evolution_10kg",
                title = "Evolução de carga",
                description = "Aumente 10kg em um exercício",
                icon = "🏆",
                category = AchievementCategory.PERFORMANCE,
                unlockedAt = if (unlocked10kg) defaultUnlockedTime else null,
                progress = (maxWeightIncrease / 10f).coerceIn(0f, 1f),
                currentProgress = maxWeightIncrease.toInt().coerceAtMost(10),
                targetProgress = 10
            )
        )

        // --- BODY ---
        val unlockedMeasurement = measurementCount >= 1
        achievements.add(
            Achievement(
                id = "first_measurement",
                title = "Primeira medição",
                description = "Registre sua primeira medição corporal",
                icon = "🏆",
                category = AchievementCategory.BODY,
                unlockedAt = if (unlockedMeasurement) (measurements.firstOrNull()?.date ?: defaultUnlockedTime) else null,
                progress = if (unlockedMeasurement) 1.0f else 0.0f,
                currentProgress = if (unlockedMeasurement) 1 else 0,
                targetProgress = 1
            )
        )

        val weightLost = if (weightChange < 0) -weightChange else 0f
        val unlocked5kgLoss = weightLost >= 5f
        achievements.add(
            Achievement(
                id = "body_evolution_5kg",
                title = "Evolução corporal",
                description = "Elimine 5kg de peso corporal",
                icon = "🏆",
                category = AchievementCategory.BODY,
                unlockedAt = if (unlocked5kgLoss) defaultUnlockedTime else null,
                progress = (weightLost / 5f).coerceIn(0f, 1f),
                currentProgress = weightLost.toInt().coerceAtMost(5),
                targetProgress = 5
            )
        )

        return achievements
    }
}
