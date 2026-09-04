package com.example.domain.gamification

import com.example.domain.evolution.calculator.ConsistencyCalculator
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
import com.example.domain.gamification.model.GamificationEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Deriva os fatos de consistência a partir do histórico de treinos concluídos.
 *
 * É uma função pura: recebe o histórico e devolve os fatos. Não persiste, não pontua, não decide
 * nada visual. A idempotência fica por conta da `dedupeKey` de cada evento.
 */
object ConsistencyMilestoneEvaluator {

    /** Marcos de consistência medidos em semanas consecutivas cumprindo a meta semanal. */
    val STREAK_MILESTONES_IN_WEEKS = listOf(2, 4, 8, 12, 24, 52)

    fun evaluate(
        workoutTimestamps: List<Long>,
        weeklyGoal: Int = 3,
        goalSnapshots: List<WeeklyGoalSnapshot> = emptyList(),
        referenceTimestamp: Long,
        trackingStartedAtEpochDay: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<GamificationEvent> {
        if (workoutTimestamps.isEmpty()) return emptyList()

        val referenceDate = Instant.ofEpochMilli(referenceTimestamp).atZone(zoneId).toLocalDate()
        val referenceWeekStart = referenceDate.with(DayOfWeek.MONDAY)

        val weeklyConsistencies = ConsistencyCalculator.calculateWeeklyConsistencies(
            timestamps = workoutTimestamps,
            goalSnapshots = goalSnapshots,
            defaultGoal = weeklyGoal,
            referenceDate = referenceDate,
            trackingStartedAtEpochDay = trackingStartedAtEpochDay,
            zoneId = zoneId
        )

        val events = mutableListOf<GamificationEvent>()

        val refWeek = weeklyConsistencies.firstOrNull { it.weekStartEpochDay == referenceWeekStart.toEpochDay() }
        if (refWeek != null && refWeek.goal > 0 && refWeek.completedWorkouts >= refWeek.goal) {
            events += GamificationEvents.weeklyGoalCompleted(
                weekStartEpochDay = refWeek.weekStartEpochDay,
                weeklyGoal = refWeek.goal,
                completedSessions = refWeek.completedWorkouts,
                timestamp = referenceTimestamp
            )
        }

        val progress = ConsistencyCalculator.calculateProgress(weeklyConsistencies, referenceDate)
        if (progress.currentStreakWeeks in STREAK_MILESTONES_IN_WEEKS) {
            events += GamificationEvents.streakMilestoneReached(
                streakWeeks = progress.currentStreakWeeks,
                weekStartEpochDay = referenceWeekStart.toEpochDay(),
                timestamp = referenceTimestamp
            )
        }

        return events
    }
}
