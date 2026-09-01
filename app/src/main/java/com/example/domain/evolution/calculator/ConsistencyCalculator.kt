package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.consistency.WorkoutConsistencySummary
import com.example.domain.evolution.model.consistency.WorkoutFrequencyPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

object ConsistencyCalculator {

    /**
     * Calculates consistency summary for domain consistency model.
     */
    fun calculateConsistencySummary(
        timestamps: List<Long>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): WorkoutConsistencySummary {
        if (timestamps.isEmpty()) {
            return WorkoutConsistencySummary(
                totalSessions = 0,
                currentStreak = 0,
                longestStreak = 0,
                averageSessionsPerWeek = 0f,
                lastWorkoutDate = null
            )
        }

        val localDates = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }
        val sortedDistinctDays = localDates.distinct().sorted()

        var longestStreak = 0
        var tempStreak = 0
        var prevDate: LocalDate? = null

        for (date in sortedDistinctDays) {
            if (prevDate != null && date == prevDate.plusDays(1)) {
                tempStreak++
            } else {
                tempStreak = 1
            }
            if (tempStreak > longestStreak) {
                longestStreak = tempStreak
            }
            prevDate = date
        }

        var currentStreak = 0
        var checkDate = sortedDistinctDays.last()
        val distinctSet = sortedDistinctDays.toSet()

        while (distinctSet.contains(checkDate)) {
            currentStreak++
            checkDate = checkDate.minusDays(1)
        }

        val totalSessions = timestamps.size
        val firstDate = sortedDistinctDays.first()
        val lastDate = sortedDistinctDays.last()
        val daysSpan = ChronoUnit.DAYS.between(firstDate, lastDate) + 1
        val weeksSpan = max(1.0f, daysSpan / 7.0f)
        val averageSessionsPerWeek = totalSessions / weeksSpan
        val roundedAvg = kotlin.math.round(averageSessionsPerWeek * 10f) / 10f

        return WorkoutConsistencySummary(
            totalSessions = totalSessions,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            averageSessionsPerWeek = roundedAvg,
            lastWorkoutDate = timestamps.maxOrNull()
        )
    }

    /**
     * Calculates weekly workout frequency history points.
     */
    fun calculateFrequencyHistory(
        timestamps: List<Long>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<WorkoutFrequencyPoint> {
        if (timestamps.isEmpty()) return emptyList()

        val datesWithWeekStart = timestamps.map { ts ->
            val date = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate()
            val weekStart = date.with(DayOfWeek.MONDAY)
            weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli() to ts
        }

        val grouped = datesWithWeekStart.groupBy { it.first }
        return grouped.map { (weekStartMillis, items) ->
            WorkoutFrequencyPoint(
                date = weekStartMillis,
                sessions = items.size
            )
        }.sortedBy { it.date }
    }

    /**
     * Calculates legacy consistency metrics from a list of workout timestamps (in epoch milliseconds).
     */
    fun calculate(
        timestamps: List<Long>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): ConsistencyMetrics {
        if (timestamps.isEmpty()) {
            return ConsistencyMetrics(
                trainingDays = 0,
                currentStreak = 0,
                longestStreak = 0,
                monthlySessions = 0,
                averageSessionsPerWeek = 0f
            )
        }

        val localDates = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }

        return calculateFromDates(localDates, Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate())
    }

    /**
     * Calculates consistency metrics from a list of LocalDate instances.
     */
    fun calculateFromDates(
        dates: List<LocalDate>,
        referenceDate: LocalDate = LocalDate.now()
    ): ConsistencyMetrics {
        if (dates.isEmpty()) {
            return ConsistencyMetrics(
                trainingDays = 0,
                currentStreak = 0,
                longestStreak = 0,
                monthlySessions = 0,
                averageSessionsPerWeek = 0f
            )
        }

        val sortedDistinctDays = dates.distinct().sorted()
        val trainingDays = sortedDistinctDays.size

        // Longest Streak calculation
        var longestStreak = 0
        var tempStreak = 0
        var prevDate: LocalDate? = null

        for (date in sortedDistinctDays) {
            if (prevDate != null && date == prevDate.plusDays(1)) {
                tempStreak++
            } else {
                tempStreak = 1
            }
            if (tempStreak > longestStreak) {
                longestStreak = tempStreak
            }
            prevDate = date
        }

        // Current Streak calculation (consecutive days ending at the last trained day)
        var currentStreak = 0
        var checkDate = sortedDistinctDays.last()
        val distinctSet = sortedDistinctDays.toSet()

        while (distinctSet.contains(checkDate)) {
            currentStreak++
            checkDate = checkDate.minusDays(1)
        }

        // Monthly sessions (within the reference month/year or the latest trained month)
        val targetMonth = referenceDate.month
        val targetYear = referenceDate.year
        val monthlySessions = dates.count { it.month == targetMonth && it.year == targetYear }

        // Average sessions per week
        val firstDate = sortedDistinctDays.first()
        val lastDate = sortedDistinctDays.last()
        val daysSpan = ChronoUnit.DAYS.between(firstDate, lastDate) + 1
        val weeksSpan = max(1.0f, daysSpan / 7.0f)
        val averageSessionsPerWeek = dates.size / weeksSpan
        val roundedAvg = kotlin.math.round(averageSessionsPerWeek * 10f) / 10f

        return ConsistencyMetrics(
            trainingDays = trainingDays,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            monthlySessions = monthlySessions,
            averageSessionsPerWeek = roundedAvg
        )
    }
}
