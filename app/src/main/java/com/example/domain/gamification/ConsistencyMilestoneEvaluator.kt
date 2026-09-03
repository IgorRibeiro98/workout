package com.example.domain.gamification

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

    /** Marcos de consistência medidos em semanas consecutivas com pelo menos um treino. */
    val STREAK_MILESTONES_IN_WEEKS = listOf(4, 8, 12, 16, 24, 52)

    fun evaluate(
        workoutTimestamps: List<Long>,
        weeklyGoal: Int,
        referenceTimestamp: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<GamificationEvent> {
        if (workoutTimestamps.isEmpty()) return emptyList()

        val referenceWeekStart = weekStartOf(referenceTimestamp, zoneId)
        val sessionsByWeek = workoutTimestamps
            .groupingBy { weekStartOf(it, zoneId) }
            .eachCount()

        val events = mutableListOf<GamificationEvent>()

        val completedThisWeek = sessionsByWeek[referenceWeekStart] ?: 0
        if (weeklyGoal > 0 && completedThisWeek >= weeklyGoal) {
            events += GamificationEvents.weeklyGoalCompleted(
                weekStartEpochDay = referenceWeekStart.toEpochDay(),
                weeklyGoal = weeklyGoal,
                completedSessions = completedThisWeek,
                timestamp = referenceTimestamp
            )
        }

        val streakWeeks = consecutiveWeeksEndingAt(referenceWeekStart, sessionsByWeek.keys)
        if (streakWeeks in STREAK_MILESTONES_IN_WEEKS) {
            events += GamificationEvents.streakMilestoneReached(
                streakWeeks = streakWeeks,
                weekStartEpochDay = referenceWeekStart.toEpochDay(),
                timestamp = referenceTimestamp
            )
        }

        return events
    }

    /** Semanas consecutivas com treino, contadas para trás a partir da semana de referência. */
    private fun consecutiveWeeksEndingAt(referenceWeekStart: LocalDate, trainedWeeks: Set<LocalDate>): Int {
        var streak = 0
        var week = referenceWeekStart
        while (trainedWeeks.contains(week)) {
            streak++
            week = week.minusWeeks(1)
        }
        return streak
    }

    private fun weekStartOf(timestamp: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().with(DayOfWeek.MONDAY)
}
