package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.achievement.AchievementCatalog
import com.example.domain.evolution.model.achievement.AchievementCategory
import com.example.domain.evolution.model.achievement.AchievementEvaluation
import com.example.domain.evolution.model.achievement.AchievementEvaluationContext
import com.example.domain.gamification.model.GamificationEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AchievementEvaluator {
    fun evaluate(context: AchievementEvaluationContext): List<AchievementEvaluation> {
        return AchievementCatalog.DEFINITIONS.map { definition ->
            when (definition.category) {
                AchievementCategory.TRAINING -> evaluateTraining(definition, context)
                AchievementCategory.CONSISTENCY -> evaluateConsistency(definition, context)
                AchievementCategory.PERFORMANCE -> evaluatePerformance(definition, context)
                AchievementCategory.BODY -> evaluateBody(definition, context)
            }
        }
    }

    private fun evaluateTraining(definition: com.example.domain.evolution.model.achievement.AchievementDefinition, context: AchievementEvaluationContext): AchievementEvaluation {
        val currentProgress = context.completedWorkoutsCount
        val target = definition.target
        val eligible = currentProgress >= target
        
        // Find exactly when the N-th workout happened
        var reachedAt: Long? = null
        var triggerEventId: String? = null
        if (eligible) {
            val sorted = context.completedWorkoutsTimestamps.sorted()
            if (sorted.size >= target) {
                reachedAt = sorted[target - 1]
            }
            if (reachedAt != null) {
                triggerEventId = context.gamificationEvents.firstOrNull {
                    it.timestamp == reachedAt && (it.type == GamificationEventType.WORKOUT_COMPLETED || it.type == GamificationEventType.FIRST_WORKOUT_COMPLETED)
                }?.id
            }
        }

        return AchievementEvaluation(definition, currentProgress, target, eligible, reachedAt, triggerEventId)
    }

    private fun evaluateConsistency(definition: com.example.domain.evolution.model.achievement.AchievementDefinition, context: AchievementEvaluationContext): AchievementEvaluation {
        val currentProgress = context.consistencyProgress?.longestStreakWeeks ?: 0
        val target = definition.target
        val eligible = currentProgress >= target
        var reachedAt: Long? = null
        var triggerEventId: String? = null

        if (eligible) {
            // Find STREAK_MILESTONE_REACHED events
            val streakEvents = context.gamificationEvents
                .filter { it.type == GamificationEventType.STREAK_MILESTONE_REACHED }
                .sortedBy { it.timestamp }
            
            for (ev in streakEvents) {
                val valStr = ev.metadata[com.example.domain.gamification.model.GamificationEventMetadata.STREAK_WEEKS] ?: ""
                val weeks = valStr.toIntOrNull() ?: 0
                if (weeks == target) {
                    reachedAt = ev.timestamp
                    triggerEventId = ev.id
                    break
                }
            }
        }

        return AchievementEvaluation(definition, currentProgress, target, eligible, reachedAt, triggerEventId)
    }

    private fun evaluatePerformance(definition: com.example.domain.evolution.model.achievement.AchievementDefinition, context: AchievementEvaluationContext): AchievementEvaluation {
        val prEvents = context.gamificationEvents
            .filter { it.type == GamificationEventType.PERSONAL_RECORD_CREATED }
            .sortedBy { it.timestamp }
        val currentProgress = prEvents.size
        val target = definition.target
        val eligible = currentProgress >= target

        var reachedAt: Long? = null
        var triggerEventId: String? = null
        if (eligible && prEvents.size >= target) {
            reachedAt = prEvents[target - 1].timestamp
            triggerEventId = prEvents[target - 1].id
        }

        return AchievementEvaluation(definition, currentProgress, target, eligible, reachedAt, triggerEventId)
    }

    private fun evaluateBody(definition: com.example.domain.evolution.model.achievement.AchievementDefinition, context: AchievementEvaluationContext): AchievementEvaluation {
        // distinct dates
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
        val distinctDates = context.measurements
            .map { formatter.format(Instant.ofEpochMilli(it.date)) to it.date }
            .groupBy { it.first }
            .map { it.value.minByOrNull { m -> m.second }!!.second }
            .sorted()
            
        val currentProgress = distinctDates.size
        val target = definition.target
        val eligible = currentProgress >= target

        var reachedAt: Long? = null
        if (eligible && distinctDates.size >= target) {
            reachedAt = distinctDates[target - 1]
        }

        return AchievementEvaluation(definition, currentProgress, target, eligible, reachedAt)
    }
}
