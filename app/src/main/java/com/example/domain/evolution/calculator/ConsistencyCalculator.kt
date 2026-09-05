package com.example.domain.evolution.calculator

import com.example.domain.evolution.model.ConsistencyMetrics
import com.example.domain.evolution.model.consistency.ConsistencyProgress
import com.example.domain.evolution.model.consistency.WeeklyConsistency
import com.example.domain.evolution.model.consistency.WeeklyConsistencyStatus
import com.example.domain.evolution.model.consistency.WeeklyGoalSnapshot
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
     * Segunda-feira da semana que contém [date]: a definição de semana usada por toda a consistência.
     *
     * Exposta para que outras camadas (missões, por exemplo) compartilhem exatamente esta regra em
     * vez de recriarem um segundo conceito de início de semana.
     */
    fun weekStart(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    /** Mesma regra de [weekStart] a partir de um instante. */
    fun weekStartEpochDay(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        weekStart(Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()).toEpochDay()

    /**
     * Builds weekly consistencies from workout timestamps and historical goal snapshots.
     */
    fun calculateWeeklyConsistencies(
        timestamps: List<Long>,
        goalSnapshots: List<WeeklyGoalSnapshot> = emptyList(),
        defaultGoal: Int = 3,
        referenceDate: LocalDate = LocalDate.now(),
        trackingStartedAtEpochDay: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<WeeklyConsistency> {
        if (trackingStartedAtEpochDay == null) return emptyList()

        val currentMonday = referenceDate.with(DayOfWeek.MONDAY)
        val sortedGoalSnapshots = goalSnapshots.sortedBy { it.effectiveFromWeek }

        fun resolveGoal(weekStartEpochDay: Long): Int? {
            val snapshot = sortedGoalSnapshots.lastOrNull { it.effectiveFromWeek <= weekStartEpochDay }
            return snapshot?.goal
        }

        val trackingStartDate = LocalDate.ofEpochDay(trackingStartedAtEpochDay)
        val startMonday = trackingStartDate.with(DayOfWeek.MONDAY)

        // Only count workouts that happened on or after trackingStartMonday
        val validWorkouts = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }.filter { !it.isBefore(startMonday) }

        val workoutsByWeek = validWorkouts
            .map { it.with(DayOfWeek.MONDAY) }
            .groupingBy { it }
            .eachCount()

        val result = mutableListOf<WeeklyConsistency>()
        var week = startMonday

        if (week.isAfter(currentMonday)) return emptyList()

        while (!week.isAfter(currentMonday)) {
            val epochDay = week.toEpochDay()
            val count = workoutsByWeek[week] ?: 0
            val goal = resolveGoal(epochDay)
            val isCurrentWeek = (week == currentMonday)
            val isFirstTrackingWeek = (epochDay == startMonday.toEpochDay())
            val isStartedMidWeek = isFirstTrackingWeek && trackingStartedAtEpochDay > startMonday.toEpochDay()

            if (goal == null) {
                result.add(
                    WeeklyConsistency(
                        weekStartEpochDay = epochDay,
                        goal = 0,
                        completedWorkouts = count,
                        status = WeeklyConsistencyStatus.NOT_COUNTED
                    )
                )
            } else {
                var status = if (isCurrentWeek) {
                    if (count >= goal) WeeklyConsistencyStatus.COMPLETED else WeeklyConsistencyStatus.IN_PROGRESS
                } else {
                    if (count >= goal) WeeklyConsistencyStatus.COMPLETED else WeeklyConsistencyStatus.MISSED
                }

                if (status == WeeklyConsistencyStatus.MISSED && isStartedMidWeek) {
                    status = WeeklyConsistencyStatus.NOT_COUNTED
                }

                result.add(
                    WeeklyConsistency(
                        weekStartEpochDay = epochDay,
                        goal = goal,
                        completedWorkouts = count,
                        status = status
                    )
                )
            }
            week = week.plusWeeks(1)
        }

        return result
    }

    /**
     * Calculates the consistency progress (current and longest streak in weeks).
     */
    fun calculateProgress(
        weeklyConsistencies: List<WeeklyConsistency>,
        referenceDate: LocalDate = LocalDate.now()
    ): ConsistencyProgress {
        if (weeklyConsistencies.isEmpty()) {
            return ConsistencyProgress(
                currentStreakWeeks = 0,
                longestStreakWeeks = 0,
                currentWeekCompleted = 0,
                currentWeekGoal = 3,
                currentWeekStatus = WeeklyConsistencyStatus.IN_PROGRESS
            )
        }

        val currentMonday = referenceDate.with(DayOfWeek.MONDAY)
        val currentWeek = weeklyConsistencies.firstOrNull { it.weekStartEpochDay == currentMonday.toEpochDay() }
            ?: weeklyConsistencies.last()

        val pastWeeks = weeklyConsistencies.filter { it.weekStartEpochDay < currentWeek.weekStartEpochDay }

        // Calculate Current Streak
        var currentStreak = 0
        if (currentWeek.status == WeeklyConsistencyStatus.COMPLETED) {
            currentStreak = 1
        }
        
        for (i in (pastWeeks.size - 1) downTo 0) {
            val past = pastWeeks[i]
            if (past.status == WeeklyConsistencyStatus.COMPLETED) {
                currentStreak++
            } else if (past.status == WeeklyConsistencyStatus.NOT_COUNTED) {
                continue
            } else {
                break
            }
        }

        if (currentWeek.status == WeeklyConsistencyStatus.MISSED) {
            currentStreak = 0
        }

        // Calculate Longest Streak
        var longestStreak = 0
        var currentRun = 0

        for (w in pastWeeks) {
            if (w.status == WeeklyConsistencyStatus.COMPLETED) {
                currentRun++
                if (currentRun > longestStreak) longestStreak = currentRun
            } else if (w.status == WeeklyConsistencyStatus.NOT_COUNTED) {
                // Does not break run, does not add
            } else {
                currentRun = 0
            }
        }

        if (currentWeek.status == WeeklyConsistencyStatus.COMPLETED) {
            currentRun++
            if (currentRun > longestStreak) longestStreak = currentRun
        }

        if (currentStreak > longestStreak) {
            longestStreak = currentStreak
        }

        return ConsistencyProgress(
            currentStreakWeeks = currentStreak,
            longestStreakWeeks = longestStreak,
            currentWeekCompleted = currentWeek.completedWorkouts,
            currentWeekGoal = currentWeek.goal,
            currentWeekStatus = currentWeek.status
        )
    }

    /**
     * Calculates consistency summary for domain consistency model using weekly streaks.
     */
    fun calculateConsistencySummary(
        timestamps: List<Long>,
        goalSnapshots: List<WeeklyGoalSnapshot> = emptyList(),
        defaultGoal: Int = 3,
        trackingStartedAtEpochDay: Long? = null,
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

        val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val weeklyConsistencies = calculateWeeklyConsistencies(
            timestamps = timestamps,
            goalSnapshots = goalSnapshots,
            defaultGoal = defaultGoal,
            referenceDate = referenceDate,
            trackingStartedAtEpochDay = trackingStartedAtEpochDay,
            zoneId = zoneId
        )

        val progress = calculateProgress(weeklyConsistencies, referenceDate)

        val localDates = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }
        val sortedDistinctDays = localDates.distinct().sorted()
        val totalSessions = timestamps.size
        val firstDate = sortedDistinctDays.first()
        val lastDate = sortedDistinctDays.last()
        val daysSpan = ChronoUnit.DAYS.between(firstDate, lastDate) + 1
        val weeksSpan = max(1.0f, daysSpan / 7.0f)
        val averageSessionsPerWeek = totalSessions / weeksSpan
        val roundedAvg = kotlin.math.round(averageSessionsPerWeek * 10f) / 10f

        return WorkoutConsistencySummary(
            totalSessions = totalSessions,
            currentStreak = progress.currentStreakWeeks,
            longestStreak = progress.longestStreakWeeks,
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
     * Calculates legacy consistency metrics from a list of local dates.
     */
    fun calculateFromDates(
        dates: List<LocalDate>,
        referenceDate: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ConsistencyMetrics {
        val timestamps = dates.map { it.atStartOfDay(zoneId).toInstant().toEpochMilli() }
        val nowMillis = referenceDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val trackingStart = dates.minOrNull()?.toEpochDay()
        return calculate(timestamps, zoneId, nowMillis, trackingStart)
    }

    /**
     * Calculates legacy consistency metrics.
     */
    fun calculate(
        timestamps: List<Long>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
        trackingStartedAtEpochDay: Long? = null
    ): ConsistencyMetrics {
        val summary = calculateConsistencySummary(
            timestamps = timestamps,
            zoneId = zoneId,
            trackingStartedAtEpochDay = trackingStartedAtEpochDay,
            nowMillis = nowMillis
        )

        val referenceDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val localDates = timestamps.map {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }
        val distinctDays = localDates.distinct()
        val monthlySessions = localDates.count { it.month == referenceDate.month && it.year == referenceDate.year }

        return ConsistencyMetrics(
            trainingDays = distinctDays.size,
            currentStreak = summary.currentStreak,
            longestStreak = summary.longestStreak,
            monthlySessions = monthlySessions,
            averageSessionsPerWeek = summary.averageSessionsPerWeek
        )
    }
}
