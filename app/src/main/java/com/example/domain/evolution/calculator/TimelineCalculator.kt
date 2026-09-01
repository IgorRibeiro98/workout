package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.BodyEvolutionSummary
import com.example.domain.evolution.model.BodyMeasurement
import com.example.domain.evolution.model.EvolutionSummary
import com.example.domain.evolution.model.achievement.Achievement
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.domain.evolution.model.performance.PersonalRecord
import com.example.domain.evolution.model.performance.WorkoutPerformanceSummary
import com.example.domain.evolution.model.timeline.EvolutionTimelineEvent
import com.example.domain.evolution.model.timeline.TimelineEventCategory

object TimelineCalculator {

    fun calculateTimelineEvents(
        summary: EvolutionSummary?,
        performanceSummary: WorkoutPerformanceSummary?,
        exerciseEvolutions: List<ExercisePerformanceEvolution> = emptyList(),
        personalRecords: List<PersonalRecord> = emptyList(),
        consistencySummary: WorkoutConsistencySummary?,
        achievements: List<Achievement> = emptyList(),
        bodySummary: BodyEvolutionSummary?,
        measurements: List<BodyMeasurement> = emptyList()
    ): List<EvolutionTimelineEvent> {
        val events = mutableListOf<EvolutionTimelineEvent>()

        val totalSessions = summary?.totalWorkoutSessions
            ?: consistencySummary?.totalSessions
            ?: performanceSummary?.totalSessions
            ?: 0

        val baseDate = consistencySummary?.lastWorkoutDate
            ?: summary?.generatedAt
            ?: System.currentTimeMillis()

        // 1. Training Events
        if (totalSessions >= 1) {
            events.add(
                EvolutionTimelineEvent(
                    id = "event_first_workout",
                    date = baseDate,
                    title = "Primeiro treino",
                    description = "Você completou seu primeiro treino e iniciou sua jornada.",
                    icon = "🏋️",
                    category = TimelineEventCategory.TRAINING
                )
            )
        }

        if (totalSessions >= 10) {
            events.add(
                EvolutionTimelineEvent(
                    id = "event_10_workouts",
                    date = baseDate,
                    title = "10 treinos realizados",
                    description = "Você alcançou a marca de 10 treinos!",
                    icon = "🏋️",
                    category = TimelineEventCategory.TRAINING
                )
            )
        }

        if (totalSessions >= 50) {
            events.add(
                EvolutionTimelineEvent(
                    id = "event_50_workouts",
                    date = baseDate,
                    title = "50 treinos realizados",
                    description = "Você alcançou a marca de 50 treinos!",
                    icon = "🏋️",
                    category = TimelineEventCategory.TRAINING
                )
            )
        }

        if (totalSessions >= 100) {
            events.add(
                EvolutionTimelineEvent(
                    id = "event_100_workouts",
                    date = baseDate,
                    title = "100 treinos realizados",
                    description = "Marca incrível de 100 treinos concluídos!",
                    icon = "🏋️",
                    category = TimelineEventCategory.TRAINING
                )
            )
        }

        // 2. Personal Records
        personalRecords.forEachIndexed { index, pr ->
            val titleText = if (index == 0) "Primeiro recorde pessoal" else "Novo recorde pessoal"
            events.add(
                EvolutionTimelineEvent(
                    id = "event_pr_${pr.exerciseId}_${pr.achievedAt}",
                    date = pr.achievedAt,
                    title = titleText,
                    description = "${pr.exerciseName}\n${pr.maxWeight.toInt()}kg x ${pr.repetitions}",
                    icon = "🏆",
                    category = TimelineEventCategory.PERFORMANCE
                )
            )
        }

        // 3. Unlocked Achievements
        achievements.filter { it.unlockedAt != null }.forEach { achievement ->
            events.add(
                EvolutionTimelineEvent(
                    id = "event_achievement_${achievement.id}",
                    date = achievement.unlockedAt!!,
                    title = "Conquista desbloqueada",
                    description = achievement.title,
                    icon = achievement.icon,
                    category = TimelineEventCategory.ACHIEVEMENT
                )
            )
        }

        // 4. Body Evolution & Measurements
        val variation = bodySummary?.weightVariation
        val initialWeight = bodySummary?.initialWeight
        val currentWeight = bodySummary?.currentWeight
        if (bodySummary != null && initialWeight != null && currentWeight != null && variation != null && variation != 0f) {
            val isLoss = variation < 0
            val icon = if (isLoss) "📉" else "📈"
            events.add(
                EvolutionTimelineEvent(
                    id = "event_body_evolution_summary",
                    date = baseDate,
                    title = "Evolução corporal",
                    description = "Peso: ${initialWeight.toInt()}kg → ${currentWeight.toInt()}kg",
                    icon = icon,
                    category = TimelineEventCategory.BODY
                )
            )
        }

        measurements.forEach { m ->
            val descLines = mutableListOf<String>()
            m.weightKg?.let { descLines.add("Peso: ${it.toInt()}kg") }
            m.waistCm?.let { descLines.add("Cintura: ${it.toInt()}cm") }
            events.add(
                EvolutionTimelineEvent(
                    id = "event_measurement_${m.id}",
                    date = m.date,
                    title = "Nova medição registrada",
                    description = descLines.joinToString("\n"),
                    icon = "📏",
                    category = TimelineEventCategory.BODY
                )
            )
        }

        // 5. Sort by date descending & limit to 20
        return events
            .sortedByDescending { it.date }
            .distinctBy { it.id }
            .take(20)
    }
}
